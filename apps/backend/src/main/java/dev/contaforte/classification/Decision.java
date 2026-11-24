package dev.contaforte.classification;

/** O que fazer com a classificação, pela confiança (ADR-017). */
public enum Decision {
    /** Aplica sozinho e marca como automática. */
    AUTO,
    /** Só sugere: nada é aplicado até o usuário confirmar. */
    SUGGEST,
    /** Sem sugestão. */
    NONE
}
