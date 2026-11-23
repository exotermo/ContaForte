# ADR-006 — MQTT para comunicação IoT

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
Leve e adequado ao ESP32. Pub/sub desacopla o backend dos dispositivos.

## Decisão
Dispositivos usam MQTT com credenciais próprias, e os tópicos seguem `finance/{userId}/device/{deviceId}/...`.

## Consequências
Ver o [SDD](../../SDD/SDD_Personal_Financial_Copilot.md) para o detalhamento. Revisitar esta decisão exige um novo ADR que a substitua.
