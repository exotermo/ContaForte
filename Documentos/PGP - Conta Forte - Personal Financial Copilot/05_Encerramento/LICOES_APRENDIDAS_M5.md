# Lições aprendidas — Milestone 5 (Dashboard)

## Entregue
Tela inicial com a movimentação do mês (receitas, gastos sem estornos, sobra, investido e comparação com o mês anterior), série mensal de até 24 meses numa só consulta, gráficos em SVG acessíveis, gastos por categoria e contexto com atalho para as transações filtradas, autoverificação no seed, rota nova no bench e seção no painel Negócio. Números ao fechar: **623 testes de backend**, **243 de frontend**, **21 E2E**, mais os de Go; série mensal com p95 de **48 ms** em 10 mil transações; importação de 20 mil linhas sem regressão (prévia 3,5 s, confirmação 4,2 s, pico de 468 MiB).

## O que funcionou
- **Reaproveitar a regra, não reescrevê-la:** um teste exige que o último mês da série seja igual ao resumo mensal; o seed repete a conferência com números reais. Duas visões do mesmo dado não podem divergir.
- **Uma consulta agrupada** em vez de uma por mês: simples de medir e sem custo perceptível.
- **Centavos inteiros no cliente:** a comparação com o mês anterior e as porcentagens saem exatas.
- **Acessibilidade desde o início:** legenda em texto, `<title>`, descrição e tabela `sr-only` evitaram refazer os gráficos depois.
- **Olhar a captura de tela:** dois defeitos (título e gráfico no celular) só apareceram na imagem.

## O que deu trabalho
| Problema | Causa | Solução |
|---|---|---|
| E2E com seletor ambíguo (modo estrito) | "Transações" existia no menu e nos links novos | links "Ver lançamentos de X" e seletores por papel e nome |
| Texto ambíguo em teste de componente | mesmo valor em mais de um bloco | `data-testid` nos blocos de números |
| E-mail de E2E reaproveitado | contador reiniciava por execução | prefixo com a hora (`e2e-<hora>-N`) |
| "Novembro De 2026" | `capitalize` do CSS põe maiúscula em toda palavra | `first-letter:uppercase` |
| Gráfico ilegível no celular (rótulos de ~5 px) | viewBox largo demais encolhido | viewBox 420×210 e barras proporcionais |
| Script de DoD com formato de API errado | suposição sem conferir a resposta | conferir a resposta real antes de escrever a asserção |
| `./cf down -v` deixou volumes | projeto de compose com outro nome | remoção manual (causa não investigada) |
| Lint, tsc e Vitest não rodam no host | Node do host sem as dependências | sempre `./cf test web` |

## Decisões que valem lembrar
- Caixa disponível é posição de hoje, não previsão; VA/VR ficam fora.
- Mês sempre em America/Sao_Paulo, calculado no banco.
- Transferência e investimento não são receita nem gasto; estorno abate o gasto.
- Filtros vindos da URL são validados e o inválido é ignorado.

## Dívidas conhecidas (candidatas a milestones futuros)
- "Sem categoria" e "Sem contexto" não têm atalho para as transações.
- O atalho filtra só despesas; o total da lista pode diferir do mostrado por causa dos estornos.
- O gráfico não é clicável e não foi testado com leitor de tela.
- `./cf down -v` pode deixar volumes de projetos renomeados.
- Previsão e recomendação continuam fora do MVP.
