package dev.contaforte.auth;

import dev.contaforte.audit.AuditService;
import dev.contaforte.common.ApiException;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** Resposta de autenticação: o refresh token só aparece aqui, em claro, uma vez. */
    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {}

    private static final String INVALID_CREDENTIALS = "Credenciais inválidas.";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final LoginRateLimiter limiter;
    private final AuditService audit;
    private volatile String dummyHash;

    AuthService(UserRepository users, PasswordEncoder encoder, TokenService accessTokens,
            RefreshTokenService refreshTokens, LoginRateLimiter limiter, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
        this.audit = audit;
    }

    public Tokens login(String email, String password, String ip) {
        limiter.check(email, ip);
        Optional<User> found = users.findByEmailIgnoreCase(email).filter(User::isEnabled);
        // sempre faz uma verificação de hash, para não revelar por tempo se o e-mail existe
        boolean ok = encoder.matches(password, found.map(User::getPasswordHash).orElseGet(this::dummy));
        if (found.isEmpty() || !ok) {
            limiter.recordFailure(email, ip);
            audit.record(null, "LOGIN_FAILURE", "email_sha256:" + LoginRateLimiter.sha256(email.toLowerCase()).substring(0, 16),
                    Map.of("ip", ip));
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        User user = found.get();
        limiter.reset(email, ip);
        audit.record(user.getId(), "LOGIN_SUCCESS", "user:" + user.getId(), Map.of("ip", ip));
        return tokensFor(user, refreshTokens.issueNewFamily(user));
    }

    public Tokens refresh(String refreshToken, String ip) {
        return switch (refreshTokens.rotate(refreshToken)) {
            case RefreshTokenService.Rotation.Ok ok -> {
                audit.record(ok.issued().user().getId(), "TOKEN_REFRESH", null, Map.of("ip", ip));
                yield tokensFor(ok.issued().user(), ok.issued().token());
            }
            case RefreshTokenService.Rotation.Reuse reuse -> {
                audit.record(reuse.userId(), "REFRESH_REUSE_DETECTED", "user:" + reuse.userId(), Map.of("ip", ip));
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token inválido.");
            }
            case RefreshTokenService.Rotation.Invalid invalid ->
                    throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token inválido.");
        };
    }

    public void logout(String refreshToken, String ip) {
        refreshTokens.revoke(refreshToken)
                .ifPresent(userId -> audit.record(userId, "LOGOUT", "user:" + userId, Map.of("ip", ip)));
    }

    private Tokens tokensFor(User user, String refreshToken) {
        return new Tokens(accessTokens.issueAccessToken(user), refreshToken, "Bearer", accessTokens.accessTtlSeconds());
    }

    private String dummy() {
        String h = dummyHash;
        if (h == null) {
            h = encoder.encode("senha-inexistente-para-igualar-o-tempo");
            dummyHash = h;
        }
        return h;
    }
}
