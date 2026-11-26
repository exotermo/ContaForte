# ADR-020 — Núcleo enxuto, com monitoramento e MQTT em perfis opcionais

**Status:** Aceito · **Data:** 2026-11-25

## Contexto
A meta é a stack caber em **1 GB de RAM** num servidor doméstico modesto. Na medição com os limites antigos (somados, 3680 MiB), a stack completa chegou a **1275 MiB** logo após subir e a **1603 MiB** sob carga, contando o swap. O núcleo (backend, web, Postgres, Redis e gateway) ficou perto de 500 MiB parado e 760 MiB no pico (soma dos picos de cada serviço). O restante é, quase todo, monitoramento.

## Decisão
- **Sem perfil, sobe só o núcleo.** É ele que precisa caber no 1 GB (junto com o túnel, quando usado).
- **`obs`:** Prometheus, Loki, Alloy, Grafana, exporters e docker-proxy. Liga sob demanda com `./cf obs up` e desliga com `./cf obs down`, sem recriar o núcleo e sem apagar o histórico; para ficar sempre ligado, `COMPOSE_PROFILES=obs`. Ligado, a stack passa de 1 GB, e isso é aceito.
- **`iot`:** o MQTT, que nenhuma parte da aplicação usa ainda.
- **O Redis continua no núcleo:** gasta pouco e sustenta os limites de tentativas de login e de importação, que falham fechados.
- **Rotação de logs em todos os serviços** (3 arquivos de 10 MB). Sem monitoramento, os logs do Docker são o que resta para investigar, e sem limite enchem o disco.
- **`./cf down` e `./cf reset` incluem todos os perfis**, porque o Compose ignora serviços de perfis inativos e os deixaria de pé.

## Alternativas descartadas
- **Trocar o monitoramento por ferramentas menores:** exigiria refazer painéis e consultas e perderia o que já funciona.
- **Só o coletor no servidor e o painel fora dele:** ainda ocupa memória o tempo todo.

## Consequências
- No padrão não há métricas nem logs centralizados; o diagnóstico usa `./cf status`, `./cf mem` e `./cf logs`. O que aconteceu enquanto o `obs` estava desligado não aparece nos painéis, salvo os logs ainda guardados pelo Docker que o Alloy conseguir ler ao ligar.
- Quem já tinha a stack no ar precisa saber que o `up` não liga mais o monitoramento.
- Um teste exige que o núcleo seja exatamente os cinco serviços: um serviço novo precisa caber no orçamento ou ir para um perfil.
- Os limites de memória de cada serviço estão no [ADR-021](021-orcamento-de-memoria-de-1-gb.md).
