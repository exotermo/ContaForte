# Operação

Guia para instalar, operar e monitorar o ContaForte. Tudo roda em Docker, em redes isoladas, e é controlado pelo CLI `./cf`.

## Quickstart

Pré-requisitos: Docker com Compose v2 e cerca de 4 GB de RAM livre. Não é preciso Java, Node nem Go no host: os builds acontecem em containers (o Go só é baixado em container se não existir localmente).

```bash
./cf init     # verifica pré-requisitos e gera o .env com segredos aleatórios
./cf up       # constrói e sobe a stack, esperando todos os serviços ficarem saudáveis
./cf open     # abre http://127.0.0.1:8080
./cf seed     # opcional: dados FICTÍCIOS de demonstração (veja abaixo)
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
| `seed [--reset]` | Carrega 90 dias de dados **fictícios** num usuário demo e confere o resumo no final |
| `bench` | Mede o p95 das telas com 10 mil transações sintéticas (k6 em container) |
| `backup` / `restore <arquivo>` | `pg_dump` comprimido em `backups/` e restauração |

## Dados de demonstração (`./cf seed`)

Para ver o produto com números sem digitar nada: `./cf seed` cria o usuário `demo@contaforte.local` (senha aleatória gerada uma vez e guardada em `DEMO_PASSWORD` no `.env`) e carrega, pela API (com todas as regras de negócio), 7 contas, 154 transações dos últimos 90 dias, uma de cada tipo de conta e de transação. No final, o `cf` compara o resumo calculado pelo backend com o resultado esperado pelo gerador e falha se divergirem.

- Tudo é **fictício** e determinístico (`datasets/synthetic/scenario.json`, gerado por `tools/gen-synthetic`, semente fixa). Nenhum dado real entra no repositório.
- Rodar de novo recusa se o usuário demo já tem dados. `./cf seed --reset` apaga **só** o usuário demo (e os dados dele, em cascata) e recarrega.
- **Só em ambiente local:** o comando recusa rodar com o perfil `tunnel` ativo.
- O administrador do `.env` nunca recebe os dados de demonstração: eles ficam no usuário demo.

## Desempenho (`./cf bench`)

Cria um usuário descartável com N transações sintéticas (padrão 10 mil), roda o k6 por 20 s com 10 usuários virtuais passando pelo gateway e remove tudo ao final. Medido na máquina de desenvolvimento (p95, em ms):

| Rota | 10 mil transações | 100 mil transações |
|---|---|---|
| Lista de transações | 30 | 97 |
| Lista com filtros (tipo, período, texto) | 35 | 35 |
| Página profunda (página 400) | 34 | 88 |
| Resumo mensal | 24 | 20 |
| Resumo atual | 45 | 301 |

Leitura: para uso pessoal (10 mil linhas equivalem a quase 3 anos com 10 lançamentos por dia) tudo fica abaixo de 50 ms. O `/summary` é o primeiro a crescer, porque soma todas as transações a cada leitura (saldo derivado, [ADR-013](../02_Arquitetura/Arquitetura/ADR/013-saldo-derivado-e-classes-de-liquidez.md)). Se um dia passar de ~300 ms em uso real, o caminho é uma visão materializada ou um cache do saldo.

## Painéis do Grafana

Além de Visão geral, PostgreSQL e Redis, o painel **Negócio** mostra as transações registradas por tipo, o p95 das telas financeiras (última hora) e as respostas 4xx/5xx por rota. Nenhuma métrica carrega valor, descrição ou dado pessoal. Limitação do Prometheus a conhecer: a **primeira** ocorrência de uma série nova (por exemplo, o primeiro 404 de uma rota) não aparece em `rate()`/`increase()`; a partir da segunda aparece. Os contadores de negócio são registrados em 0 na partida justamente para evitar isso.

## Rede e isolamento

| Rede | Tipo | Serviços |
|---|---|---|
| `edge` | bridge | `gateway`, `cloudflared` (opcional) |
| `app` | internal (sem saída à Internet) | backend, web, Postgres, Redis, MQTT, gateway |
| `obs` | internal | Prometheus, Grafana, Loki, Alloy, exporters, gateway |

Só o `gateway` publica porta, e apenas em `127.0.0.1:8080`. O `./cf doctor` confere isso.

## Importar extratos (OFX e CSV)

Em **Importações** você traz o extrato do banco. O fluxo é sempre em duas fases: o sistema lê o arquivo e mostra uma **prévia**, e só quando você confirma é que as transações são criadas.

1. **Arquivo:** escolha a conta que recebe o extrato e o arquivo (`.ofx`, `.qfx`, `.csv` ou `.txt`, até 5 MB, 20 mil linhas, em reais). O formato é reconhecido pelo conteúdo, não pelo nome.
2. **Colunas (só CSV):** o sistema detecta o separador e o cabeçalho e sugere quais colunas são data, valor, descrição e identificador. Confira a amostra e ajuste. O valor pode vir em uma coluna com sinal, em uma coluna positiva com um tipo (D/C) ou em colunas separadas de débito e crédito.
3. **Revisão:** quatro abas.
   - **Novas:** serão importadas (desmarque as que não quiser).
   - **Duplicadas:** já existem na conta (mesmo identificador, ou mesmo valor, dia e descrição) e nunca são importadas.
   - **Possíveis duplicatas:** mesmo valor em data próxima, mas descrição diferente. Vêm **desmarcadas** e mostram o lançamento existente ao lado, para você decidir.
   - **Inválidas:** linhas que não deu para ler, com o motivo.
4. **Concluído:** as transações entram nos saldos e no resumo. Pelo histórico dá para **desfazer** a importação inteira (as transações saem dos saldos; o histórico fica) e importar de novo depois.

**Como exportar do banco:** procure "exportar extrato" no internet banking ou no app e escolha **OFX** (preferível, traz identificador e data certinhos) ou **CSV**. Cada banco exporta de um jeito: o leitor é genérico e o CSV tem mapeamento de colunas justamente por isso. Extrato de cartão de crédito também funciona; se as despesas vierem positivas, use "Inverter o sinal". Uma transferência entre contas suas aparece nos dois extratos e entra como receita numa conta e despesa na outra.

**Privacidade:** o arquivo **não fica guardado**. Ficam só o hash (para avisar "este arquivo já foi importado"), o nome e as linhas da prévia, por 24 horas, e depois as transações que você confirmou. Prévias não confirmadas são apagadas depois de 24 horas.

**Limites:** 20 prévias por hora por usuário (as descartadas contam). Acima disso a tela mostra em quantos minutos tentar de novo.

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
