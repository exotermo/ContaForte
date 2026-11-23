package dev.contaforte.auth;

import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Emite, rotaciona e revoga refresh tokens opacos (256 bits, guardados só como hash). */
@Service
class RefreshTokenService {

    /** Token em claro (devolvido uma única vez ao cliente) + o usuário dono. */
    record Issued(String token, User user) {}

    /** Resultado de uma tentativa de rotação. */
    sealed interface Rotation {
        record Ok(Issued issued) implements Rotation {}
        record Reuse(UUID userId) implements Rotation {}   // token já usado/revogado: família revogada
        record Invalid() implements Rotation {}
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final AppProperties props;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository tokens, UserRepository users, AppProperties props, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    String issueNewFamily(User user) {
        return issue(user.getId(), UUID.randomUUID());
    }

    @Transactional
    Rotation rotate(String raw) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = tokens.findByTokenHash(LoginRateLimiter.sha256(raw));
        if (found.isEmpty()) {
            return new Rotation.Invalid();
        }
        RefreshToken current = found.get();
        if (current.getRevokedAt() != null || tokens.revokeIfActive(current.getId(), now) == 0) {
            // reuso de um token já rotacionado: possível roubo → derruba toda a cadeia
            tokens.revokeFamily(current.getFamilyId(), now);
            return new Rotation.Reuse(current.getUserId());
        }
        if (!current.getExpiresAt().isAfter(now)) {
            return new Rotation.Invalid();
        }
        Optional<User> user = users.findById(current.getUserId()).filter(User::isEnabled);
        if (user.isEmpty()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            return new Rotation.Invalid();
        }
        return new Rotation.Ok(new Issued(issue(current.getUserId(), current.getFamilyId()), user.get()));
    }

    /** Revoga a cadeia do token informado (logout). Idempotente e silencioso para tokens desconhecidos. */
    @Transactional
    Optional<UUID> revoke(String raw) {
        return tokens.findByTokenHash(LoginRateLimiter.sha256(raw)).map(t -> {
            tokens.revokeFamily(t.getFamilyId(), clock.instant());
            return t.getUserId();
        });
    }

    @Transactional
    void revokeAllOf(UUID userId) {
        tokens.revokeAllOfUser(userId, clock.instant());
    }

    private String issue(UUID userId, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.save(new RefreshToken(userId, familyId, LoginRateLimiter.sha256(raw), now,
                now.plus(props.jwt().refreshTtl())));
        return raw;
    }
}
