-- Sem este índice, cada transação apagada de verdade varre import_items inteira para aplicar o ON DELETE SET NULL
-- de duplicate_of: o custo cresce com o quadrado do histórico (apagar um usuário passava de 9 minutos num banco
-- pequeno). Parcial: só as linhas marcadas como duplicata têm valor.
CREATE INDEX ix_import_items_duplicate_of ON import_items (duplicate_of) WHERE duplicate_of IS NOT NULL;
