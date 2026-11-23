# Software Design Document — Personal Financial Copilot

**Status:** Draft  
**Version:** 0.1.0  
**Arquitetura:** Modular Monolith + Event-Driven Components  
**Plataforma:** Web / Mobile-ready / IoT-ready

---

# 1. Visão Geral

## 1.1 Propósito

O **Personal Financial Copilot** é uma plataforma de gestão financeira pessoal orientada por dados, criada para ajudar usuários a compreender seus hábitos financeiros, controlar despesas, prever fluxo de caixa e receber recomendações personalizadas.

Diferentemente de sistemas tradicionais de controle financeiro baseados apenas em categorias e dashboards, a plataforma mantém um modelo financeiro contextual do usuário.

O sistema deverá ser capaz de compreender não apenas:

> "Onde o dinheiro foi gasto?"

mas também:

> "Em qual contexto o dinheiro foi gasto, qual o impacto desse comportamento e o que pode ser feito a respeito?"

A plataforma combinará:

- agregação de dados financeiros;
- processamento de transações PIX;
- categorização automática;
- gestão de VA/VR e outras carteiras;
- orçamento;
- previsão de fluxo de caixa;
- detecção de anomalias;
- recomendações financeiras;
- planejamento de investimentos;
- machine learning;
- dispositivos IoT;
- impressão térmica de insights financeiros.

---

# 2. Objetivos do Sistema

Os principais objetivos são:

1. Centralizar informações financeiras do usuário.
2. Reduzir a necessidade de categorização manual.
3. Aprender padrões financeiros individuais.
4. Separar dinheiro líquido de benefícios restritos como VA e VR.
5. Identificar gastos recorrentes e padrões de PIX.
6. Projetar despesas e saldo futuro.
7. Detectar comportamentos financeiros anormais.
8. Identificar oportunidades de economia.
9. Calcular capacidade de investimento.
10. Criar recomendações explicáveis.
11. Permitir simulações financeiras futuras.
12. Integrar o sistema financeiro com dispositivos físicos.

---

# 3. Princípios de Design

## 3.1 Privacy by Design

Dados financeiros são considerados sensíveis dentro do domínio da aplicação.

O sistema deverá minimizar coleta, exposição e movimentação desnecessária desses dados.

## 3.2 Zero Trust

Nenhum componente será considerado confiável simplesmente por estar dentro de uma determinada rede.

Toda comunicação entre componentes deverá possuir:

- identidade;
- autenticação;
- autorização;
- criptografia;
- auditoria.

## 3.3 Explainable Recommendations

Nenhuma recomendação financeira relevante deverá existir apenas porque "a IA decidiu".

Toda recomendação deverá possuir evidências que permitam explicar sua origem.

Exemplo:

> Seus gastos com delivery estão projetados em R$ 680 neste mês, contra uma média de R$ 430 nos últimos três meses.

## 3.4 Event Driven

Mudanças importantes no estado financeiro devem produzir eventos de domínio.

Exemplo:

```text
TransactionCreated
TransactionCategorized
BudgetThresholdReached
UnusualSpendingDetected
GoalReached
SalaryReceived
InvestmentCapacityChanged
```

Esses eventos poderão alimentar:

- notificações;
- machine learning;
- auditoria;
- analytics;
- IoT;
- impressão térmica.

## 3.5 Human-in-the-loop

Machine Learning deverá auxiliar decisões, e não esconder incerteza.

Quando a confiança de uma classificação for baixa, o sistema poderá solicitar confirmação do usuário.

Cada correção torna-se novo feedback para o modelo.

---

# 4. Stack Tecnológica

## Backend

- Java 21
- Spring Boot
- Spring Security
- Spring Data JPA
- Hibernate
- Bean Validation
- Flyway
- Gradle
- OpenAPI
- JUnit 5
- Testcontainers

## Machine Learning / Data

- Python 3
- FastAPI
- Pandas
- NumPy
- scikit-learn
- LightGBM ou XGBoost, caso necessário
- Pydantic

## Frontend

- React
- TypeScript
- Next.js
- TanStack Query
- Tailwind CSS
- PWA

## Persistência

- PostgreSQL
- Redis

## Infraestrutura

- Docker
- Docker Compose
- Cloudflare Tunnel
- Cloudflare Zero Trust
- CI/CD
- Object Storage para arquivos importados

## IoT

