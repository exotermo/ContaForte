# Operação

Guia para instalar, operar e monitorar o ContaForte. Tudo roda em Docker, em redes isoladas, e é controlado pelo CLI `./cf`.

## Quickstart

Pré-requisitos: Docker com Compose v2 e cerca de 4 GB de RAM livre. Não é preciso Java, Node nem Go no host: os builds acontecem em containers (o Go só é baixado em container se não existir localmente).

```bash
./cf init     # verifica pré-requisitos e gera o .env com segredos aleatórios
./cf up       # constrói e sobe a stack, esperando todos os serviços ficarem saudáveis
./cf open     # abre http://127.0.0.1:8080
```

O administrador inicial é `ADMIN_EMAIL` (padrão `admin@contaforte.local`) com a senha de `ADMIN_PASSWORD` no `.env`.

## Comandos do `cf`

| Comando | O que faz |
|---|---|
| `init [--force] [--admin-email E]` | Gera o `.env` (permissão 600) sem sobrescrever o que já existe. `--force` regenera tudo e pede confirmação |
| `up` / `down` / `build` | Sobe, derruba e constrói imagens |
| `reset` | Derruba e **apaga os volumes** (pede confirmação) |
| `status` | Estado, saúde e consumo de cada serviço |
| `doctor` | Segredos, RAM, redes internas, portas expostas, alvos do Prometheus e logs no Loki |
| `logs [serviço]` | Acompanha os logs |
| `open [app\|grafana]` | Abre o app ou o Grafana |
| `test [backend\|web\|go\|all]` | Testes em container |
| `e2e` | Testes de navegador (Playwright) contra a stack em execução |
| `backup` / `restore <arquivo>` | `pg_dump` comprimido em `backups/` e restauração |

## Rede e isolamento

| Rede | Tipo | Serviços |
|---|---|---|
| `edge` | bridge | `gateway`, `cloudflared` (opcional) |
| `app` | internal (sem saída à Internet) | backend, web, Postgres, Redis, MQTT, gateway |
| `obs` | internal | Prometheus, Grafana, Loki, Alloy, exporters, gateway |

Só o `gateway` publica porta, e apenas em `127.0.0.1:8080`. O `./cf doctor` confere isso.

## Observabilidade

Grafana em `http://127.0.0.1:8080/grafana/` (usuário `admin`, senha `GRAFANA_PASSWORD` do `.env`). Três dashboards vêm provisionados como código (Visão geral, PostgreSQL, Redis). Prometheus guarda 15 dias de métricas e o Loki 7 dias de logs. Decisões em [ADR-012](../02_Arquitetura/Arquitetura/ADR/012-observabilidade-com-stack-grafana-e-docker-proxy.md).

- **Memória:** os limites dos containers somam cerca de 3,7 GB. O uso real fica em torno de 1,3 GB.
- **Sem Alertmanager:** os alertas só aparecem no Prometheus e no Grafana, não notificam ninguém.
- **Postgres:** o `postgres-exporter` usa o mesmo usuário da aplicação. Um usuário dedicado com o papel `pg_monitor` é um endurecimento futuro.
- **Dashboards como código:** o gerador está em `tools/gen-dashboards`. Edite lá e regenere os JSON.

## Exposição pública com Cloudflare Tunnel (opcional)

Por padrão nada é exposto à Internet. Para publicar o app sem abrir portas:

1. No painel Cloudflare Zero Trust → Networks → Tunnels, crie um túnel e copie o token.
2. Em *Public hostname*, aponte seu domínio para `http://gateway:8081` (**8081**, não 80).
3. No `.env`, adicione `COMPOSE_PROFILES=tunnel` e `TUNNEL_TOKEN=<token>`.
4. `./cf up` e depois `./cf doctor`.

O Grafana **não** é publicado pelo túnel. Para proteger o app público, configure no painel do Cloudflare: Access (login por e-mail/SSO), regras de WAF e rate limit de borda. Detalhes em [ADR-007](../02_Arquitetura/Arquitetura/ADR/007-cloudflare-zero-trust-no-ingresso.md).

## Backup e restauração

```bash
./cf backup                          # backups/contaforte-AAAAMMDD-HHMMSS.sql.gz
./cf restore backups/<arquivo>.sql.gz
```

Os backups ficam fora do Git (`backups/` ignorado). Guarde uma cópia fora da máquina.

## Integração contínua

O workflow `.github/workflows/ci.yml` roda a cada push na `main` e em PRs: Go (gofmt, vet, testes), backend (Gradle + Testcontainers), frontend (lint, tipos, testes e build), stack completa (`./cf init` + `./cf up` + login real + Grafana protegido) e varredura de segredos com gitleaks sobre todo o histórico.
