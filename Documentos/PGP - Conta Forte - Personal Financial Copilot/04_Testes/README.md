# Testes

Estratégia e evidências (SDD §38). Tudo roda em container, sem exigir Java, Node ou Go no host, e no CI a cada push.

| Camada | O que cobre | Como roda | Quantidade (v0.2.0) |
|---|---|---|---|
| Backend: unidade e integração | regras de negócio, API completa com login real, restrições do banco, isolamento entre usuários | `./cf test backend` (Gradle + Testcontainers com Postgres e Redis reais) | 148 |
| Frontend: unidade e componente | dinheiro, cliente da API, proxy BFF, formulários, listas | `./cf test web` (lint, tipos e Vitest) | 111 |
| Go: CLI e exporter | `cf`, cenário sintético, `container-exporter` | `./cf test go` | testes de `tools/cf` e `tools/container-exporter` |
| E2E no navegador | login, fluxo conta → transações → saldos e resumo, proxy e CSRF, navegação | `./cf e2e` (Playwright contra a stack em execução) | 10 |
| Stack completa (CI) | `./cf init` + `./cf up` + `./cf seed` + login real | job `stack` do GitHub Actions | 1 fluxo |
| Carga leve | p95 das telas com 10 mil transações | `./cf bench` (k6) | 5 rotas |

## Princípios adotados

- **Teste pelo contrato, não pela implementação:** a API é exercitada por HTTP com login de verdade (`ApiTestSupport`), do token ao banco.
- **Toda rota nova ganha teste de isolamento:** o usuário B nunca enxerga nem altera dado do usuário A (404).
- **Dinheiro com casos de borda:** 0, negativo, 0,005, 999999999999,99, soma exata de centavos, nenhum `float`.
- **A promessa central tem teste dedicado:** VA e VR nunca entram em `availableCash`.
- **Dados sensíveis fora de logs, métricas e auditoria:** os testes conferem que valor e descrição não vazam.
- **Dado de teste tem dono e prefixo:** o E2E usa `e2e-` e o `./cf e2e` limpa ao terminar; o seed usa um usuário demo isolado.
- **Captura de tela como teste:** problemas de layout só aparecem olhando a imagem (dashboards na M1, rolagem no celular na M2).

## Evidências por milestone

Os números de cada fechamento estão nas lições aprendidas ([M1](../05_Encerramento/LICOES_APRENDIDAS_M1.md), [M2](../05_Encerramento/LICOES_APRENDIDAS_M2.md)) e no [CHANGELOG](../../../CHANGELOG.md).