- ESP32
- MQTT
- OLED
- impressora térmica ESC/POS 58 mm
- NFC opcional
- encoder/botões físicos

---

# 5. Arquitetura de Alto Nível

```text
┌─────────────────────────────────────────────┐
│                CLIENT LAYER                 │
│                                             │
│        Next.js + React + TypeScript         │
│                                             │
│         Web / PWA / Mobile-ready            │
└────────────────────┬────────────────────────┘
                     │
                     │ HTTPS
                     ▼
┌─────────────────────────────────────────────┐
│         CLOUDFLARE ZERO TRUST               │
│                                             │
│ Cloudflare Tunnel                           │
│ Access Policies                             │
│ TLS                                         │
│ Rate Limiting                               │
│ WAF                                         │
└────────────────────┬────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────┐
│             JAVA 21 BACKEND                 │
│                                             │
│ Authentication                              │
│ User / Profile                              │
│ Accounts / Wallets                          │
│ Transactions                                │
│ Categories / Contexts                       │
│ Budget                                      │
│ Goals                                       │
│ Financial Insights                          │
│ Investment Planning                         │
│ Integration                                 │
│ IoT                                         │
└─────────────┬──────────────────┬────────────┘
              │                  │
              │                  │ REST
              │                  ▼
              │       ┌──────────────────────┐
              │       │ Python Intelligence  │
              │       │      Service         │
              │       │                      │
              │       │ Categorization       │
              │       │ Prediction           │
              │       │ Anomaly Detection    │
              │       │ Recommendation       │
              │       └──────────────────────┘
              │
      ┌───────┴─────────┐
      │                 │
      ▼                 ▼
┌────────────┐      ┌─────────┐
│ PostgreSQL │      │  Redis  │
└────────────┘      └─────────┘

              │
              ▼
┌─────────────────────────────────────────────┐
│              INTEGRATIONS                   │
│                                             │
│ Open Finance                                │
│ Banks                                       │
│ PIX / Statements                            │
│ OFX                                         │
│ CSV                                         │
│ Investment Market Data                      │
└─────────────────────────────────────────────┘

              │
              ▼
┌─────────────────────────────────────────────┐
│                 IoT                         │
│                                             │
│ MQTT Broker                                 │
│      │                                      │
│      ▼                                      │
│    ESP32                                    │
│      │                                      │
│ ┌────┼─────────┐                            │
│ ▼    ▼         ▼                            │
│OLED Buttons Thermal Printer                 │
└─────────────────────────────────────────────┘
```

---

# 6. Estratégia Arquitetural

A primeira versão utilizará um **Modular Monolith** no backend Java.

Não serão utilizados microserviços para cada domínio inicialmente.

Essa decisão reduz:

- complexidade operacional;
- latência;
- observabilidade distribuída;
- deployment overhead;
- gerenciamento de contratos;
- complexidade de desenvolvimento.

Entretanto, os módulos deverão possuir limites claros para permitir extração futura.

Exemplo:

```text
backend/
├── auth/
├── users/
├── accounts/
├── transactions/
├── categorization/
├── budgets/
├── goals/
├── predictions/
├── recommendations/
├── investments/
├── integrations/
├── notifications/
└── iot/
```

O serviço Python será separado desde o início porque possui características diferentes do domínio transacional.

---

# 7. Componentes

## 7.1 Authentication Module

Responsável por:

- autenticação;
- autorização;
- gerenciamento de sessões;
- refresh tokens;
- MFA;
- device sessions;
- recuperação de conta.

O backend deverá utilizar RBAC inicialmente.

Exemplo:

```text
USER
ADMIN
SERVICE
DEVICE
```

Dispositivos IoT possuirão identidade independente.

---

# 8. User Profile

O perfil financeiro deverá representar características relevantes para personalização.

Exemplo:

```json
{
  "household": {
    "livingArrangement": "WITH_FAMILY"
  },
  "relationships": [],
  "dependents": [],
  "pets": [],
  "financialGoals": [],
  "riskProfile": null
}
```

O objetivo não é armazenar informações pessoais sem necessidade, mas permitir que gastos possuam contexto financeiro.

---

# 9. Accounts & Wallets

O sistema não deverá assumir que todo saldo possui a mesma liquidez.

Tipos iniciais:

