# ADR-019 — Dashboard: série mensal agregada no banco e "caixa disponível" como posição, não previsão

**Status:** Aceito · **Data:** 2026-11-25

## Contexto
O RF-11 pede saldo, gastos mensais, distribuição por categoria e contexto, VA/VR e caixa disponível. Faltavam a série de meses, os gráficos e a ligação com as transações. Também era preciso decidir o que o número "caixa disponível" promete, para não sugerir uma previsão que o sistema ainda não faz.

## Decisão
- **Caixa disponível é a posição de hoje** (contas correntes, poupança e dinheiro), nunca uma estimativa do que sobrará no fim do mês. VA e VR continuam fora dele ([ADR-013](013-saldo-derivado-e-classes-de-liquidez.md)). Previsão e recomendação ficam para milestone futuro e vão ter nome e explicação próprios.
- **Série mensal em uma única consulta agrupada** (`GET /summary/history?end=AAAA-MM&months=1..24`, padrão 6), com o mês calculado em America/Sao_Paulo e meses sem movimento devolvidos zerados. Cada mês usa a mesma regra do resumo mensal: transferência e investimento não são receita nem gasto, e estorno abate o gasto. Um teste garante que o último mês da série é igual ao `/summary/monthly`.
- **Gráficos em SVG puro, sem biblioteca.** Cada gráfico tem legenda em texto, `<title>`, descrição e uma tabela equivalente só para leitor de tela; a cor nunca é a única pista (a comparação com o mês anterior é escrita).
- **Aritmética em centavos inteiros** no cliente (`lib/dashboard.ts`); nada de `float` para dinheiro.
- **Do gráfico para os lançamentos:** cada categoria e contexto leva a `/transacoes` já filtrada por tipo, período e categoria ou contexto. Os filtros da URL são validados (data e UUID) e o que for inválido é ignorado.

## Consequências
- Uma consulta por tela, p95 de 48 ms com 10 mil transações; sem cache nem visão materializada por enquanto.
- O atalho filtra só despesas, então o total da lista pode diferir do valor mostrado (despesas menos estornos). "Sem categoria" e "Sem contexto" ainda não têm atalho. Ambos são dívidas conhecidas.
- Trocar o SVG por uma biblioteca fica possível sem mudar a API.
