package dev.contaforte.auth;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/** API stateless com Bearer JWT. Tudo exige autenticação, salvo o que está explicitamente liberado. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)                 // sem cookies de sessão: CSRF não se aplica
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll() // porta de gestão: só rede interna
                    .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                            "/api/v1/system/info", "/api/v1/openapi/**").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    // dados financeiros pessoais: só pessoas (USER/ADMIN); SERVICE e DEVICE não têm acesso
                    .requestMatchers("/api/v1/accounts/**", "/api/v1/categories/**", "/api/v1/contexts/**",
                            "/api/v1/transactions/**", "/api/v1/summary/**").hasAnyRole("USER", "ADMIN")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(roleConverter())))
            .headers(h -> h.cacheControl(Customizer.withDefaults()));
        return http.build();
    }

    /** Converte a claim "role" em ROLE_&lt;papel&gt; para o RBAC do Spring. */
    private Converter<Jwt, AbstractAuthenticationToken> roleConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