```text
CHECKING_ACCOUNT
SAVINGS_ACCOUNT
CASH
CREDIT_CARD
VA
VR
INVESTMENT_ACCOUNT
OTHER
```

Exemplo:

```text
Saldo bancário:       R$ 4.200
VA:                    R$ 700
VR:                    R$ 500
Investimentos:       R$ 8.400
```

O sistema deverá diferenciar:

```text
available_cash
restricted_balance
invested_assets
total_net_worth
```

Isso evita considerar VA/VR como capital disponível para investimento.

---

# 10. Transaction Model

Uma transação deverá conter mais informações do que apenas categoria.

Modelo conceitual:

```text
Transaction

id
userId
accountId
type
amount
currency
description
merchant
pixRecipient
category
subcategory
context
occurredAt
source
classificationConfidence
createdAt
updatedAt
```

Tipos:

```text
INCOME
EXPENSE
TRANSFER
INVESTMENT
REFUND
```

Origem:

```text
MANUAL
PIX
OPEN_FINANCE
OFX
CSV
SYSTEM
```

---

# 11. Categoria vs Contexto

O sistema separará **categoria financeira** de **contexto pessoal**.

Exemplo:

```text
Transaction

category:
FOOD

subcategory:
RESTAURANT

context:
RELATIONSHIP
```

Outro exemplo:

```text
category:
HEALTH

context:
PET
```

Isso permite análises multidimensionais.

Exemplo:

```text
Alimentação
├── Individual
├── Família
├── Relacionamento
└── Pet
```

---

# 12. PIX Intelligence

PIX será tratado como uma entidade relevante para aprendizado.

Será criado um modelo `PixRecipient`.

```text
PixRecipient

id
userId
normalizedName
documentHash
defaultCategory
defaultContext
transactionCount
averageAmount
lastTransactionAt
confidence
```

O sistema aprenderá associações.

Exemplo:

```text
PIX → Restaurante ABC

FOOD
RESTAURANT
confidence = 0.97
```

Outro:

```text
PIX → Person A

context = RELATIONSHIP
confidence = 0.89
```

---

# 13. Categorization Engine

A categorização ocorrerá em múltiplos estágios.

```text
Transaction
     │
     ▼
Normalization
     │
     ▼
Deterministic Rules
     │
     ▼
Known Merchant / Recipient
     │
     ▼
ML Classifier
     │
     ▼
Confidence Evaluation
     │
     ├── HIGH → auto classify
     │
     └── LOW → request confirmation
```

Features potenciais:

```text
description
merchant
pix recipient
amount
hour
weekday
month day
transaction frequency
historical category
historical context
account type
```

Resultado:

```json
{
  "category": "FOOD",
  "subcategory": "RESTAURANT",
  "confidence": 0.93,
  "source": "ML"
}
```

---

# 14. Feedback Loop

Quando o usuário corrige:

```text
FOOD → TRANSPORT
```

a alteração será registrada como:

```text
ClassificationFeedback
```

Contendo:

```text
transactionId
previousCategory
correctedCategory
predictionConfidence
modelVersion
timestamp
```

Esses dados formarão o dataset para evolução do modelo.

---

# 15. Budget Engine

O Budget Engine será responsável por:

- limites mensais;
- limites por categoria;
- limites por contexto;
- acompanhamento;
- projeções;
- alertas.

Exemplo:

```text
Restaurantes

Budget:    R$ 800
Spent:     R$ 640
Remaining: R$ 160

Usage:
████████░░
80%
```

Eventos:

```text
Budget50Reached
Budget80Reached
Budget100Reached
BudgetExceeded
```

---

# 16. Prediction Engine

O Prediction Engine estimará o estado financeiro futuro.

Entrada:

```text
saldo atual
+
receitas previstas
-
despesas recorrentes
-
despesas variáveis previstas
```

Saída:

```text
projected_balance
projected_expenses
expected_savings
investment_capacity
risk_of_budget_overflow
```

Horizontes iniciais:

```text
7 dias
30 dias
fim do mês
próximo salário
90 dias
```

A primeira implementação poderá utilizar estatística simples.

Posteriormente:

```text
Moving Average
Exponential Smoothing
Gradient Boosting
Time Series Models
```

A complexidade deverá ser adicionada somente quando houver evidência de melhoria significativa.

---

# 17. Anomaly Detection

O sistema deverá detectar desvios relevantes do comportamento histórico.

Exemplo:

