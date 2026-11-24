package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecipientKnowledgeTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID CTX = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static RecipientKnowledge votos(UUID categoria, int n) {
        RecipientKnowledge k = RecipientKnowledge.EMPTY;
        for (int i = 0; i < n; i++) k = k.withVote(categoria, null);
        return k;
    }

    @Test
    void semVotosNaoHaPalpite() {
        assertThat(RecipientKnowledge.EMPTY.topCategory()).isNull();
        assertThat(RecipientKnowledge.EMPTY.topContext()).isNull();
    }

    @Test
    void aConfiancaCrescePeloMetodoDaSuavizacao() {
        // k/(n+1) com todos concordando: 1→0,50  2→0,67  3→0,75  4→0,80  5→0,83  6→0,857 (aplica)  10→0,909
        double[] esperado = {0.50, 0.667, 0.75, 0.80, 0.833, 0.857, 0.875, 0.889, 0.90, 0.909};
        for (int n = 1; n <= 10; n++) {
            Confidence c = votos(A, n).topCategory().confidence();
            assertThat((double) c.num() / c.den()).as("n=" + n).isCloseTo(esperado[n - 1], org.assertj.core.data.Offset.offset(0.001));
        }
        assertThat(votos(A, 5).topCategory().confidence().decision()).isEqualTo(Decision.SUGGEST);
        assertThat(votos(A, 6).topCategory().confidence().decision()).isEqualTo(Decision.AUTO);
    }

    @Test
    void umSoCasoNuncaAplicaSozinho() {
        assertThat(votos(A, 1).topCategory().confidence().decision()).isEqualTo(Decision.SUGGEST);
    }

    @Test
    void discordanciaDerrubaAConfianca() {
        RecipientKnowledge k = votos(A, 6);
        assertThat(k.topCategory().confidence().decision()).isEqualTo(Decision.AUTO);

        k = k.withVote(B, null).withVote(B, null);   // 6 em A, 2 em B: 6/9
        assertThat(k.topCategory().id()).isEqualTo(A);
        assertThat(k.topCategory().confidence()).isEqualTo(new Confidence(6, 9));
        assertThat(k.topCategory().confidence().decision()).isEqualTo(Decision.SUGGEST);
    }

    @Test
    void empateEscolheOMenorIdSempre() {
        RecipientKnowledge ab = RecipientKnowledge.EMPTY.withVote(A, null).withVote(B, null);
        RecipientKnowledge ba = RecipientKnowledge.EMPTY.withVote(B, null).withVote(A, null);

        assertThat(ab.topCategory().id()).isEqualTo(A).isEqualTo(ba.topCategory().id());
        assertThat(ab.topCategory().confidence()).isEqualTo(new Confidence(1, 3));   // 1/(2+1): empate quase não decide nada
        assertThat(ab.topCategory().confidence().decision()).isEqualTo(Decision.NONE);
    }

    @Test
    void categoriaEContextoSaoIndependentes() {
        RecipientKnowledge k = RecipientKnowledge.EMPTY.withVote(A, CTX).withVote(B, CTX).withVote(A, CTX);

        assertThat(k.topCategory().id()).isEqualTo(A);
        assertThat(k.topCategory().confidence()).isEqualTo(new Confidence(2, 4));
        assertThat(k.topContext().id()).isEqualTo(CTX);
        assertThat(k.topContext().confidence()).isEqualTo(new Confidence(3, 4));
    }

    @Test
    void decidirSoUmaDasDimensoesSoSomaNela() {
        RecipientKnowledge soCategoria = RecipientKnowledge.EMPTY.withVote(A, null);
        RecipientKnowledge soContexto = RecipientKnowledge.EMPTY.withVote(null, CTX);

        assertThat(soCategoria.topContext()).isNull();
        assertThat(soContexto.topCategory()).isNull();
        assertThat(RecipientKnowledge.EMPTY.withVote(null, null)).isEqualTo(RecipientKnowledge.EMPTY);
    }

    @Test
    void retirarUmVotoDesfazAprendizadoENuncaPassaDeZero() {
        RecipientKnowledge k = votos(A, 2);

        assertThat(k.withoutVote(A, null).topCategory().confidence()).isEqualTo(new Confidence(1, 2));
        assertThat(k.withoutVote(A, null).withoutVote(A, null).topCategory()).isNull();
        assertThat(RecipientKnowledge.EMPTY.withoutVote(A, CTX)).isEqualTo(RecipientKnowledge.EMPTY);
        assertThat(k.withoutVote(B, null)).isEqualTo(k);   // categoria que nunca teve voto: nada muda
    }

    @Test
    void ObjetoEhImutavelEAprenderDevolveOutro() {
        RecipientKnowledge antes = votos(A, 1);
        RecipientKnowledge depois = antes.withVote(A, null);

        assertThat(antes.topCategory().confidence()).isEqualTo(new Confidence(1, 2));
        assertThat(depois.topCategory().confidence()).isEqualTo(new Confidence(2, 3));
    }
}
