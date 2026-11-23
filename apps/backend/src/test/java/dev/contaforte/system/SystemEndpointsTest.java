package dev.contaforte.system;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                // em @SpringBootTest a exportação de métricas vem desligada por padrão
                "management.prometheus.metrics.export.enabled=true"
        })
@Import(PostgresTestConfig.class)
class SystemEndpointsTest {

    @Autowired TestRestTemplate rest;
    @LocalManagementPort int managementPort;

    @Test
    void infoDaApiResponde() {
        var resposta = rest.getForEntity("/api/v1/system/info", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).contains("contaforte-backend");
    }

    @Test
    void healthDeGestaoEstaUpComBanco() {
        var resposta = rest.getForEntity("http://localhost:" + managementPort + "/actuator/health", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void metricasPrometheusEstaoExpostasSoNaPortaDeGestao() {
        var gestao = rest.getForEntity("http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertThat(gestao.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gestao.getBody()).contains("jvm_memory_used_bytes");

        var publica = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(publica.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
