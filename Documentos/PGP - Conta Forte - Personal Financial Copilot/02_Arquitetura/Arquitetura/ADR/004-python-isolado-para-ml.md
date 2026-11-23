# ADR-004 — Python isolado para cargas de ML

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
O ML tem ciclo de vida, dependências e perfil de carga diferentes do domínio transacional.

## Decisão
Serviço FastAPI separado, chamado por REST interno autenticado, sem acesso direto às tabelas de negócio.

## Consequências
Ver o [SDD](../../SDD/SDD_Personal_Financial_Copilot.md) para o detalhamento. Revisitar esta decisão exige um novo ADR que a substitua.