```text
Delivery

historical average = R$ 420
current projection = R$ 680

variation = +61.9%
```

Possíveis sinais:

```text
amount anomaly
frequency anomaly
category anomaly
merchant anomaly
cash-flow anomaly
```

---

# 18. Recommendation Engine

O Recommendation Engine transformará métricas em ações.

Pipeline:

```text
Financial Data
      ↓
Metrics
      ↓
Pattern Detection
      ↓
Opportunity Detection
      ↓
Recommendation
      ↓
Explanation
```

Uma recomendação deverá possuir estrutura própria.

```json
{
  "type": "REDUCE_EXPENSE",
  "category": "DELIVERY",
  "impact": 230,
  "confidence": 0.88,
  "reason": {
    "historicalAverage": 420,
    "currentProjection": 680
  }
}
```

A apresentação em linguagem natural poderá posteriormente ser realizada por um LLM.

O LLM não deverá ser a fonte dos cálculos financeiros.

---

# 19. Investment Engine

O Investment Engine inicialmente será um mecanismo de planejamento financeiro.

Pipeline:

```text
Income
   ↓
Essential Expenses
   ↓
Debt
   ↓
Emergency Reserve
   ↓
Goals
   ↓
Risk Profile
   ↓
Investment Capacity
```

A primeira versão não terá como objetivo recomendar diretamente ativos individuais.

O foco será responder:

```text
Quanto posso investir?

Tenho reserva suficiente?

Quanto falta para minha meta?

Qual minha capacidade sustentável de aporte?
```

---

# 20. Financial Twin

Uma evolução futura será a criação de um **Financial Twin**.

O Financial Twin representa uma simulação do comportamento financeiro do usuário.

Permitirá responder perguntas como:

```text
E se eu morar sozinho?

E se meu aluguel for R$ 1.800?

E se meu salário aumentar 20%?

E se eu comprar um carro?

E se eu investir R$ 1.000/mês?

E se eu dividir despesas com outra pessoa?
```

Pipeline:

```text
Current Financial State
          +
Scenario Parameters
          ↓
Simulation Engine
          ↓
Projected Cash Flow
          ↓
Projected Savings
          ↓
Projected Net Worth
```

---

# 21. Event Architecture

Eventos de domínio deverão ser utilizados para desacoplar ações secundárias.

Exemplo:

```text
TransactionCreated
        │
        ├── Categorization
        │
        ├── Budget Update
        │
        ├── Prediction Update
        │
        └── Anomaly Detection
```

Eventos principais:

```text
TransactionCreated
TransactionUpdated
TransactionCategorized

BudgetThresholdReached
BudgetExceeded

UnusualSpendingDetected

IncomeReceived
SalaryReceived

GoalProgressUpdated
GoalReached

PredictionUpdated

RecommendationCreated

InvestmentCapacityChanged

DeviceConnected
PrintRequested
```

Inicialmente, eventos poderão ser internos ao monólito.

Redis poderá ser utilizado para:

- cache;
- jobs;
- rate limiting;
- eventos temporários.

Uma evolução futura poderá introduzir RabbitMQ, NATS ou Kafka caso exista necessidade real.

---

# 22. IoT Architecture

O sistema deverá suportar dispositivos financeiros físicos.

Arquitetura:

```text
Financial Backend
       │
       │ event
       ▼
IoT Gateway
       │
       │ MQTT
       ▼
MQTT Broker
       │
       ▼
ESP32
       │
 ┌─────┼─────────┐
 ▼     ▼         ▼
OLED  Buttons   Printer
```

O ESP32 nunca terá acesso direto ao PostgreSQL.

Toda comunicação ocorrerá por APIs ou mensagens autorizadas.

---

# 23. Thermal Printer

A impressora térmica poderá produzir:

```text
DailySummary
WeeklySummary
MonthlySummary
BudgetWarning
GoalReached
UnusualSpending
InvestmentCapacity
```

Exemplo:

```text
================================
       FINANCIAL COPILOT
================================

Resumo semanal

Gastos
R$ 842,50

Maior categoria
Alimentação
R$ 284,00

--------------------------------

PREVISÃO DO MÊS

Saldo projetado
R$ 1.420

--------------------------------

INSIGHT

Delivery está 27% acima
da sua média.

Economia potencial
R$ 120 / mês

================================
```

---

