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
        users.save(new User(email, encoder.encode(SENHA), role));
        return entrar(email, SENHA);
    }

    /** Faz o login de verdade (com IP próprio) e devolve a pessoa com o token de acesso. */
    protected Pessoa entrar(String email, String senha) {
        User u = users.findByEmailIgnoreCase(email).orElseThrow();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        int n = SEQ.incrementAndGet();
        h.set("X-Forwarded-For", "10.8." + (n / 250) + "." + (n % 250 + 1));
        ResponseEntity<Map> r = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", senha), h), Map.class);
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

    // ---------------------------------------------------------------- atalhos de domínio (contas, lançamentos, padrões)

    /** Cria uma conta pela API e devolve o id. */
    protected String conta(Pessoa p, String nome, String tipo, String saldo) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", nome, "type", tipo));
        if (saldo != null) body.put("openingBalance", saldo);
        var r = call(HttpMethod.POST, "/api/v1/accounts", body, p);
        assertThat(r.getStatusCode()).as("conta %s: %s", nome, r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    protected String saldo(Pessoa p, String contaId) {
        return (String) call(HttpMethod.GET, "/api/v1/accounts/" + contaId, null, p).getBody().get("balance");
    }

    /** Corpo mínimo de um lançamento. */
    protected Map<String, Object> corpo(String tipo, String conta, Object valor) {
        Map<String, Object> b = new java.util.HashMap<>();
        b.put("type", tipo);
        b.put("accountId", conta);
        b.put("amount", valor);
        return b;
    }

    protected ResponseEntity<Map> lancar(Pessoa p, Map<String, Object> corpo) {
        return call(HttpMethod.POST, "/api/v1/transactions", corpo, p);
    }

    /** Lança e exige 201. */
    protected Map criada(Pessoa p, Map<String, Object> corpo) {
        var r = lancar(p, corpo);
        assertThat(r.getStatusCode()).as("lançar %s: %s", corpo, r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    protected HttpStatus status(HttpMethod m, String path, Object body, Pessoa p) {
        return HttpStatus.valueOf(call(m, path, body, p).getStatusCode().value());
    }

    /** Id de uma categoria padrão (de qualquer nível) pelo código estável. */
    protected String categoriaPadrao(Pessoa p, String codigo) {
        for (Map c : getList("/api/v1/categories", p)) {
            if (codigo.equals(c.get("code"))) return (String) c.get("id");
            for (Map f : (List<Map>) c.get("children")) if (codigo.equals(f.get("code"))) return (String) f.get("id");
        }
        throw new AssertionError("categoria padrão não encontrada: " + codigo);
    }

    protected String contextoPadrao(Pessoa p, String codigo) {
        return getList("/api/v1/contexts", p).stream().filter(c -> codigo.equals(c.get("code")))
                .findFirst().orElseThrow().get("id").toString();
    }
}
