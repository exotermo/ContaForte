# ADR-008 — BigDecimal para valores monetários

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
Evita erros de arredondamento em dinheiro.

## Decisão
Java usa `BigDecimal` e o PostgreSQL usa `NUMERIC`. Nunca `float` ou `double`.

## Consequências
Ver o [SDD](../../SDD/SDD_Personal_Financial_Copilot.md) para o detalhamento. Revisitar esta decisão exige um novo ADR que a substitua.
