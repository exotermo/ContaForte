# Documentação do ContaForte

Porta de entrada da documentação do **ContaForte — Personal Financial Copilot**. A árvore segue a organização do PGP/MCTI de forma enxuta: só existe documento onde há informação útil agora. Os demais diretórios são preenchidos conforme os milestones avançam.

## Comece por aqui

| Objetivo | Documento |
|---|---|
| Entender o propósito, escopo e riscos | [Termo de Abertura](00_Gestao_do_Projeto/Termo_de_Abertura.md) |
| Ver o que está dentro e fora do escopo | [Especificação de Escopo](00_Gestao_do_Projeto/Especificacao_de_Escopo.md) |
| Acompanhar riscos | [Riscos](00_Gestao_do_Projeto/Riscos.md) |
| Ver os requisitos por milestone | [Requisitos](01_Analise_e_Requisitos/Requisitos.md) |
| Entender a arquitetura | [ARCHITECTURE](02_Arquitetura/Arquitetura/ARCHITECTURE.md) |
| Conhecer as decisões arquiteturais | [ADRs](02_Arquitetura/Arquitetura/ADR/README.md) |
| Ler o design completo | [SDD](02_Arquitetura/SDD/SDD_Personal_Financial_Copilot.md) |

## Estrutura

| Pasta | Conteúdo | Estado |
|---|---|---|
| `00_Gestao_do_Projeto` | Abertura, escopo, riscos | ✅ iniciado |
| `01_Analise_e_Requisitos` | Requisitos funcionais e não funcionais | ✅ iniciado |
| `02_Arquitetura` | SDD, arquitetura, ADRs | ✅ iniciado |
| `03_Desenvolvimento` | Guia de desenvolvimento, changelog | ⏳ Milestone 1 |
| `04_Testes` | Plano e evidências de teste | ⏳ Milestone 1 |
| `05_Encerramento` | Aceite e lições aprendidas | ⏳ por milestone |
| `06_Operacao` | Instalação, runbooks, backup | ⏳ Milestone 1 |
| `07_Referencias` | Normas, LGPD, links | ✅ iniciado |

Regra de precedência em caso de divergência: **código e configuração > migrations/schemas > ADRs > SDD**. Veja [DOCUMENTATION_PLAN.md](DOCUMENTATION_PLAN.md).
