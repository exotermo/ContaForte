package dev.contaforte.imports.parse;

import java.util.List;
import java.util.Map;

/**
 * Leitura prévia de um CSV para ajudar o usuário a escolher o mapeamento: delimitador e cabeçalho detectados, as
 * primeiras linhas e um palpite de colunas ({@code mapping} vazio quando não foi possível adivinhar).
 */
public record CsvSuggestion(char delimiter, int skipLines, boolean hasHeader, List<String> headers,
                            List<List<String>> sample, Map<String, String> mapping, List<String> warnings) {
}
