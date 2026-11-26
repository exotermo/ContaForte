package dev.contaforte.common;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class CommonConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Argon2id com os parâmetros recomendados pelo Spring Security (formato autodescritivo), no máximo 2 por vez. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BoundedPasswordEncoder(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), 2, Duration.ofSeconds(10));
    }
}
