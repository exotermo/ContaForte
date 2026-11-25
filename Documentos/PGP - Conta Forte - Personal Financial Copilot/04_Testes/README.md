# Testes

Estratégia e evidências (SDD §38). Tudo roda em container, sem exigir Java, Node ou Go no host, e no CI a cada push.

| Camada | O que cobre | Como roda | Quantidade (v0.4.0) |
|---|---|---|---|
| Backend: unidade e integração | regras de negócio, motor de classificação (inclusive precisão e desempenho), API completa com login real, restrições do banco, isolamento entre usuários | `./cf test backend` (Gradle + Testcontainers com Postgres e Redis reais) | 617 |
| Frontend: unidade e componente | dinheiro, cliente da API, proxy BFF, formulários, listas | `./cf test web` (lint, tipos e Vitest) | 229 |
| Go: CLI e exporter | `cf`, cenário sintético, `container-exporter` | `./cf test go` | testes de `tools/cf` e `tools/container-exporter` |
| E2E no navegador | login, fluxo conta → transações → saldos e resumo, proxy e CSRF, navegação, revisão, regras e favorecidos | `./cf e2e` (Playwright contra a stack em execução) | 19 |
| Stack completa (CI) | `./cf init` + `./cf up` + `./cf seed` (com as importações) + login real | job `stack` do GitHub Actions | 1 fluxo |
| Carga leve | p95 das telas com 10 mil transações e importação de 20 mil linhas já classificada, com 51 regras (tempo, memória e contagem exata de categorizadas) | `./cf bench` (k6 e API) | 5 rotas e 1 importação |

## Princípios adotados

- **Teste pelo contrato, não pela implementação:** a API é exercitada por HTTP com login de verdade (`ApiTestSupport`), do token ao banco.
- **Toda rota nova ganha teste de isolamento:** o usuário B nunca enxerga nem altera dado do usuário A (404).
- **Dinheiro com casos de borda:** 0, negativo, 0,005, 999999999999,99, soma exata de centavos, nenhum `float`.
- **A promessa central tem teste dedicado:** VA e VR nunca entram em `availableCash`.
- **Dados sensíveis fora de logs, métricas e auditoria:** os testes conferem que valor e descrição não vazam.
- **Dado de teste tem dono e prefixo:** o E2E usa `e2e-` e o `./cf e2e` limpa ao terminar; o seed usa um usuário demo isolado.
- **Parsers testados para quebrar:** fuzz com sementes fixas, entradas hostis (entidades externas, UTF-16, emoji, valores como `--5` e `0.001`) e arquivos sintéticos de cada banco.
- **Precisão medida, não presumida:** o motor de classificação roda contra 766 casos sintéticos com resposta conhecida (`datasets/synthetic/classification`) e o teste falha se a precisão da categoria ou do contexto aplicados sozinhos cair abaixo de 95% (hoje: 98,1% e 100%).
- **Captura de tela como teste:** problemas de layout só aparecem olhando a imagem (dashboards na M1, rolagem no celular na M2).

## Evidências por milestone

Os números de cada fechamento estão nas lições aprendidas ([M1](../05_Encerramento/LICOES_APRENDIDAS_M1.md), [M2](../05_Encerramento/LICOES_APRENDIDAS_M2.md), [M3](../05_Encerramento/LICOES_APRENDIDAS_M3.md), [M4](../05_Encerramento/LICOES_APRENDIDAS_M4.md)) e no [CHANGELOG](../../../CHANGELOG.md).
