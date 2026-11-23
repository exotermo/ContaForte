package dev.contaforte.users;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.ApiTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@SuppressWarnings({"rawtypes", "unchecked"})
class AdminUserApiTest extends ApiTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger(3000);
    private static final String SENHA_NOVA = "OutraSenhaForte#2026";

    private String novoEmail() {
        return "criado-" + UUID.randomUUID() + "@teste.local";
    }

    private HttpStatus loginStatus(String email, String senha) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        int n = SEQ.incrementAndGet();
        h.set("X-Forwarded-For", "10.7." + (n / 250) + "." + (n % 250 + 1));
        return HttpStatus.valueOf(rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", senha), h), Map.class).getStatusCode().value());
    }

    private Map<String, Object> corpo(String email, String senha) {
        Map<String, Object> b = new HashMap<>();
        b.put("email", email);
        b.put("password", senha);
        return b;
    }

    @Test
    void adminCriaUsuarioQueConsegueEntrarEFicaComPapelUser() {
        Pessoa admin = pessoa(Role.ADMIN);
        String email = novoEmail();

        var r = call(HttpMethod.POST, "/api/v1/admin/users", corpo(email, SENHA_NOVA), admin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("email", email).containsEntry("role", "USER").containsEntry("enabled", true);
        assertThat(r.getBody()).doesNotContainKeys("password", "passwordHash");
        assertThat(loginStatus(email, SENHA_NOVA)).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus(email, "senha-errada-123")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void campoDePapelNoCorpoNaoEscalaPrivilegio() {
        Pessoa admin = pessoa(Role.ADMIN);
        Map<String, Object> b = corpo(novoEmail(), SENHA_NOVA);
        b.put("role", "ADMIN");

        var r = call(HttpMethod.POST, "/api/v1/admin/users", b, admin);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("role", "USER");
    }

    @Test
    void soAdminCria() {
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo(novoEmail(), SENHA_NOVA), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (var role : new Role[] {Role.USER, Role.SERVICE, Role.DEVICE}) {
            assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo(novoEmail(), SENHA_NOVA), pessoa(role)))
                    .as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void emailDuplicadoSemDiferenciarCaixaDa409() {
        Pessoa admin = pessoa(Role.ADMIN);
        String email = novoEmail();
        call(HttpMethod.POST, "/api/v1/admin/users", corpo(email, SENHA_NOVA), admin);

        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo(email.toUpperCase(), SENHA_NOVA), admin)).isEqualTo(HttpStatus.CONFLICT);
        // e-mail com espaços nas pontas nem é um e-mail válido: a validação recusa antes da regra de duplicidade
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo("  " + email + "  ", SENHA_NOVA), admin)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validaEmailESenha() {
        Pessoa admin = pessoa(Role.ADMIN);
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo("nao-eh-email", SENHA_NOVA), admin)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo("", SENHA_NOVA), admin)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo(novoEmail(), "curta-11-ch"), admin)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo(novoEmail(), "x".repeat(129)), admin)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", Map.of("email", novoEmail()), admin)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.POST, "/api/v1/admin/users", corpo(novoEmail(), "x".repeat(12)), admin)).isEqualTo(HttpStatus.CREATED);   // 12 é o mínimo
    }

    @Test
    void auditoriaNaoGuardaEmailNemSenha() {
        Pessoa admin = pessoa(Role.ADMIN);
        String email = novoEmail();
        var r = call(HttpMethod.POST, "/api/v1/admin/users", corpo(email, SENHA_NOVA), admin);

        List<Map<String, Object>> linhas = jdbc.queryForList(
                "select action, resource, detail::text as detail from audit_logs where actor_id = ? and action = 'USER_CREATED_BY_ADMIN'", admin.id());
        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0).get("resource")).isEqualTo("user:" + r.getBody().get("id"));
        assertThat((String) linhas.get(0).get("detail")).doesNotContain(email, SENHA_NOVA, "@");
    }

    @Test
    void usuarioCriadoComecaSemDadosFinanceiros() {
        Pessoa admin = pessoa(Role.ADMIN);
        String email = novoEmail();
        call(HttpMethod.POST, "/api/v1/admin/users", corpo(email, SENHA_NOVA), admin);

        Pessoa nova = entrar(email, SENHA_NOVA);

        assertThat(getList("/api/v1/accounts", nova)).isEmpty();
        assertThat(call(HttpMethod.GET, "/api/v1/summary", null, nova).getBody()).containsEntry("totalNetWorth", "0.00");
    }

    @Test
    void openApiDocumentaACriacao() {
        Map paths = (Map) rest.getForEntity("/api/v1/openapi", Map.class).getBody().get("paths");
        assertThat(((Map) paths.get("/api/v1/admin/users"))).containsKey("post");
    }
}
