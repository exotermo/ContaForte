package dev.contaforte.transactions;

/** Estado de classificação da transação (ADR-017). Espelha o CHECK da V7. */
public enum ClassificationStatus {
    /** Nunca passou pelo motor nem por decisão do usuário. */
    NONE,
    /** O motor aplicou categoria e/ou contexto sozinho (confiança alta). */
    AUTO,
    /** O motor só sugeriu: categoria e contexto sugeridos ficam à parte até o usuário decidir. */
    SUGGESTED,
    /** O usuário decidiu (confirmou a sugestão ou escolheu). */
    CONFIRMED
}
