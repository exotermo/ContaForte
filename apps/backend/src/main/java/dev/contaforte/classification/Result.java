package dev.contaforte.classification;

/**
 * Resultado do motor: categoria e contexto são decididos separadamente ([ADR-009]), cada um pelo primeiro estágio que
 * tem confiança de sugestão ou mais. O {@link #summary()} é o que vai para a transação: um só estado, a origem e a
 * confiança do elo MAIS FRACO entre as partes decididas (a transação é tão confiável quanto a parte menos confiável).
 */
public record Result(Part category, Part context) {

    public static final Result NONE = new Result(Part.NONE, Part.NONE);

    /** Estado, origem e confiança da transação como um todo. */
    public record Summary(Decision decision, Source source, Confidence confidence) {
    }

    public Summary summary() {
        Part weakest = null;
        boolean suggest = false;
        for (Part p : new Part[] {category, context}) {
            if (!p.decided()) continue;
            if (p.decision() == Decision.SUGGEST) suggest = true;
            if (weakest == null || p.confidence().compareTo(weakest.confidence()) < 0) weakest = p;
        }
        if (weakest == null) return new Summary(Decision.NONE, null, null);
        return new Summary(suggest ? Decision.SUGGEST : Decision.AUTO, weakest.source(), weakest.confidence());
    }
}
