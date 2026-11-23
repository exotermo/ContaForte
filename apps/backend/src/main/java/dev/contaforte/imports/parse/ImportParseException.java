package dev.contaforte.imports.parse;

/**
 * Erro do ARQUIVO inteiro (formato desconhecido, moeda não suportada, limite estourado...). Problemas de uma linha
 * específica não lançam: viram uma linha inválida com o motivo. A mensagem nunca inclui conteúdo do extrato.
 */
public class ImportParseException extends RuntimeException {

    public enum Reason {
        EMPTY, TOO_LARGE, NOT_TEXT, UNKNOWN_FORMAT, NO_TRANSACTIONS, TOO_MANY_ROWS,
        UNSUPPORTED_CURRENCY, MULTIPLE_ACCOUNTS, BAD_MAPPING, UNCLOSED_QUOTE
    }

    private final Reason reason;

    public ImportParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
