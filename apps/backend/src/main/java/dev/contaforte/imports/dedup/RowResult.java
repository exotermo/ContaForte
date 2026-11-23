package dev.contaforte.imports.dedup;

import dev.contaforte.imports.ImportItemStatus;
import dev.contaforte.imports.parse.ParsedRow;
import java.util.UUID;

/**
 * Resultado da classificação de uma linha. {@code duplicateOf} aponta a transação existente que a "consumiu" (nulo para
 * nova, inválida e para repetição dentro do próprio arquivo). {@code selected} é o padrão da prévia: só as novas vêm
 * marcadas; possível duplicata é decisão do usuário.
 */
public record RowResult(ParsedRow row, ImportItemStatus status, UUID duplicateOf, String reason, String text, boolean selected) {
}
