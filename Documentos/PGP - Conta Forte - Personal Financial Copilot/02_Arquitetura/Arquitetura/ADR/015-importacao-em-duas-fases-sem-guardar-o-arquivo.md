# ADR-015 — Importação em duas fases, sem guardar o arquivo, com rollback lógico

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
O Milestone 3 importa extratos OFX e CSV. Extratos trazem dados pessoais de terceiros (nomes, documentos, estabelecimentos), os formatos variam por banco e um erro de importação pode somar ou esconder dinheiro. O SDD (§30–31) pede que cada importação tenha um identificador, para auditoria e rollback lógico.

## Decisão
- **Duas fases.** O envio do arquivo cria uma **prévia** (`imports` em `PREVIEW` + `import_items`): cada linha é lida, normalizada e classificada (nova, duplicada, possível duplicata, inválida), e **nenhuma transação é criada**. Só a confirmação cria as transações, numa única transação do banco. O usuário sempre vê o que vai entrar.
- **Ciclo de vida:** `PREVIEW` → `CONFIRMED` → `ROLLED_BACK`. A prévia não confirmada **vence em 24 h** e é apagada; descartar uma prévia apaga a linha (e as dela, em cascata). A confirmação é idempotente: confirmar de novo é conflito.
- **O arquivo não é guardado.** Ficam o hash SHA-256 (para avisar "este arquivo já foi importado nesta conta"), o nome do arquivo e as linhas já normalizadas. O texto bruto das linhas não é persistido. Nome do arquivo, descrições e valores nunca vão para logs, métricas ou auditoria (que registram só ids, formato e contagens).
- **Rollback lógico.** Desfazer marca como excluídas (`deleted_at`) as transações da importação que ainda estejam ativas e muda o status para `ROLLED_BACK`. Saldos e resumos acompanham sozinhos (já ignoram excluídas); o identificador externo é liberado e reimportar funciona. A importação e suas linhas permanecem como histórico. **Uma importação confirmada não pode ser apagada** (a chave estrangeira impede): só desfeita.
- **Integridade no banco**, no mesmo espírito do [ADR-013](013-saldo-derivado-e-classes-de-liquidez.md): a importação é da conta e do dono (chave estrangeira composta); a transação importada referencia `(import_id, account_id)`, então só pode apontar para uma importação **da própria conta** (e, por consequência, do mesmo dono); só origens de arquivo (OFX, CSV) têm importação; contadores precisam somar o total; as datas seguem o status.
- **Limites** (impostos na API, M3.4): 5 MB por arquivo, 20 mil linhas, 20 prévias por hora por usuário. Tipo conferido pelo conteúdo.
- **Apagar o usuário apaga tudo** (importações, linhas e transações), como no restante do modelo (LGPD).

## Alternativas descartadas
- **Importar direto, com relatório depois:** mais simples, mas qualquer erro de leitura já alteraria saldos e o desfazer viraria a única defesa.
- **Guardar o arquivo bruto:** facilitaria reprocessar, mas retém dados de terceiros sem necessidade (Privacy by Design). Reprocessar é reenviar o arquivo.
- **Apagar fisicamente no rollback:** perderia o histórico e a auditoria, e impediria explicar um saldo antigo.
- **Biblioteca de parser OFX:** as opções são pouco mantidas; o formato é simples e um tokenizador próprio, linear e sem XML, evita XXE e dá controle sobre limites e erros por linha.

## Consequências
- O fluxo tem um passo a mais para o usuário (revisar a prévia), pago pela segurança e pela base do "human-in-the-loop" do M4.
- Há uma tarefa agendada de limpeza das prévias vencidas.
- Reimportar um arquivo exige reenviá-lo; o hash só serve para avisar.
- Importar de novo depois de um rollback depende de o identificador externo ter sido liberado (índice único parcial entre as ativas).
