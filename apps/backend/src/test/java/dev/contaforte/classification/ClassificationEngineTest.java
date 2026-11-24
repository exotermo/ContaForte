package dev.contaforte.classification;

import static dev.contaforte.classification.ClassificationEngine.classify;
import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.classification.ClassificationEngine.Subject;
import dev.contaforte.transactions.TransactionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationEngineTest {

    static UUID id(String nome) {
        return UUID.nameUUIDFromBytes(nome.getBytes());
    }

    static final UUID MERCADO = id("FOOD_GROCERIES");
    static final UUID RESTAURANTE = id("FOOD_RESTAURANT");
    static final UUID COMBUSTIVEL = id("TRANSPORT_FUEL");
    static final UUID SAUDE = id("HEALTH_APPOINTMENT");
    static final UUID ENTREGA = id("FOOD_DELIVERY");
    static final UUID CORRIDA = id("TRANSPORT_RIDE");
    static final UUID PET = id("ctx:PET");
    static final UUID FAMILIA = id("ctx:FAMILY");

    static Rule user(MatchType m, String padrao, TransactionType tipo, UUID cat, UUID ctx, int prioridade, long criadaEm) {
        return new Rule(id("u:" + m + padrao + tipo + prioridade + criadaEm), m, padrao, tipo, cat, ctx, prioridade, Instant.ofEpochSecond(criadaEm), Source.RULE);
    }

    static Rule padrao(String padrao, UUID cat) {
        return new Rule(id("d:" + padrao), MatchType.CONTAINS, padrao, TransactionType.EXPENSE, cat, null, 0, Instant.EPOCH, Source.DEFAULT_RULE);
    }

    static Subject gasto(String descricao) {
        return new Subject(descricao, null, TransactionType.EXPENSE);
    }

    static RecipientKnowledge votos(UUID cat, UUID ctx, int n) {
        RecipientKnowledge k = RecipientKnowledge.EMPTY;
        for (int i = 0; i < n; i++) k = k.withVote(cat, ctx);
        return k;
    }

    static final List<Rule> PADRAO = List.of(padrao("posto", COMBUSTIVEL), padrao("posto de saude", SAUDE), padrao("uber", CORRIDA), padrao("uber eats", ENTREGA));

    // ---------------------------------------------------------------- estágios e precedência

    @Test
    void semNenhumEstagioNaoSugereNada() {
        Result r = classify(gasto("COMPRA LOJA QUALQUER"), List.of(), PADRAO, RecipientKnowledge.EMPTY);

        assertThat(r).isEqualTo(Result.NONE);
        assertThat(r.summary().decision()).isEqualTo(Decision.NONE);
    }

    @Test
    void regraPadraoAplicaComConfianca090() {
        Result r = classify(gasto("COMPRA POSTO ABC"), List.of(), PADRAO, RecipientKnowledge.EMPTY);

        assertThat(r.category().id()).isEqualTo(COMBUSTIVEL);
        assertThat(r.category().source()).isEqualTo(Source.DEFAULT_RULE);
        assertThat(r.category().confidence()).isEqualTo(new Confidence(9, 10));
        assertThat(r.category().decision()).isEqualTo(Decision.AUTO);
        assertThat(r.context().decided()).isFalse();
    }

    @Test
    void regraDoUsuarioVenceRegraPadrao() {
        Rule minha = user(MatchType.CONTAINS, "posto", TransactionType.EXPENSE, MERCADO, null, 100, 1);

        Result r = classify(gasto("COMPRA POSTO ABC"), List.of(minha), PADRAO, RecipientKnowledge.EMPTY);

        assertThat(r.category().id()).isEqualTo(MERCADO);
        assertThat(r.category().source()).isEqualTo(Source.RULE);
        assertThat(r.category().confidence()).isEqualTo(Confidence.ONE);
    }

    @Test
    void regraDoUsuarioVenceFavorecidoConhecido() {
        Rule minha = user(MatchType.CONTAINS, "joana exemplo", TransactionType.EXPENSE, RESTAURANTE, null, 100, 1);

        Result r = classify(gasto("PIX ENVIADO JOANA EXEMPLO"), List.of(minha), PADRAO, votos(MERCADO, null, 10));

        assertThat(r.category().id()).isEqualTo(RESTAURANTE);
        assertThat(r.category().source()).isEqualTo(Source.RULE);
    }

    @Test
    void favorecidoConhecidoVenceRegraPadrao() {
        Result r = classify(gasto("PIX POSTO ABC"), List.of(), PADRAO, votos(MERCADO, null, 6));

        assertThat(r.category().id()).isEqualTo(MERCADO);
        assertThat(r.category().source()).isEqualTo(Source.RECIPIENT);
    }

    @Test
    void favorecidoComConfiancaBaixaNaoDecideEDeixaPassarARegraPadrao() {
        // 3 votos em A e 3 em B: 3/7 = 0,43 → abaixo de 0,50, o estágio não decide
        RecipientKnowledge k = votos(MERCADO, null, 3);
        for (int i = 0; i < 3; i++) k = k.withVote(RESTAURANTE, null);

        Result r = classify(gasto("PIX POSTO ABC"), List.of(), PADRAO, k);

        assertThat(r.category().id()).isEqualTo(COMBUSTIVEL);
        assertThat(r.category().source()).isEqualTo(Source.DEFAULT_RULE);
    }

    @Test
    void regraMaisEspecificaDoSistemaVence() {
        assertThat(classify(gasto("UBER EATS PEDIDO"), List.of(), PADRAO, RecipientKnowledge.EMPTY).category().id()).isEqualTo(ENTREGA);
        assertThat(classify(gasto("UBER TRIP"), List.of(), PADRAO, RecipientKnowledge.EMPTY).category().id()).isEqualTo(CORRIDA);
        assertThat(classify(gasto("POSTO DE SAUDE CENTRAL"), List.of(), PADRAO, RecipientKnowledge.EMPTY).category().id()).isEqualTo(SAUDE);
    }

    // ---------------------------------------------------------------- limiares do favorecido

    @Test
    void favorecidoSoSugereAteSeisConfirmacoes() {
        for (int n = 1; n <= 5; n++) {
            Result r = classify(gasto("PIX ENVIADO ANA"), List.of(), List.of(), votos(MERCADO, null, n));
            assertThat(r.category().decision()).as("n=" + n).isEqualTo(Decision.SUGGEST);
            assertThat(r.summary().decision()).isEqualTo(Decision.SUGGEST);
        }
        Result seis = classify(gasto("PIX ENVIADO ANA"), List.of(), List.of(), votos(MERCADO, null, 6));
        assertThat(seis.category().decision()).isEqualTo(Decision.AUTO);
        assertThat(seis.category().source()).isEqualTo(Source.RECIPIENT);
    }

    // ---------------------------------------------------------------- categoria e contexto são independentes

    @Test
    void categoriaPeloFavorecidoEContextoPelaRegra() {
        Rule contexto = user(MatchType.CONTAINS, "pet", TransactionType.EXPENSE, null, PET, 100, 1);

        Result r = classify(gasto("PIX ENVIADO PET SHOP AUMIGOS"), List.of(contexto), List.of(), votos(MERCADO, null, 6));

        assertThat(r.category().id()).isEqualTo(MERCADO);
        assertThat(r.category().source()).isEqualTo(Source.RECIPIENT);
        assertThat(r.context().id()).isEqualTo(PET);
        assertThat(r.context().source()).isEqualTo(Source.RULE);
        assertThat(r.summary().decision()).isEqualTo(Decision.AUTO);
        assertThat(r.summary().confidence()).isEqualTo(new Confidence(6, 7));   // o elo mais fraco
        assertThat(r.summary().source()).isEqualTo(Source.RECIPIENT);
    }

    @Test
    void umaParteAutomaticaEOutraSugeridaViraSugestaoDoTodo() {
        RecipientKnowledge k = votos(MERCADO, FAMILIA, 6).withVote(null, PET);   // categoria 6/7, contexto 6/8 = 0,75

        Result r = classify(gasto("PIX ENVIADO ANA"), List.of(), List.of(), k);

        assertThat(r.category().decision()).isEqualTo(Decision.AUTO);
        assertThat(r.context().decision()).isEqualTo(Decision.SUGGEST);
        assertThat(r.summary().decision()).isEqualTo(Decision.SUGGEST);
        assertThat(r.summary().confidence()).isEqualTo(new Confidence(6, 8));
    }

    @Test
    void regraSoDeContextoNaoDecideACategoria() {
        Rule contexto = user(MatchType.CONTAINS, "racao", TransactionType.EXPENSE, null, PET, 100, 1);

        Result r = classify(gasto("COMPRA RACAO PREMIUM"), List.of(contexto), List.of(padrao("compra", MERCADO)), RecipientKnowledge.EMPTY);

        assertThat(r.context().id()).isEqualTo(PET);
        assertThat(r.category().id()).isEqualTo(MERCADO);                       // a categoria cai para a regra padrão
        assertThat(r.category().source()).isEqualTo(Source.DEFAULT_RULE);
    }

    // ---------------------------------------------------------------- regras do usuário: ordem e desempate

    @Test
    void menorPrioridadeVence() {
        Rule a = user(MatchType.CONTAINS, "mercado", TransactionType.EXPENSE, MERCADO, null, 50, 100);
        Rule b = user(MatchType.CONTAINS, "mercado", TransactionType.EXPENSE, RESTAURANTE, null, 10, 200);

        assertThat(classify(gasto("MERCADO CENTRAL"), List.of(a, b), List.of(), RecipientKnowledge.EMPTY).category().id()).isEqualTo(RESTAURANTE);
    }

    @Test
    void empateDePrioridadeVaiParaAMaisAntiga() {
        Rule antiga = user(MatchType.CONTAINS, "mercado", TransactionType.EXPENSE, MERCADO, null, 100, 100);
        Rule nova = user(MatchType.STARTS_WITH, "mercado", TransactionType.EXPENSE, RESTAURANTE, null, 100, 200);

        assertThat(classify(gasto("MERCADO CENTRAL"), List.of(nova, antiga), List.of(), RecipientKnowledge.EMPTY).category().id()).isEqualTo(MERCADO);
    }

    @Test
    void aOrdemDeChegadaDasRegrasNaoMudaOResultado() {
        List<Rule> regras = new ArrayList<>();
        Random rnd = new Random(7);
        for (int i = 0; i < 12; i++) {
            regras.add(new Rule(id("regra" + i), MatchType.CONTAINS, "mercado", TransactionType.EXPENSE, id("cat" + i), id("ctx" + i), rnd.nextInt(3), Instant.ofEpochSecond(rnd.nextInt(3)), Source.RULE));   // ids únicos, como no banco
        }
        Result referencia = classify(gasto("COMPRA MERCADO CENTRAL"), regras, PADRAO, RecipientKnowledge.EMPTY);

        for (int i = 0; i < 50; i++) {
            Collections.shuffle(regras, rnd);
            assertThat(classify(gasto("COMPRA MERCADO CENTRAL"), regras, PADRAO, RecipientKnowledge.EMPTY)).isEqualTo(referencia);
        }
    }

    // ---------------------------------------------------------------- comparação por palavras inteiras

    @Test
    void padraoCasaPalavraInteiraENaoPedaco() {
        List<Rule> regras = List.of(padrao("posto", COMBUSTIVEL));

        assertThat(classify(gasto("COMPOSTO QUIMICO"), List.of(), regras, RecipientKnowledge.EMPTY).category().decided()).isFalse();
        assertThat(classify(gasto("POSTOS REUNIDOS"), List.of(), regras, RecipientKnowledge.EMPTY).category().decided()).isFalse();
        assertThat(classify(gasto("posto"), List.of(), regras, RecipientKnowledge.EMPTY).category().decided()).isTrue();
        assertThat(classify(gasto("AUTO POSTO"), List.of(), regras, RecipientKnowledge.EMPTY).category().decided()).isTrue();
        assertThat(classify(gasto("COMPRA POSTO ABC"), List.of(), regras, RecipientKnowledge.EMPTY).category().decided()).isTrue();
    }

    @Test
    void tiposDeComparacao() {
        Rule contem = user(MatchType.CONTAINS, "farmacia popular", null, MERCADO, null, 1, 1);
        Rule comeca = user(MatchType.STARTS_WITH, "academia", null, RESTAURANTE, null, 1, 1);
        Rule igual = user(MatchType.EQUALS, "salario", null, COMBUSTIVEL, null, 1, 1);

        assertThat(match(contem, "COMPRA FARMACIA POPULAR CENTRO")).isTrue();
        assertThat(match(contem, "FARMACIA CENTRO")).isFalse();
        assertThat(match(comeca, "ACADEMIA CENTRAL")).isTrue();
        assertThat(match(comeca, "MENSALIDADE ACADEMIA")).isFalse();
        assertThat(match(igual, "Salário")).isTrue();
        assertThat(match(igual, "SALARIO EMPRESA")).isFalse();
    }

    private static boolean match(Rule r, String texto) {
        return classify(gasto(texto), List.of(r), List.of(), RecipientKnowledge.EMPTY).category().decided();
    }

    @Test
    void ignoraCaixaAcentoPontuacaoEEspacos() {
        Rule r = user(MatchType.CONTAINS, "Padaria São João", null, MERCADO, null, 1, 1);

        assertThat(match(r, "compra  PADARIA, sao joao!")).isTrue();
        assertThat(match(r, "PADARIA-SAO-JOAO 12/03")).isTrue();
    }

    @Test
    void oMerchantTambemEhComparado() {
        Rule r = user(MatchType.CONTAINS, "loja do ze", null, MERCADO, null, 1, 1);

        Result r1 = classify(new Subject("COMPRA 12345", "Loja do Zé", TransactionType.EXPENSE), List.of(r), List.of(), RecipientKnowledge.EMPTY);

        assertThat(r1.category().id()).isEqualTo(MERCADO);
    }

    // ---------------------------------------------------------------- tipo da transação

    @Test
    void regraComTipoSoValeParaOTipo() {
        Rule soReceita = user(MatchType.CONTAINS, "joana exemplo", TransactionType.INCOME, id("INCOME_OTHER"), null, 1, 1);
        Rule soDespesa = user(MatchType.CONTAINS, "joana exemplo", TransactionType.EXPENSE, RESTAURANTE, null, 1, 1);
        List<Rule> regras = List.of(soReceita, soDespesa);

        assertThat(classify(new Subject("PIX RECEBIDO JOANA EXEMPLO", null, TransactionType.INCOME), regras, List.of(), RecipientKnowledge.EMPTY).category().id())
                .isEqualTo(id("INCOME_OTHER"));
        assertThat(classify(gasto("PIX ENVIADO JOANA EXEMPLO"), regras, List.of(), RecipientKnowledge.EMPTY).category().id()).isEqualTo(RESTAURANTE);
    }

    @Test
    void soReceitaEDespesaSaoClassificadas() {
        for (TransactionType t : new TransactionType[] {TransactionType.TRANSFER, TransactionType.INVESTMENT, TransactionType.REFUND}) {
            Result r = classify(new Subject("COMPRA POSTO ABC", null, t), List.of(), PADRAO, votos(MERCADO, null, 10));
            assertThat(r).as(t.name()).isEqualTo(Result.NONE);
        }
    }

    // ---------------------------------------------------------------- robustez

    @Test
    void entradasVaziasOuHostisNaoQuebram() {
        assertThat(classify(new Subject(null, null, TransactionType.EXPENSE), List.of(), PADRAO, null)).isEqualTo(Result.NONE);
        assertThat(classify(gasto(""), List.of(), PADRAO, RecipientKnowledge.EMPTY)).isEqualTo(Result.NONE);
        assertThat(classify(gasto("\u0000​😀"), List.of(), PADRAO, RecipientKnowledge.EMPTY)).isEqualTo(Result.NONE);
        assertThat(classify(gasto("POSTO " + "x".repeat(100_000)), List.of(), PADRAO, RecipientKnowledge.EMPTY).category().id()).isEqualTo(COMBUSTIVEL);
    }

    @Test
    void regrasInvalidasSaoRecusadasNaCriacao() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> user(MatchType.CONTAINS, "ab", null, MERCADO, null, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> user(MatchType.CONTAINS, "mercado", null, null, null, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> user(MatchType.CONTAINS, "mercado", TransactionType.TRANSFER, MERCADO, null, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> user(MatchType.CONTAINS, "!!", null, MERCADO, null, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