# 24. IoT Event Priority

Nem todo evento financeiro deverá gerar impressão.

Prioridades:

```text
LOW
→ somente dashboard

NORMAL
→ dashboard + notification

HIGH
→ dashboard + device display

CRITICAL
→ display + optional print
```

O objetivo é evitar notification fatigue e desperdício de papel.

---

# 25. MQTT

Estrutura conceitual:

```text
finance/{userId}/device/{deviceId}/command
finance/{userId}/device/{deviceId}/status
finance/{userId}/device/{deviceId}/event
```

Mensagem:

```json
{
  "eventId": "uuid",
  "type": "PRINT_SUMMARY",
  "timestamp": "ISO-8601",
  "payload": {
    "period": "WEEKLY"
  }
}
```

Cada dispositivo deverá possuir credenciais próprias.

---

# 26. API Design

A API pública será inicialmente REST.

Prefixo:

```text
/api/v1
```

Endpoints conceituais:

```text
POST   /auth/login
POST   /auth/refresh

GET    /users/me
PATCH  /users/me

GET    /accounts
POST   /accounts

GET    /transactions
POST   /transactions
PATCH  /transactions/{id}

POST   /transactions/import

GET    /budgets
POST   /budgets

GET    /goals
POST   /goals

GET    /predictions
GET    /recommendations

GET    /investments/summary

GET    /devices
POST   /devices
POST   /devices/{id}/print
```

GraphQL poderá ser avaliado posteriormente caso exista necessidade comprovada.

REST será a abordagem padrão para o MVP.

---

# 27. Comunicação Java ↔ Python

O backend Java será a autoridade sobre o domínio financeiro.

Python será responsável por computação analítica.

```text
Java Backend
      │
      │ Internal REST
      ▼
Python ML Service
```

Exemplo:

```text
POST /internal/v1/classify
```

Request:

```json
{
  "transactionId": "uuid",
  "description": "PIX MERCADO ABC",
  "amount": 84.90,
  "occurredAt": "2026-08-31T18:42:00"
}
```

Response:

```json
{
  "category": "FOOD",
  "subcategory": "GROCERIES",
  "confidence": 0.94,
  "modelVersion": "categorizer-1.3.0"
}
```

Python não deverá acessar tabelas de negócio diretamente quando isso puder ser evitado.

Java permanece como proprietário dos dados transacionais.

---

# 28. PostgreSQL

Entidades principais:

```text
users

profiles

accounts
wallets

transactions
transaction_categories
transaction_contexts

merchants
pix_recipients

classification_feedback

budgets
budget_entries

goals

predictions

financial_insights
recommendations

investment_accounts
investment_positions

devices
device_events

audit_logs
```

IDs deverão utilizar UUID.

Valores monetários não deverão utilizar `float` ou `double`.

Java:

```text
BigDecimal
```

PostgreSQL:

```text
NUMERIC
```

---

# 29. Redis

Redis poderá ser utilizado para:

```text
session cache
rate limiting
distributed locks
prediction cache
dashboard cache
temporary jobs
IoT device state
```

Redis não será considerado fonte primária de dados financeiros.

---

# 30. Importação Financeira

Pipeline:

```text
OFX / CSV / Open Finance
          ↓
Raw Import
          ↓
Validation
          ↓
Normalization
          ↓
Deduplication
          ↓
Transaction Creation
          ↓
Categorization
          ↓
Budget Processing
          ↓
Prediction
```

Cada importação deverá possuir um identificador.

Isso permitirá auditoria e rollback lógico.

---

# 31. Deduplicação

Uma mesma transação pode chegar através de diferentes fontes.

Exemplo:

```text
Open Finance
+
CSV importado manualmente
```

O sistema deverá detectar potenciais duplicatas utilizando:

```text
account
amount
timestamp
transaction identifier
merchant
PIX identifier
description hash
```

---

# 32. Segurança

O sistema adotará uma estratégia Zero Trust.

```text
Internet
   │
   ▼
Cloudflare
   │
   ├── TLS
   ├── WAF
   ├── Rate Limit
   ├── Access Policy
   └── Tunnel
        │
        ▼
Private Docker Network
```

Nenhuma porta administrativa deverá precisar ficar diretamente exposta à Internet.

---

# 33. Cloudflare Tunnel

Serviços poderão ser publicados através de Cloudflare Tunnel.

Exemplo:

