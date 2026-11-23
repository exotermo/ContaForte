# ADR-012 — Observabilidade com Prometheus, Grafana, Loki e proxy somente-leitura do Docker

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
O sistema precisa de métricas, logs e painéis desde o Milestone 1, rodando na mesma máquina do restante da stack, sem expor portas e sem abrir caminhos de privilégio para quem comprometer um container de monitoramento.

## Decisão
- **Métricas:** Prometheus (retenção de 15 dias) coleta o backend (Actuator/Micrometer, porta de gestão 8081), o `postgres-exporter`, o `redis-exporter`, o `container-exporter`, o Loki, o Alloy e o próprio Grafana. Há regras de alerta básicas (alvo fora do ar, 5xx, Postgres/Redis indisponíveis, container acima de 90% do limite de memória). Alertmanager fica para quando houver um canal de notificação.
- **Logs:** Grafana **Alloy** coleta os logs dos containers e envia ao **Loki** (retenção de 7 dias). Escolhido no lugar do Promtail, que foi descontinuado. Os logs do backend são JSON (ECS), e o nível vira label filtrável.
- **Painéis:** Grafana com datasources e dashboards **provisionados como código** (`infrastructure/monitoring/grafana`), somente leitura. Acesso por `/grafana/` no gateway (127.0.0.1), com login obrigatório.
- **Rede:** tudo na rede `obs` (`internal`, sem saída à Internet). O backend e os exporters também entram na `obs` para serem coletados.
- **Acesso ao Docker:** nada monta o `docker.sock`. Um `docker-socket-proxy` expõe só `GET` de containers, imagens, redes, info e eventos (`POST=0`), e é a única peça que enxerga o socket.
- **Métricas por container:** um **`container-exporter` próprio** (Go, ~150 linhas, sem dependências, imagem `scratch` não-root) lê o proxy e publica CPU, memória e rede com os mesmos nomes de métrica do cAdvisor.

## Alternativas descartadas
- **cAdvisor:** a versão atual só enxerga containers do Docker com acesso ao `docker.sock` **e** ao socket do containerd, o que é equivalente a root no host. Atrás do proxy ele só vê o cgroup raiz.
- **Promtail:** fim de vida.
- **Stack sem métricas por container:** perderia o acompanhamento dos limites de memória, que importa com a stack completa ligada.

## Consequências
- Mais um componente para manter (`tools/container-exporter`), mas coberto por testes e com superfície mínima.
- O Loki usa imagem sem shell, então não tem healthcheck do Docker. O `cf doctor` valida o alvo `loki` no Prometheus e a chegada de logs.
- O `postgres-exporter` usa o mesmo usuário da aplicação. Um usuário dedicado com o papel `pg_monitor` é um endurecimento futuro.
- Sem Alertmanager, os alertas só aparecem no Prometheus/Grafana.
- Com a stack completa, os limites somam cerca de 3,7 GB e o uso real fica em torno de 1,3 GB.
