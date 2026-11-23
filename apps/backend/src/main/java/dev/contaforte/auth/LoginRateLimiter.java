package dev.contaforte.auth;

import dev.contaforte.common.ApiException;
import dev.contaforte.common.RateLimitedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Limita tentativas de login com falha, por (conta+IP) e por IP, usando contadores no Redis.
 * Falha fechada: se o Redis estiver indisponível o login é recusado (503), nunca liberado.
 */
@Component
class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final DefaultRedisScript<List> INCR = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return {c, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final AppProperties.RateLimit cfg;

    LoginRateLimiter(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.cfg = props.rateLimit();
    }

    /** Lança 429 se a conta ou o IP já estourou o limite de falhas na janela. */
    void check(String email, String ip) {
        try {
            enforce(accountKey(email, ip), cfg.maxFailuresPerAccount());
            enforce(ipKey(ip), cfg.maxFailuresPerIp());
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Redis indisponível para o rate limit de login", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Serviço temporariamente indisponível.");
        }
    }

    void recordFailure(String email, String ip) {
        try {
            for (String key : List.of(accountKey(email, ip), ipKey(ip))) {
                redis.execute(INCR, List.of(key), String.valueOf(cfg.window().toSeconds()));
            }
        } catch (RuntimeException e) {
            log.error("Falha ao registrar tentativa no Redis", e);
        }
    }

    void reset(String email, String ip) {
        try {
            redis.delete(accountKey(email, ip));
        } catch (RuntimeException e) {
            log.warn("Falha ao limpar contador de login", e);
        }
    }

    private void enforce(String key, int max) {
        String value = redis.opsForValue().get(key);
        if (value != null && Long.parseLong(value) >= max) {
            Long ttl = redis.getExpire(key);
            throw new RateLimitedException(ttl == null || ttl < 1 ? cfg.window().toSeconds() : ttl);
        }
    }

    private static String accountKey(String email, String ip) {
        return "rl:login:acct:" + sha256(email.toLowerCase()) + ":" + ip;
    }

    private static String ipKey(String ip) {
        return "rl:login:ip:" + ip;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
