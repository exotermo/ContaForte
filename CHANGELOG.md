# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/), com versionamento semântico.

## [0.2.0] — Milestone 2: Financial Core

### Adicionado
- **Contas e carteiras** (8 tipos) com classe de liquidez por tipo; o saldo é derivado das transações, nunca armazenado ([ADR-013](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/013-saldo-derivado-e-classes-de-liquidez.md)).
- **Categorias em 2 níveis** (12 categorias e 39 subcategorias padrão em pt-BR) e **contextos** (Individual, Família, Relacionamento, Pet, Trabalho, Outro), com os do usuário ao lado dos padrão ([ADR-014](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/014-categorias-em-dois-niveis-e-contextos.md)).
- **Transações manuais** (receita, despesa, transferência, investimento e estorno) com filtros por período, conta, tipo, categoria, contexto e texto, paginação e exclusão lógica.
- **Resumo financeiro**: `available_cash`, `restricted_balance` (VA/VR nunca entram no caixa disponível), `invested_assets`, cartão de crédito e patrimônio líquido; e **resumo mensal** por categoria e contexto.
- **Telas** de Resumo, Contas e Transações, com formulários acessíveis em pt-BR e proxy de dados `/bff` (o navegador continua sem ver tokens).
- `POST /api/v1/admin/users`: o administrador cria pessoas (sempre com papel USER).
- **Dados sintéticos** (`datasets/synthetic`, gerados de forma determinística) e `./cf seed`, que carrega 90 dias de dados fictícios num usuário demo e se autoverifica contra o resumo esperado.
- `./cf bench`: p95 das telas com 10 mil transações (k6).
- Painel **Negócio** no Grafana e a métrica `contaforte_transactions_recorded_total{type}`.
- `./cf version` passa a mostrar a tag do Git.

### Segurança
- Isolamento entre usuários também no banco (chave estrangeira composta e gatilhos); recurso de outro usuário responde 404.
- Valor e descrição de transações nunca vão para logs, métricas, eventos ou auditoria.
- Papéis SERVICE e DEVICE não acessam dados financeiros.

## [0.1.0] — Milestone 1: Foundation

### Adicionado
- Stack Docker com redes isoladas (`edge`, `app` e `obs` internas), PostgreSQL 16, Redis 7, Mosquitto e gateway nginx publicado só em `127.0.0.1`.
- CLI `cf` (Go): `init`, `up`, `down`, `reset`, `status`, `doctor`, `logs`, `open`, `test`, `e2e`, `backup`, `restore`.
- Backend Spring Boot (Java 21) com Flyway e Actuator.
- Autenticação com JWT RS256, refresh tokens com rotação e detecção de reuso, Argon2id, rate limit no Redis, RBAC e auditoria sem dados pessoais.
- Frontend Next.js com login, página protegida e PWA; sessão por cookies httpOnly (BFF).
- Monitoramento: Prometheus, Grafana (3 dashboards provisionados), Loki, Alloy, exporters, alertas básicos e proxy somente-leitura do Docker.
- CI no GitHub Actions (Go, backend, frontend, smoke test da stack, gitleaks) e Dependabot.
- Perfil opcional de Cloudflare Tunnel.
- ADRs 001 a 012 e documentação de operação e desenvolvimento.
