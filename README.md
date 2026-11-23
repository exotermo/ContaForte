# ContaForte — Personal Financial Copilot

Plataforma open source de gestão financeira pessoal, pensada para o contexto brasileiro (PIX, VA/VR, OFX/CSV, LGPD). Em vez de só mostrar "onde o dinheiro foi gasto", ela mantém um modelo financeiro **contextual** do usuário para responder *em que contexto* o gasto aconteceu, qual o impacto e o que fazer a respeito.

[![CI](https://github.com/exotermo/ContaForte/actions/workflows/ci.yml/badge.svg)](https://github.com/exotermo/ContaForte/actions/workflows/ci.yml)

> **Status:** Milestone 2 (Financial Core) concluído (`v0.2.0`): contas e carteiras, categorias e contextos, transações, resumo financeiro com a separação de liquidez (VA/VR nunca contam como dinheiro disponível) e as telas correspondentes, sobre a fundação do Milestone 1 (stack Docker isolada, autenticação, monitoramento e CI). Próximo: importação de extratos (Milestone 3).

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

## Quickstart

Precisa só de Docker (Compose v2) e cerca de 4 GB de RAM livre. Java, Node e Go não são necessários no host.

```bash
./cf init     # verifica pré-requisitos e gera o .env com segredos aleatórios
./cf up       # constrói e sobe a stack, esperando ficar saudável
./cf open     # http://127.0.0.1:8080 (admin inicial: veja ADMIN_EMAIL/ADMIN_PASSWORD no .env)
./cf seed     # opcional: carrega 90 dias de dados FICTÍCIOS num usuário demo (demo@contaforte.local, senha em DEMO_PASSWORD no .env)
```

O CLI `./cf` também cuida de `status`, `doctor`, `logs`, `test`, `e2e`, `bench`, `backup` e `restore`. O Grafana fica em `http://127.0.0.1:8080/grafana/` (usuário `admin`, senha `GRAFANA_PASSWORD`). Para publicar na Internet sem abrir portas há um perfil opcional de Cloudflare Tunnel. Guia completo em [Operação](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/06_Operacao/README.md).

### Segurança por padrão

- Só o gateway publica porta, e apenas em `127.0.0.1`. Bancos e serviços ficam em redes Docker `internal`, sem saída à Internet.
- Segredos gerados aleatoriamente pelo `./cf init` (`.env` com permissão 600, fora do Git). Login com JWT RS256, refresh com rotação e rate limit.
- Nenhum container monta o `docker.sock` diretamente: o monitoramento usa um proxy somente-leitura.

### Operação: o que saber

- **Memória do host:** os limites dos containers somam cerca de 3,7 GB, e o uso real fica em torno de 1,3 GB.
- **Postgres do monitoramento:** o `postgres-exporter` usa o mesmo usuário da aplicação. Um usuário dedicado com o papel `pg_monitor` é um endurecimento futuro.
- **Sem Alertmanager:** os alertas só aparecem no Prometheus e no Grafana, não notificam ninguém.
- **Gerador de dashboards:** `tools/gen-dashboards` reproduz exatamente os JSON provisionados. Edite lá e regenere.

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

Para desenvolver, veja o [guia de desenvolvimento](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/03_Desenvolvimento/DEVELOPMENT.md) e o [CHANGELOG](CHANGELOG.md). Toda a documentação de projeto está em [`Documentos/`](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/README.md), organizada seguindo o PGP/MCTI de forma enxuta.

## Contribuindo

Veja [CONTRIBUTING.md](CONTRIBUTING.md). Problemas de segurança: [SECURITY.md](SECURITY.md).

## Licença

[GNU AGPL-3.0](LICENSE). Se você hospedar uma versão modificada como serviço, precisa disponibilizar o código-fonte aos usuários.
