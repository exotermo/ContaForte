package dev.contaforte.imports;

/** Classificação de cada linha do extrato na prévia (ADR-016). Só NEW e POSSIBLE_DUPLICATE podem ser importadas. */
public enum ImportItemStatus {
    /** Linha nova: será importada. */
    NEW,
    /** Já existe (mesmo identificador externo, ou mesmo valor, dia e descrição): nunca é importada. */
    DUPLICATE,
    /** Parece existir (mesmo valor e dia próximo, descrição diferente): o usuário decide. */
    POSSIBLE_DUPLICATE,
    /** Não pôde ser lida (data, valor ou tipo inválido): fica na prévia com o motivo e nunca é importada. */
    INVALID
}
