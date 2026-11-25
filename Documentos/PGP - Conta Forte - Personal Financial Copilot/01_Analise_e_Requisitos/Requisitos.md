# Requisitos

Derivados do [SDD](../02_Arquitetura/SDD/SDD_Personal_Financial_Copilot.md). Prioridade: M1–M5 (MVP).

## Funcionais

| ID | Milestone | Requisito | Estado |
|---|---|---|---|
| RF-01 | M1 | O usuário se autentica com login e refresh de token | ✅ v0.1.0 |
| RF-02 | M1 | O sistema aplica RBAC (USER, ADMIN) | ✅ v0.1.0 |
| RF-03 | M2 | O usuário cria contas e carteiras dos tipos CHECKING, SAVINGS, CASH, CREDIT_CARD, VA, VR, INVESTMENT e OTHER | ✅ v0.2.0 |
| RF-04 | M2 | O usuário registra transações (INCOME, EXPENSE, TRANSFER, INVESTMENT, REFUND) com categoria, subcategoria e contexto | ✅ v0.2.0 |
| RF-05 | M2 | O sistema calcula available_cash, restricted_balance, invested_assets e total_net_worth | ✅ v0.2.0 |
| RF-06 | M3 | O usuário importa OFX e CSV, e cada importação tem um ID e permite rollback lógico | ✅ |
| RF-07 | M3 | O sistema deduplica transações por conta, valor, data, identificador, descrição e PIX | ✅ |
| RF-08 | M4 | O sistema mantém `PixRecipient` com categoria e contexto padrão | ✅ v0.4.0 |
| RF-09 | M4 | O sistema categoriza por regras e favorecidos conhecidos e, com baixa confiança, pede confirmação | ✅ v0.4.0 |
| RF-10 | M4 | Cada correção do usuário é gravada como `ClassificationFeedback` | ✅ v0.4.0 |
| RF-11 | M5 | O dashboard mostra saldo, gastos mensais, distribuição por categoria e contexto, VA/VR e caixa disponível | ⏳ |
| RF-12 | M6 | Previsão de saldo (7d, 30d, fim do mês, 90d) e detecção de anomalias | ⏳ |
| RF-13 | M7 | Orçamentos por categoria e contexto com alertas de 50%, 80% e 100% | ⏳ |
| RF-14 | M7 | Metas, reserva de emergência e capacidade de investimento | ⏳ |
| RF-15 | M8 | Dispositivos IoT com identidade própria, exibição no OLED e impressão de resumos | ⏳ |
| RF-16 | M9 | Simulação de cenários (Financial Twin) | ⏳ |

## Não funcionais

| ID | Requisito | Estado |
|---|---|---|
| RNF-01 | Valores monetários em `BigDecimal`/`NUMERIC`, nunca em ponto flutuante | ✅ NUMERIC/BigDecimal no backend; string decimal na API e no front |
| RNF-02 | IDs em UUID | ✅ |
| RNF-03 | Operações críticas (importação, eventos, IoT) são idempotentes | ⏳ importação (M3) |
| RNF-04 | Toda recomendação traz evidências, e o LLM não faz cálculos | ⏳ M6 |
| RNF-05 | Logs estruturados, sem senhas, tokens ou dados financeiros desnecessários | ✅ logs JSON; testes conferem que valor e descrição não vazam |
| RNF-06 | Segredos fora do código, do Git e do frontend | ✅ `.env` 600, fora do Git; gitleaks no CI |
| RNF-07 | LGPD: minimização, consentimento, exportação e exclusão, pseudonimização para ML | 🔶 exclusão em cascata do usuário; exportação e consentimento pendentes |
| RNF-08 | Sem portas administrativas expostas à Internet (Cloudflare Tunnel) | ✅ só `127.0.0.1`; túnel Cloudflare opcional |
| RNF-09 | Testes: unitários, integração (Testcontainers) e E2E, rodando no CI | ✅ unitários, integração, E2E e CI |
| RNF-10 | Ambiente reproduzível com `docker compose up` | ✅ `./cf init && ./cf up` |

✅ atendido · 🔶 parcial · ⏳ pendente. Evidências em [Testes](../04_Testes/README.md) e nas lições aprendidas de cada milestone.
