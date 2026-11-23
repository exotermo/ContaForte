package dev.contaforte.auth;

import dev.contaforte.users.PasswordChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Ao trocar a senha, todas as sessões (refresh tokens) do usuário são encerradas. */
@Component
class SessionRevocationListener {

    private final RefreshTokenService refreshTokens;

    SessionRevocationListener(RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @EventListener
    void on(PasswordChanged event) {
        refreshTokens.revokeAllOf(event.userId());
    }
}
