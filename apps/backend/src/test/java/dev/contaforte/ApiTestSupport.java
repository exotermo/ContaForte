package dev.contaforte;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.users.Role;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Base dos testes de API autenticada: cria pessoas reais, faz o login de verdade e chama os endpoints com o
 * Bearer token. Cada pessoa usa um IP de origem próprio para não esbarrar no rate limit do login.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class ApiTestSupport extends AbstractIntegrationTest {

    protected static final String SENHA = "SenhaDoUsuario#2026";
    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    @Autowired protected TestRestTemplate rest;
    @Autowired protected UserRepository users;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected JdbcTemplate jdbc;

    /** Uma pessoa de teste: id e token de acesso obtidos pelo login real. */
    protected record Pessoa(UUID id, String token) {}

    protected Pessoa pessoa(Role role) {
        String email = "u" + UUID.randomUUID() + "@teste.local";
        User u = users.save(new User(email, encoder.encode(SENHA), role));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        int n = SEQ.incrementAndGet();
        h.set("X-Forwarded-For", "10.8." + (n / 250) + "." + (n % 250 + 1));
        ResponseEntity<Map> r = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", SENHA), h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Pessoa(u.getId(), (String) r.getBody().get("accessToken"));
    }

    protected Pessoa pessoa() {
        return pessoa(Role.USER);
    }

    /** Chamada cuja resposta é um objeto JSON (inclui os ProblemDetail de erro). */
    protected ResponseEntity<Map> call(HttpMethod m, String path, Object body, Pessoa p) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (p != null) h.setBearerAuth(p.token());
        return rest.exchange(path, m, new HttpEntity<>(body, h), Map.class);
    }

    /** GET cuja resposta é uma lista JSON. */
    protected List<Map> getList(String path, Pessoa p) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(p.token());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
    }
}
