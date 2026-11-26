package dev.contaforte.system;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaIndexTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    /**
     * Apagar uma transação obriga o banco a procurar quem aponta para ela. Sem índice, é uma leitura da tabela inteira
     * por linha apagada. Vale um índice que comece pela primeira coluna da chave; se for parcial, só "coluna IS NOT NULL".
     */
    @Test
    void todaChaveEstrangeiraParaTransacoesTemIndice() {
        List<String> semIndice = jdbc.queryForList("""
                SELECT c.conrelid::regclass || '.' || a.attname
                  FROM pg_constraint c
                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                 WHERE c.contype = 'f' AND c.confrelid = 'transactions'::regclass
                   AND NOT EXISTS (SELECT 1 FROM pg_index i
                                    WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1]
                                      AND (i.indpred IS NULL
                                           OR pg_get_expr(i.indpred, i.indrelid) = '(' || a.attname || ' IS NOT NULL)'))
                """, String.class);
        assertThat(semIndice).isEmpty();
    }
}
