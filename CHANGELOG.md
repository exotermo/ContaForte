# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/), com versionamento semântico.

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
