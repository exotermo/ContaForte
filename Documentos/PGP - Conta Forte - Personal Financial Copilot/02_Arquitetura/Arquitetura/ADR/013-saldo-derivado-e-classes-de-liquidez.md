# ADR-013 — Saldo derivado e classes de liquidez

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
O produto promete que nem todo saldo é capital disponível: VA/VR não podem aparecer como dinheiro para investir. Também é preciso decidir como guardar saldos e como representar transferências sem criar inconsistência entre contas e lançamentos.

## Decisão
- **Conta e carteira são a mesma tabela (`accounts`).** VA, VR e CASH já são carteiras pelo tipo; separar duplicaria modelo e telas.
- **O saldo não é armazenado.** Saldo = `opening_balance` + efeito das transações. Sem coluna de saldo, não há como ele divergir dos lançamentos.
- **Valor monetário:** `NUMERIC(14,2)` no banco e `BigDecimal` no Java ([ADR-008](008-bigdecimal-para-valores-monetarios.md)). `amount` é sempre positivo; o **tipo** dá o sinal. Moeda fixa em `BRL` no MVP (CHECK no banco); a coluna `currency` existe para o futuro.
- **Liquidez por tipo de conta**, codificada em `AccountType.liquidity()`:

  | Tipo | Totalizador |
  |---|---|
  | CHECKING_ACCOUNT, SAVINGS_ACCOUNT, CASH | `available_cash` |
  | VA, VR | `restricted_balance` (nunca é caixa disponível) |
  | INVESTMENT_ACCOUNT | `invested_assets` |
  | CREDIT_CARD | passivo (saldo negativo), reduz só o `total_net_worth` |
  | OTHER | só `total_net_worth` |

  Poupança conta como disponível por ter liquidez imediata. Mudar essa regra altera relatórios e exige um novo ADR.
- **Efeito de cada tipo na conta de origem:** INCOME e REFUND somam; EXPENSE subtrai; TRANSFER e INVESTMENT subtraem e somam o mesmo valor na **contraparte**.
- **Transferência e investimento são uma linha só**, com `account_id` (origem) e `counterpart_account_id` (destino). Uma linha não tem como ficar com "meia transferência" depois de uma falha.
- **Integridade no banco, não só na API:** chave estrangeira composta `(conta, user_id)` garante que conta e contraparte pertencem ao mesmo usuário da transação; CHECKs exigem contraparte só em TRANSFER/INVESTMENT e diferente da origem; um gatilho barra categoria ou contexto de outro usuário.
- **Exclusão lógica** (`deleted_at`) em transações e arquivamento (`archived_at`) em contas, que liberam o nome para reuso. Prepara o rollback de importações do Milestone 3.
- **Apagar o usuário apaga tudo dele** em cascata (direito ao apagamento da LGPD), preservando as categorias e contextos padrão.

## Consequências
- Todo saldo exige somar transações. Com a escala de uso pessoal isso é barato; se a medição mostrar lentidão, o caminho é índice ou visão materializada, em um novo ADR.
- O banco arredonda silenciosamente valores com mais de 2 casas (comportamento do `NUMERIC(14,2)`), então a **API precisa recusar** esses valores com 422 antes de gravar. O teste disso fica na etapa da API de transações.
- **Formato na API:** valores monetários **saem como string decimal** (`"1234.50"`), nunca como número JSON, para que nenhum cliente passe por ponto flutuante. Na entrada aceita-se string ou número, com no máximo 2 casas e até 12 dígitos inteiros (`Money.parse`).
- A consulta de saldo usa o sinal por tipo em SQL, espelhando `TransactionType.accountSign()`; um teste confere os cinco tipos contra um cálculo manual para as duas regras não divergirem.
- Isolamento entre usuários tem duas camadas: consultas escopadas por dono na aplicação e as restrições acima no banco.
