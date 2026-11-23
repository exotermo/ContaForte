# Lições aprendidas — Milestone 2 (Financial Core)

## Entregue
Contas e carteiras, categorias e contextos, transações manuais, resumo financeiro com separação de liquidez, as três telas, dados sintéticos e o painel de negócio. Números ao fechar: **149 testes de backend**, **111 de frontend**, **10 E2E no navegador**, mais os de Go; p95 abaixo de 50 ms com 10 mil transações.

## O que funcionou
- **Modelar primeiro, no banco:** chave estrangeira composta `(conta, user_id)` e gatilhos fizeram do isolamento entre usuários uma garantia do banco, não só da API. Os testes de restrição pegaram erros antes de existir endpoint.
- **Decisões escritas antes do código (ADR-013/014):** saldo derivado, uma linha por transferência e classes de liquidez evitaram retrabalho nas cinco etapas seguintes.
- **Testar pelo contrato, com login de verdade:** a base `ApiTestSupport` deixou cada etapa nova com isolamento, auditoria e OpenAPI testados quase de graça.
- **Autoverificação do seed:** o `cf seed` compara o resumo do backend com o que o gerador em Python previu. Backend, gerador e CLI em Go concordaram na primeira execução, e o CI repete isso a cada push.
- **Validar na stack real, não só nos testes:** cada etapa terminou com `curl` contra a stack, com limpeza dos dados de teste por prefixo (`cf-m2x-`, `e2e-`).
- **Captura de tela:** achou a rolagem horizontal no celular (M2.6) e o contador que não aparecia no painel (M2.7).

## O que deu trabalho
| Problema | Causa | Solução |
|---|---|---|
| Métrica saía como `contaforte_transactions_total` | o Prometheus reserva o sufixo `_created` | renomeada para `contaforte_transactions_recorded_total` antes de qualquer painel depender dela |
| "Transações na última hora: 0" com 308 registradas | contador criado já com valor > 0 não tem amostra anterior; `increase()` perde a primeira contagem | contadores de todos os tipos registrados em 0 na partida (teste confere) |
| Painel de 4xx zerado | mesma limitação nas séries HTTP, criadas na primeira requisição | documentado: a primeira ocorrência de uma série nova não aparece; as seguintes aparecem (provado com nova leva de 404) |
| UUID como valor de tag de métrica | era o `http.client.requests` do próprio `TestRestTemplate`; o servidor expõe a rota templada | conferido no Prometheus real; teste exclui as métricas de cliente |
| Valor com 3 casas arredondado em silêncio | `NUMERIC(14,2)` do Postgres arredonda | `Money.parse` recusa (422) antes de gravar; mesmas regras no `parseMoney` do front |
| `PATCH` não distinguia "ausente" de `null` | `Optional` no Jackson trata os dois como vazio | presença rastreada nos setters; `null` explícito limpa o campo |
| Página com rolagem horizontal no celular | elementos `sr-only` (absolutos) escapam de contêiner `overflow` sem `position: relative` | contêiner `relative` e tabela empilhada em cartões |
| Erro "cannot find symbol" em teste | passar `Map` cru a método genérico apaga o tipo do retorno | declarar o tipo (`Map<String, Object>`) |
| O seed precisava de usuário demo, mas não havia como criá-lo | só existia `GET /admin/users` | `POST /admin/users` (cria só USER; teste de que `role` no corpo não escala privilégio) |
| Datas e "o mês" no fuso errado | o servidor guarda UTC | "dia" e "mês" sempre em America/Sao_Paulo (`AppTime`), com teste de 23h30 de 31/03 |

## Decisões que valem lembrar
- O saldo nunca é armazenado; `AccountBalances` é o único cálculo.
- Transferência e investimento não são receita nem gasto no resumo mensal.
- Contas arquivadas saem de todos os totais; a tela avisa antes de arquivar uma conta com saldo.
- Recurso de outro usuário responde 404, nunca 403.
- Dinheiro é string decimal de ponta a ponta; nada de `Number` no front.
- Demonstração e bench só rodam em ambiente local (recusam com o túnel público ativo) e limpam depois.

## Dívidas conhecidas (candidatas a milestones futuros)
- Excluir ou mesclar categorias e contextos do usuário.
- Restaurar transação excluída; editar tipo e contas (hoje: excluir e relançar).
- Editar o saldo inicial e a transação pela tela; excluir conta (hoje só arquivar).
- Sem limite de taxa nos endpoints financeiros (só no login).
- Conta arquivada com saldo some do patrimônio no backend sem aviso (o aviso é só na tela).
- `/summary` soma todas as transações a cada leitura: ~300 ms com 100 mil linhas.
- Sem auditoria de acessibilidade automática (axe) nem teste com leitor de tela.
- Pendências herdadas do M1: usuário `pg_monitor`, Alertmanager, CSP com nonce, corrida de refresh entre abas.
