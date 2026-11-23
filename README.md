# ContaForte — Personal Financial Copilot

Plataforma open source de gestão financeira pessoal, pensada para o contexto brasileiro (PIX, VA/VR, OFX/CSV, LGPD). Em vez de só mostrar "onde o dinheiro foi gasto", ela mantém um modelo financeiro **contextual** do usuário para responder *em que contexto* o gasto aconteceu, qual o impacto e o que fazer a respeito.

> **Status:** fase de design (SDD v0.1.0). Ainda não há código. O roadmap está abaixo.

## Ideias centrais

- **Categoria ≠ contexto:** `FOOD/RESTAURANT` + contexto `RELATIONSHIP`.
- **Liquidez importa:** VA/VR não contam como capital disponível para investir.
- **PIX Intelligence:** o sistema aprende a categoria e o contexto de cada favorecido.
- **Recomendações explicáveis:** toda sugestão carrega evidências. O LLM só redige o texto e nunca calcula.
- **Human-in-the-loop:** quando a confiança é baixa, o sistema pergunta, e cada correção vira feedback.
- **Privacy by Design / Zero Trust / LGPD.**
- **IoT opcional:** ESP32 com OLED e impressora térmica para "recibos" financeiros.

## Arquitetura (resumo)

Monólito modular em Java 21 + Spring Boot, serviço de ML em Python/FastAPI isolado, front em Next.js, PostgreSQL como fonte da verdade e Redis para cache e jobs. Ingress via Cloudflare Tunnel e MQTT para IoT. Detalhes em [`ARCHITECTURE.md`](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ARCHITECTURE.md) e no [SDD](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/SDD/SDD_Personal_Financial_Copilot.md).

## Roadmap

| # | Milestone | Foco |
|---|---|---|
| 1 | Foundation | Docker Compose, PostgreSQL, Redis, backend, Next.js, auth, CI |
| 2 | Financial Core | Contas, carteiras, transações, categorias, contextos |
| 3 | Imports | CSV, OFX, normalização, deduplicação |
| 4 | PIX Intelligence | Detecção de favorecidos, regras, categorização, feedback |
| 5 | Dashboard | Saldo, gastos, distribuição, VA/VR, caixa disponível |
| 6 | Intelligence | Serviço Python, previsão, anomalias, insights |
| 7 | Goals | Orçamentos, reserva, metas, capacidade de investimento |
| 8 | IoT | MQTT, ESP32, OLED, impressora térmica |
| 9 | Financial Twin | Simulação de cenários |

A prioridade é entregar os milestones 1 a 5 de ponta a ponta antes de avançar.

## Documentação

Toda a documentação de projeto está em [`Documentos/`](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/README.md), organizada seguindo o PGP/MCTI de forma enxuta.

## Contribuindo

Veja [CONTRIBUTING.md](CONTRIBUTING.md). Problemas de segurança: [SECURITY.md](SECURITY.md).

## Licença

[GNU AGPL-3.0](LICENSE). Se você hospedar uma versão modificada como serviço, precisa disponibilizar o código-fonte aos usuários.
