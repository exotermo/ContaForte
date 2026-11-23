package dev.contaforte.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.AbstractIntegrationTest;
import dev.contaforte.users.Role;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

@SuppressWarnings({"rawtypes", "unchecked"})
class AuthFlowTest extends AbstractIntegrationTest {

    private static final String SENHA = "SenhaDoUsuario#2026";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;

    // cada teste usa um IP de origem próprio (via X-Forwarded-For) para isolar o rate limit
    private String ip() {
        return "10.0." + (SEQ.incrementAndGet() / 250) + "." + (SEQ.get() % 250 + 1);
    }

    private String novoUsuario() {
        String email = "u" + UUID.randomUUID() + "@teste.local";
        users.save(new User(email, encoder.encode(SENHA), Role.USER));
        return email;
    }

    private ResponseEntity<Map> send(HttpMethod method, String path, Object body, String token, String ip) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        if (ip != null) h.set("X-Forwarded-For", ip);
        return rest.exchange(path, method, new HttpEntity<>(body, h), Map.class);
    }

    /** Só o status HTTP: serve para respostas que não são objetos JSON (ex.: listas). */
    private HttpStatusCode statusOf(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode();
    }

    private ResponseEntity<Map> login(String email, String senha, String ip) {
        return send(HttpMethod.POST, "/api/v1/auth/login", Map.of("email", email, "password", senha), null, ip);
    }

    private ResponseEntity<Map> refresh(String token, String ip) {
        return send(HttpMethod.POST, "/api/v1/auth/refresh", Map.of("refreshToken", token), null, ip);
    }

    private Map loginOk(String email, String ip) {
        var r = login(email, SENHA, ip);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void adminInicialFazLoginEConsultaOProprioPerfil() {
        var r = login(ADMIN_EMAIL, ADMIN_PASSWORD, ip());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKeys("accessToken", "refreshToken").containsEntry("tokenType", "Bearer");
        var me = send(HttpMethod.GET, "/api/v1/users/me", null, (String) r.getBody().get("accessToken"), null);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("role", "ADMIN").containsEntry("email", ADMIN_EMAIL);
        assertThat(me.getBody()).doesNotContainKey("passwordHash");
    }

    @Test
    void credenciaisInvalidasTemRespostaIdenticaParaEmailExistenteEInexistente() {
        String email = novoUsuario();
        var senhaErrada = login(email, "senha-errada", ip());
        var emailInexistente = login("nao-existe@teste.local", "senha-errada", ip());

        assertThat(senhaErrada.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(emailInexistente.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(senhaErrada.getBody().get("detail")).isEqualTo(emailInexistente.getBody().get("detail"));
    }

    @Test
    void semTokenOuComTokenInvalidoEhNaoAutorizado() {
        assertThat(send(HttpMethod.GET, "/api/v1/users/me", null, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, "/api/v1/users/me", null, "isto.nao.eh-um-jwt", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenExpiradoEhRejeitado() {
        String email = novoUsuario();
        User u = users.findByEmailIgnoreCase(email).orElseThrow();
        Instant passado = Instant.now().minusSeconds(600);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("contaforte").subject(u.getId().toString())
                .issuedAt(passado.minusSeconds(900)).expiresAt(passado).claim("role", "USER").build();
        String expirado = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

        assertThat(send(HttpMethod.GET, "/api/v1/users/me", null, expirado, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenDeOutroEmissorEhRejeitado() {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("outro-emissor").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("role", "ADMIN").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

        assertThat(statusOf("/api/v1/admin/users", token)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void papelInsuficienteRecebe403EAdminRecebe200() {
        String token = (String) loginOk(novoUsuario(), ip()).get("accessToken");
        assertThat(statusOf("/api/v1/admin/users", token)).isEqualTo(HttpStatus.FORBIDDEN);

        String adminToken = (String) login(ADMIN_EMAIL, ADMIN_PASSWORD, ip()).getBody().get("accessToken");
        assertThat(statusOf("/api/v1/admin/users", adminToken)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refreshRotacionaEReusoDoTokenAntigoDerrubaAFamilia() {
        String email = novoUsuario();
        String ip = ip();
        String r1 = (String) loginOk(email, ip).get("refreshToken");

        var segundo = refresh(r1, ip);
        assertThat(segundo.getStatusCode()).isEqualTo(HttpStatus.OK);
        String r2 = (String) segundo.getBody().get("refreshToken");
        assertThat(r2).isNotEqualTo(r1);

        // reapresentar o token já rotacionado = possível roubo
        assertThat(refresh(r1, ip).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // e a cadeia inteira foi revogada, inclusive o token mais novo
        assertThat(refresh(r2, ip).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'REFRESH_REUSE_DETECTED'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refreshTokenDesconhecidoEhRejeitado() {
        assertThat(refresh("token-que-nunca-existiu", ip()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevogaORefreshToken() {
        String ip = ip();
        String refresh = (String) loginOk(novoUsuario(), ip).get("refreshToken");

        var out = send(HttpMethod.POST, "/api/v1/auth/logout", Map.of("refreshToken", refresh), null, ip);

        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(refresh, ip).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rateLimitBloqueiaApos5FalhasMesmoComSenhaCorreta() {
        String email = novoUsuario();
        String ip = ip();
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "errada-" + i, ip).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        var bloqueado = login(email, SENHA, ip);

        assertThat(bloqueado.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(bloqueado.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void loginComSucessoZeraOContadorDeFalhas() {
        String email = novoUsuario();
        String ip = ip();
        for (int i = 0; i < 4; i++) login(email, "errada", ip);
        assertThat(login(email, SENHA, ip).getStatusCode()).isEqualTo(HttpStatus.OK);

        for (int i = 0; i < 4; i++) {
            assertThat(login(email, "errada", ip).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void trocaDeSenhaExigeSenhaAtualEEncerraAsSessoes() {
        String email = novoUsuario();
        String ip = ip();
        Map tokens = loginOk(email, ip);
        String access = (String) tokens.get("accessToken");
        String refresh = (String) tokens.get("refreshToken");

        var errada = send(HttpMethod.PATCH, "/api/v1/users/me",
                Map.of("currentPassword", "nao-eh-esta", "newPassword", "NovaSenhaForte#2027"), access, null);
        assertThat(errada.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var fraca = send(HttpMethod.PATCH, "/api/v1/users/me",
                Map.of("currentPassword", SENHA, "newPassword", "curta"), access, null);
        assertThat(fraca.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var ok = send(HttpMethod.PATCH, "/api/v1/users/me",
                Map.of("currentPassword", SENHA, "newPassword", "NovaSenhaForte#2027"), access, null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(refresh(refresh, ip).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, SENHA, ip()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, "NovaSenhaForte#2027", ip()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void emailJaEmUsoRetorna409() {
        String outro = novoUsuario();
        String access = (String) loginOk(novoUsuario(), ip()).get("accessToken");

        var r = send(HttpMethod.PATCH, "/api/v1/users/me", Map.of("email", outro.toUpperCase()), access, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void auditoriaNaoGuardaEmailNemSenhaEmClaro() {
        String email = "auditoria-" + UUID.randomUUID() + "@teste.local";
        login(email, "SenhaSecretaQueNaoDeveAparecer", ip());

        String linhas = jdbc.queryForObject(
                "select coalesce(string_agg(resource || ' ' || detail::text, ' '), '') from audit_logs "
                        + "where action = 'LOGIN_FAILURE'", String.class);
        assertThat(linhas).doesNotContain(email).doesNotContain("SenhaSecretaQueNaoDeveAparecer");
        assertThat(linhas).contains("email_sha256:");
    }
}