```text
app.example.com
        │
        ▼
Cloudflare Edge
        │
        ▼
Cloudflare Tunnel
        │
        ▼
frontend:3000
```

API:

```text
api.example.com
        │
        ▼
Cloudflare
        │
        ▼
backend:8080
```

Painéis administrativos poderão exigir Cloudflare Access.

---

# 34. Docker Architecture

Ambiente inicial:

```text
docker-compose.yml

services:

frontend
backend
ml-service
postgres
redis
mqtt
cloudflared
```

Representação:

```text
┌──────────────────────── Docker Network ───────────────────────┐
│                                                              │
│ frontend                                                     │
│     │                                                        │
│     ▼                                                        │
│ backend ───────────────► postgres                            │
│     │                     redis                              │
│     │                                                        │
│     └──────────────────► ml-service                          │
│                                                              │
│ backend ───────────────► mqtt                                │
│                                                              │
│ cloudflared                                                  │
│     │                                                        │
└─────┼────────────────────────────────────────────────────────┘
      │
      ▼
Cloudflare
```

---

# 35. Secrets

Secrets não deverão existir:

```text
no código
no Dockerfile
no Git
no frontend
```

Deverão ser injetados através do ambiente ou secret manager.

Exemplos:

```text
DATABASE_PASSWORD
JWT_PRIVATE_KEY
OPEN_FINANCE_SECRET
ML_SERVICE_TOKEN
MQTT_PASSWORD
```

---

# 36. LGPD

O projeto deverá considerar:

- minimização de dados;
- consentimento;
- finalidade;
- exclusão de conta;
- exportação dos dados;
- logs de acesso;
- criptografia;
- retenção;
- anonimização para ML.

Dados utilizados para treinamento deverão preferencialmente ser pseudonimizados.

---

# 37. Observabilidade

Todos os componentes deverão produzir logs estruturados.

Campos mínimos:

```text
timestamp
service
level
requestId
traceId
userIdHash
event
duration
```

Nunca registrar:

```text
senhas
tokens
credenciais bancárias
dados financeiros completos desnecessários
```

Evolução futura:

```text
OpenTelemetry
Prometheus
Grafana
Loki
```

---

# 38. Testing Strategy

## Backend

```text
Unit Tests
Integration Tests
Repository Tests
API Tests
Security Tests
```

Testcontainers deverá fornecer:

```text
PostgreSQL
Redis
```

durante testes de integração.

## Python

```text
unit tests
model evaluation
dataset validation
regression tests
```

Modelos deverão possuir métricas versionadas.

## Frontend

```text
component tests
integration tests
E2E
```

## IoT

Testes deverão validar:

```text
MQTT reconnect
offline behavior
duplicate events
invalid messages
printer unavailable
Wi-Fi loss
```

---

# 39. Resiliência

Operações críticas deverão ser idempotentes.

Principalmente:

```text
transaction imports
Open Finance sync
event processing
IoT commands
printing
```

Todo evento deverá possuir:

```text
eventId
```

O consumidor poderá registrar IDs já processados para evitar execução duplicada.

---

# 40. Estrutura de Repositório

Inicialmente poderá ser utilizado um monorepo:

```text
financial-copilot/

├── apps/
│   ├── web/
│   │   └── Next.js
│   │
│   ├── backend/
│   │   └── Java 21 + Spring Boot
│   │
│   └── intelligence/
│       └── Python + FastAPI
│
├── firmware/
│   └── esp32/
│
├── infrastructure/
│   ├── docker/
│   ├── cloudflare/
│   └── mqtt/
│
├── docs/
│   ├── SDD.md
│   ├── architecture/
│   ├── adr/
│   └── api/
│
├── datasets/
│   └── synthetic/
│
├── docker-compose.yml
└── README.md
```

---

# 41. ADRs

Decisões arquiteturais importantes deverão possuir Architecture Decision Records.

Exemplos:

```text
ADR-001 — Modular Monolith instead of Microservices

ADR-002 — PostgreSQL as Source of Truth

ADR-003 — Java owns Financial Domain

ADR-004 — Python isolated for ML workloads

ADR-005 — REST for MVP API

ADR-006 — MQTT for IoT communication

ADR-007 — Cloudflare Zero Trust ingress

ADR-008 — BigDecimal for monetary values

ADR-009 — Categories separated from Context

ADR-010 — ML recommendations must be explainable
```

