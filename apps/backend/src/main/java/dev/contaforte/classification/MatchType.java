package dev.contaforte.classification;

/** Como a regra compara o padrão com o texto normalizado (por palavras inteiras, nunca por pedaço de palavra). */
public enum MatchType {
    /** O padrão aparece como sequência de palavras inteiras no texto. */
    CONTAINS,
    /** O texto começa pelo padrão, em palavras inteiras. */
    STARTS_WITH,
    /** O texto é exatamente o padrão. */
    EQUALS
}
