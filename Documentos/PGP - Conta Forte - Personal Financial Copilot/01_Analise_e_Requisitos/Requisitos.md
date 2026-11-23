# Requisitos

Derivados do [SDD](../02_Arquitetura/SDD/SDD_Personal_Financial_Copilot.md). Prioridade: M1–M5 (MVP).

## Funcionais

| ID | Milestone | Requisito |
|---|---|---|
| RF-01 | M1 | O usuário se autentica com login e refresh de token |
| RF-02 | M1 | O sistema aplica RBAC (USER, ADMIN) |
| RF-03 | M2 | O usuário cria contas e carteiras dos tipos CHECKING, SAVINGS, CASH, CREDIT_CARD, VA, VR, INVESTMENT e OTHER |
| RF-04 | M2 | O usuário registra transações (INCOME, EXPENSE, TRANSFER, INVESTMENT, REFUND) com categoria, subcategoria e contexto |
| RF-05 | M2 | O sistema calcula available_cash, restricted_balance, invested_assets e total_net_worth |
| RF-06 | M3 | O usuário importa OFX e CSV, e cada importação tem um ID e permite rollback lógico |
| RF-07 | M3 | O sistema deduplica transações por conta, valor, data, identificador, descrição e PIX |
| RF-08 | M4 | O sistema mantém `PixRecipient` com categoria e contexto padrão |
| RF-09 | M4 | O sistema categoriza por regras e favorecidos conhecidos e, com baixa confiança, pede confirmação |
| RF-10 | M4 | Cada correção do usuário é gravada como `ClassificationFeedback` |
| RF-11 | M5 | O dashboard mostra saldo, gastos mensais, distribuição por categoria e contexto, VA/VR e caixa disponível |
| RF-12 | M6 | Previsão de saldo (7d, 30d, fim do mês, 90d) e detecção de anomalias |
| RF-13 | M7 | Orçamentos por categoria e contexto com alertas de 50%, 80% e 100% |
| RF-14 | M7 | Metas, reserva de emergência e capacidade de investimento |
| RF-15 | M8 | Dispositivos IoT com identidade própria, exibição no OLED e impressão de resumos |
| RF-16 | M9 | Simulação de cenários (Financial Twin) |

## Não funcionais

| ID | Requisito |
|---|---|
| RNF-01 | Valores monetários em `BigDecimal`/`NUMERIC`, nunca em ponto flutuante |
| RNF-02 | IDs em UUID |
| RNF-03 | Operações críticas (importação, eventos, IoT) são idempotentes |
| RNF-04 | Toda recomendação traz evidências, e o LLM não faz cálculos |
| RNF-05 | Logs estruturados, sem senhas, tokens ou dados financeiros desnecessários |
| RNF-06 | Segredos fora do código, do Git e do frontend |
| RNF-07 | LGPD: minimização, consentimento, exportação e exclusão, pseudonimização para ML |
| RNF-08 | Sem portas administrativas expostas à Internet (Cloudflare Tunnel) |
| RNF-09 | Testes: unitários, integração (Testcontainers) e E2E, rodando no CI |
| RNF-10 | Ambiente reproduzível com `docker compose up` |