---

# 42. MVP

O MVP deverá validar primeiro o núcleo financeiro.

### Milestone 1 — Foundation

```text
Docker Compose
PostgreSQL
Redis
Java Backend
Next.js
Authentication
CI
```

### Milestone 2 — Financial Core

```text
Accounts
Wallets
Transactions
Categories
Contexts
Manual transaction creation
```

### Milestone 3 — Imports

```text
CSV
OFX
Normalization
Deduplication
```

### Milestone 4 — PIX Intelligence

```text
PIX recipient detection
Rules
Automatic categorization
User corrections
Feedback storage
```

### Milestone 5 — Financial Dashboard

```text
Current balance
Monthly spending
Category distribution
Context distribution
VA/VR
Cash availability
```

### Milestone 6 — Intelligence

```text
Python service
Prediction engine
Anomaly detection
Financial insights
```

### Milestone 7 — Goals

```text
Budgets
Emergency reserve
Goals
Investment capacity
```

### Milestone 8 — IoT

```text
MQTT
ESP32
OLED
Thermal printer
Device authentication
Weekly receipt
```

### Milestone 9 — Financial Twin

```text
Scenario engine
Cash-flow simulation
Goal simulation
Net-worth projection
```

---

# 43. Exemplo de Fluxo Completo

Usuário realiza:

```text
PIX R$ 86
```

Fluxo:

```text
Bank / OFX
     │
     ▼
Transaction Import
     │
     ▼
Normalization
     │
     ▼
TransactionCreated
     │
     ├──────────────► Budget Engine
     │
     ├──────────────► Categorization Engine
     │
     └──────────────► Prediction Engine
                           │
                           ▼
                    Python ML Service
                           │
                           ▼
                     Classification
                           │
                           ▼
                   FOOD / RESTAURANT
                   confidence 94%
                           │
                           ▼
                 TransactionCategorized
                           │
             ┌─────────────┼──────────────┐
             ▼             ▼              ▼
          Budget        Insights      Prediction
             │             │              │
             └─────────────┼──────────────┘
                           ▼
                    Dashboard Update
```

Caso o evento seja relevante:

```text
Budget 80% reached
        │
        ▼
FinancialEvent
        │
        ▼
IoT Gateway
        │
        ▼
MQTT
        │
        ▼
ESP32
        │
        ├── OLED
        └── Thermal Printer
```

---

# 44. Exemplo de Experiência Física

```text
================================
          FINANCE OS
================================

01/09/2026

SALDO LIVRE
R$ 2.840,00

GASTOS DA SEMANA
R$ 684,30

--------------------------------

ALIMENTAÇÃO        R$ 240
TRANSPORTE         R$ 126
LAZER              R$ 118
OUTROS             R$ 200

--------------------------------

! ATENÇÃO

Restaurantes chegaram a
82% do orçamento mensal.

Restante:
R$ 144

--------------------------------

PREVISÃO

Saldo no fim do mês:
R$ 1.380

Capacidade de aporte:
R$ 620

================================
```

O papel funciona como uma interface física deliberada: somente informações relevantes deverão atravessar a fronteira digital e virar impressão.

---

# 45. Visão Futura

A arquitetura deverá permitir que o produto evolua de um tracker financeiro para um sistema de inteligência financeira pessoal.

```text
                 FINANCIAL COPILOT

                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼

     OBSERVE         PREDICT         ADVISE

        │              │              │

 Transactions       Cash Flow      Recommendations
 Behavior           Spending       Investments
 Budgets            Goals          Opportunities

        └──────────────┬──────────────┘
                       ▼

                    SIMULATE

                       │

                Financial Twin

                       │
                       ▼

                     ACT

                       │

          App / Notifications / IoT
```

O objetivo final não é simplesmente registrar:

> "Você gastou R$ 86."

O sistema deverá ser capaz de responder:

> "Você gastou R$ 86. Esse gasto pertence a alimentação no contexto relacionamento. Seus gastos nessa categoria estão 18% acima da média recente. Mantendo esse ritmo, você terminará o mês aproximadamente R$ 240 abaixo da sua meta. Reduzir uma saída nas próximas duas semanas provavelmente será suficiente para voltar ao planejamento."

Esse ciclo — **observar → entender → prever → recomendar → agir → aprender** — constitui o núcleo do produto.