package dev.contaforte.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuração do domínio de autenticação (prefixo {@code contaforte}). */
@ConfigurationProperties("contaforte")
public record AppProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Admin admin,
        @DefaultValue RateLimit rateLimit) {

    /** Chave RSA privada em PKCS#8/DER codificada em Base64 (vem de segredo, nunca do código). */
    public record Jwt(
            String privateKeyB64,
            @DefaultValue("contaforte") String issuer,
            @DefaultValue("15m") Duration accessTtl,
            @DefaultValue("14d") Duration refreshTtl) {}

    public record Admin(String email, String password) {}

    public record RateLimit(
            @DefaultValue("5") int maxFailuresPerAccount,
            @DefaultValue("30") int maxFailuresPerIp,
            @DefaultValue("15m") Duration window) {}
}
