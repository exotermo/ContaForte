package dev.contaforte.classification;

/** Quem decidiu. A decisão do usuário ({@code USER}) é registrada fora do motor. */
public enum Source {
    RULE,
    RECIPIENT,
    DEFAULT_RULE,
    /** Decisão do usuário (confirmou ou escolheu); nunca sai do motor. */
    USER
}
