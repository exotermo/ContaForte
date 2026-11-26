package dev.contaforte.common;

import java.time.Duration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cada hash Argon2 ocupa cerca de 16 MiB do heap enquanto é calculado, e o login é aberto a quem não tem conta. Sem
 * teto, uma rajada de tentativas em paralelo (todas passam pelo limitador antes de qualquer falha ser contada) derruba
 * o backend por falta de memória.
 */
final class BoundedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final ConcurrencyGate gate;

    BoundedPasswordEncoder(PasswordEncoder delegate, int concurrency, Duration maxWait) {
        this.delegate = delegate;
        this.gate = new ConcurrencyGate(concurrency, maxWait, "Serviço ocupado. Tente novamente em instantes.");
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return gate.run(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return gate.run(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }
}
