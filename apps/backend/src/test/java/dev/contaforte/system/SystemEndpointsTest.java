package dev.contaforte.system;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;

class SystemEndpointsTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @LocalManagementPort int managementPort;

    @Test
    void infoDaApiEhPublica() {
        var resposta = rest.getForEntity("/api/v1/system/info", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).contains("contaforte-backend");
    }

    @Test
    void healthDeGestaoEstaUpComBancoERedis() {
        var resposta = rest.getForEntity("http://localhost:" + managementPort + "/actuator/health", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void metricasPrometheusEstaoExpostasSoNaPortaDeGestao() {
        var gestao = rest.getForEntity("http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertThat(gestao.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gestao.getBody()).contains("jvm_memory_used_bytes");

        // na porta pública a rota não existe como actuator: cai na regra "autenticado" → 401
        var publica = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(publica.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
    }

    @Test
    void especificacaoOpenApiEhPublica() {
        var resposta = rest.getForEntity("/api/v1/openapi", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).contains("/api/v1/auth/login");
    }
}
