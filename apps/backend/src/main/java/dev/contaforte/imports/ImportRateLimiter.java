package dev.contaforte.imports;

import dev.contaforte.common.ApiException;
import dev.contaforte.common.RateLimitedException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Limita quantas prévias (e leituras de CSV para sugestão) cada usuário pode pedir por hora: ler um arquivo é trabalho
 * de CPU. O contador conta TENTATIVAS (inclusive prévias depois descartadas), então criar e descartar em ciclo não
 * contorna o limite. Falha fechada, como no login: sem Redis, o envio é recusado (503).
 */
@Component
class ImportRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImportRateLimiter.class);
    private static final long WINDOW_SECONDS = 3600;

    private static final DefaultRedisScript<List> INCR = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return {c, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final int previewsPerHour;
    private final int suggestionsPerHour;

    ImportRateLimiter(StringRedisTemplate redis,
                      @Value("${contaforte.imports.previews-per-hour:20}") int previewsPerHour,
                      @Value("${contaforte.imports.suggestions-per-hour:60}") int suggestionsPerHour) {
        this.redis = redis;
        this.previewsPerHour = previewsPerHour;
        this.suggestionsPerHour = suggestionsPerHour;
    }

    void checkPreview(UUID userId) {
        check("preview", userId, previewsPerHour);
    }

    void checkSuggestion(UUID userId) {
        check("suggest", userId, suggestionsPerHour);
    }

    private void check(String bucket, UUID userId, int max) {
        try {
            List<?> r = redis.execute(INCR, List.of("rl:import:" + bucket + ":" + userId), String.valueOf(WINDOW_SECONDS));
            long count = ((Number) r.get(0)).longValue();
            long ttl = ((Number) r.get(1)).longValue();
            if (count > max) throw new RateLimitedException(Math.max(1, ttl));
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Redis indisponível para o limite de importações", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Serviço temporariamente indisponível.");
        }
    }
}
