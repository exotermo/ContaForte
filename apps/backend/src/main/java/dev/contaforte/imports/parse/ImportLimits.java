package dev.contaforte.imports.parse;

/** Limites duros da importação (ADR-015). Valem para qualquer entrada, venha ou não pela API. */
public final class ImportLimits {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ROWS = 20_000;
    public static final int MAX_DESCRIPTION = 200;
    public static final int MAX_MERCHANT = 120;
    public static final int MAX_EXTERNAL_ID = 255;
    /** Campo de CSV maior que isso é cortado (e nunca alocado por inteiro). */
    static final int MAX_FIELD = 10_000;
    static final int MAX_COLUMNS = 200;

    private ImportLimits() {
    }

    static void checkSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ImportParseException(ImportParseException.Reason.EMPTY, "O arquivo está vazio.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ImportParseException(ImportParseException.Reason.TOO_LARGE,
                    "O arquivo passa do limite de " + (MAX_BYTES / (1024 * 1024)) + " MB.");
        }
    }
}
