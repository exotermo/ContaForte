# Arquitetura

Visão resumida. O design completo está no [SDD](../SDD/SDD_Personal_Financial_Copilot.md), e as decisões estão nos [ADRs](ADR/README.md).

## Visão geral

```text
Cliente (Next.js PWA)
        │ HTTPS
        ▼
Cloudflare (Tunnel, WAF, Access)
        │
        ▼
Backend Java 21 / Spring Boot  ──REST interno──►  Intelligence (Python/FastAPI)
  (monólito modular)                               classificação, previsão, anomalias
        │            │
        ▼            ▼
   PostgreSQL      Redis
        │
        ▼
   IoT Gateway ──MQTT──► ESP32 (OLED, botões, impressora)
```

## Componentes

| Componente | Responsabilidade | Tecnologia |
|---|---|---|
| `apps/web` | Interface web e PWA | Next.js, React, TypeScript, TanStack Query, Tailwind |
| `apps/backend` | Domínio financeiro, API REST `/api/v1`, eventos | Java 21, Spring Boot, JPA, Flyway |
| `apps/intelligence` | Computação analítica (ML) | Python, FastAPI, scikit-learn |
| PostgreSQL | Fonte da verdade | PostgreSQL |
| Redis | Cache, jobs, rate limit e locks (nunca fonte primária) | Redis |
| MQTT / ESP32 | Dispositivos físicos | Mosquitto, ESP32 |

## Módulos do backend
`auth`, `users`, `accounts`, `transactions`, `categorization`, `budgets`, `goals`, `predictions`, `recommendations`, `investments`, `integrations`, `notifications`, `iot`. Cada módulo tem limites claros para permitir extração futura.

## Fluxo principal
`Importação → Normalização → TransactionCreated → Categorização (regras → favorecido → ML) → TransactionCategorized → Orçamento / Previsão / Insights → Dashboard (e IoT, se relevante)`.

## Princípios
- Java é dono dos dados, e o Python não acessa as tabelas de negócio.
- Eventos de domínio com `eventId` e consumidores idempotentes.
- Zero Trust entre todos os componentes, e o ESP32 nunca fala direto com o banco.
- Dinheiro em `BigDecimal`/`NUMERIC`.
