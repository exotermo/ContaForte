# ADR-002 — PostgreSQL como fonte da verdade

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
Consistência transacional e auditoria. O Redis pode ser perdido sem perda de dados.

## Decisão
Todo dado financeiro persistente fica no PostgreSQL. O Redis é só cache, jobs, rate limit e locks.

## Consequências
Ver o [SDD](../../SDD/SDD_Personal_Financial_Copilot.md) para o detalhamento. Revisitar esta decisão exige um novo ADR que a substitua.
