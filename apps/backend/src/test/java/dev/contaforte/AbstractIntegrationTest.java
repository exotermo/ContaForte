package dev.contaforte;

import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de integração: um único contexto Spring (e um único Postgres+Redis) para a suíte toda.
 * A chave JWT é gerada na hora, nunca versionada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        // em @SpringBootTest a exportação de métricas vem desligada por padrão
        "management.prometheus.metrics.export.enabled=true"
})
@Import(TestContainersConfig.class)
public abstract class AbstractIntegrationTest {

    public static final String ADMIN_EMAIL = "admin@teste.local";
    public static final String ADMIN_PASSWORD = "SenhaAdminTeste#2026";

    private static final String JWT_KEY = generateKey();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("contaforte.jwt.private-key-b64", () -> JWT_KEY);
        registry.add("contaforte.admin.email", () -> ADMIN_EMAIL);
        registry.add("contaforte.admin.password", () -> ADMIN_PASSWORD);
    }

    private static String generateKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return Base64.getEncoder().encodeToString(gen.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
