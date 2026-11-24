package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.AbstractIntegrationTest;
import dev.contaforte.imports.parse.TextNorm;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DefaultRulesTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void todoCodigoCitadoExisteNoCatalogoPadraoDoBanco() {
        Set<String> doBanco = new HashSet<>(jdbc.queryForList("select code from transaction_categories where user_id is null", String.class));

        assertThat(DefaultRules.categoryCodes()).isNotEmpty().isSubsetOf(doBanco);
    }

    @Test
    void carregaComIdsDoCatalogoEPadroesNormalizadosSemRepeticao() {
        List<Rule> rules = DefaultRules.load(code -> UUID.nameUUIDFromBytes(code.getBytes()));

        assertThat(rules.size()).isBetween(30, 60);
        assertThat(rules).allSatisfy(r -> {
            assertThat(r.source()).isEqualTo(Source.DEFAULT_RULE);
            assertThat(r.pattern()).isEqualTo(TextNorm.key(r.pattern())).hasSizeGreaterThanOrEqualTo(3);
            assertThat(r.categoryId()).isNotNull();
            assertThat(r.contextId()).isNull();
            assertThat(r.type()).isNotNull();
        });
        assertThat(rules.stream().map(r -> r.match() + r.pattern() + r.type()).distinct().count()).isEqualTo(rules.size());
        assertThat(rules.stream().map(Rule::id).distinct().count()).isEqualTo(rules.size());
    }

    @Test
    void ordemEIdsSaoEstaveis() {
        assertThat(DefaultRules.load(c -> UUID.nameUUIDFromBytes(c.getBytes()))).isEqualTo(DefaultRules.load(c -> UUID.nameUUIDFromBytes(c.getBytes())));
    }

    @Test
    void categoriaInexistenteEhErroNaPartida() {
        assertThatThrownBy(() -> DefaultRules.load(code -> null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("inexistente");
    }
}
