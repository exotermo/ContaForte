# Operação

Guia para instalar, operar e monitorar o ContaForte. Tudo roda em Docker, em redes isoladas, e é controlado pelo CLI `./cf`.

## Quickstart

Pré-requisitos: Docker com Compose v2 e cerca de 1 GB de RAM livre para rodar: os limites do núcleo somam 880 MiB, ou 944 com o túnel (os builds e o monitoramento pedem bem mais). Não é preciso Java, Node nem Go no host: os builds acontecem em containers (o Go só é baixado em container se não existir localmente).

```bash
./cf init     # verifica pré-requisitos e gera o .env com segredos aleatórios
./cf up       # constrói e sobe o núcleo, esperando os serviços ficarem saudáveis
./cf open     # abre http://127.0.0.1:8080
./cf seed     # opcional: dados FICTÍCIOS de demonstração (veja abaixo)
```

O administrador inicial é `ADMIN_EMAIL` (padrão `admin@contaforte.local`) com a senha de `ADMIN_PASSWORD` no `.env`.

## Perfis: o que fica ligado

| Perfil | Serviços | Como ligar |
|---|---|---|
| (nenhum) | **núcleo:** backend, web, Postgres, Redis, gateway | `./cf up` |
| `obs` | Prometheus, Loki, Alloy, Grafana, exporters, docker-proxy | `./cf obs up` quando precisar e `./cf obs down` depois; sempre ligado: `COMPOSE_PROFILES=obs` no `.env` |
| `tunnel` | cloudflared | `COMPOSE_PROFILES=tunnel` e `TUNNEL_TOKEN` (veja abaixo) |
| `iot` | broker MQTT (ainda sem uso pela aplicação) | `COMPOSE_PROFILES=iot` |

Vários perfis são separados por vírgula (`COMPOSE_PROFILES=tunnel,obs`). `./cf down` derruba todos, mantendo os dados. Cada serviço guarda no máximo 3 arquivos de log de 10 MB. Decisão em [ADR-020](../02_Arquitetura/Arquitetura/ADR/020-nucleo-enxuto-e-monitoramento-opcional.md).

> **Atualizando de uma versão com tudo sempre ligado:** rode `./cf down` e `./cf up`. O monitoramento passa a ficar desligado (o histórico continua nos volumes) e o MQTT também.

## Comandos do `cf`

| Comando | O que faz |
|---|---|
| `init [--force] [--admin-email E] [--port N]` | Gera o `.env` (permissão 600) sem sobrescrever o que já existe. `--port` troca a porta local (padrão 8080). `--force` regenera tudo e pede confirmação |
| `up` / `down` / `build` | Sobe (núcleo e perfis do `.env`), derruba tudo e constrói imagens. `down` não aceita opções e nunca apaga dados |
| `obs up` / `obs down` | Liga e desliga o monitoramento sem recriar o núcleo; o histórico fica |
| `reset` | Derruba e **apaga os volumes** (pede confirmação) |
| `status` | Estado, saúde e consumo de cada serviço |
| `doctor` | Segredos, RAM, redes internas, portas expostas e, com o monitoramento ligado, alvos do Prometheus e logs no Loki |
| `logs [serviço]` | Acompanha os logs |
| `open [app\|grafana]` | Abre o app ou o Grafana (este precisa do monitoramento ligado) |
| `test [backend\|web\|go\|all]` | Testes em container |
| `e2e` | Testes de navegador (Playwright) contra a stack em execução |
| `seed [--reset] [--with-imports]` | Carrega 90 dias de dados **fictícios** num usuário demo, com regras e favorecidos de classificação, e confere o resultado; com `--with-imports`, importa também dois extratos OFX sintéticos |
| `bench [--import-rows N]` | Mede o p95 das telas com 10 mil transações sintéticas (k6 em container) e a importação de N linhas (padrão 20 mil), com o motor de classificação ligado |
| `mem [--for 60s]` | Memória por contêiner somando o swap, limite, maior total simultâneo e mortes por falta de memória (Linux com cgroup v2) |
| `backup` / `restore <arquivo>` | `pg_dump` comprimido em `backups/` e restauração |

## Dados de demonstração (`./cf seed`)

Para ver o produto com números sem digitar nada: `./cf seed` cria o usuário `demo@contaforte.local` (senha aleatória gerada uma vez e guardada em `DEMO_PASSWORD` no `.env`) e carrega, pela API (com todas as regras de negócio), 7 contas, 154 transações dos últimos 90 dias, uma de cada tipo de conta e de transação. No final, o `cf` compara o resumo calculado pelo backend com o resultado esperado pelo gerador e falha se divergirem.

- Tudo é **fictício** e determinístico (`datasets/synthetic/scenario.json`, gerado por `tools/gen-synthetic`, semente fixa). Nenhum dado real entra no repositório.
- Rodar de novo recusa se o usuário demo já tem dados. `./cf seed --reset` apaga **só** o usuário demo (e os dados dele, em cascata) e recarrega.
- **Só em ambiente local:** o comando recusa rodar com o perfil `tunnel` ativo.
- O administrador do `.env` nunca recebe os dados de demonstração: eles ficam no usuário demo.
- **Classificação de demonstração:** o seed cria 2 regras do usuário e ensina 3 favorecidos fictícios numa conta "Conta PIX (demonstração)" (um com 6 decisões, que aplica sozinho; um com 2, que só sugere; um na outra direção, com 1). Com `--with-imports`, o extrato sintético mostra os três comportamentos do motor (9 linhas categorizadas pelas regras do sistema, 2 só sugeridas) e a tela **Revisão** já abre com 2 sugestões esperando. O `cf` confere tudo isso pelo teste de texto do motor e pelas contagens da prévia, e falha se divergirem.

## Desempenho (`./cf bench`)

Além das telas, o bench mede a **importação de 20 mil linhas** com o motor de classificação ligado, no pior caso de regras (51 regras do usuário percorridas em toda linha, mais as do sistema): tempo de prévia, confirmação e desfazer e a memória do backend. Falha se a prévia passar de 10 s, se o backend passar de 92% do limite de 448 MiB, se algum serviço reiniciar ou morrer por falta de memória ou se o número de linhas categorizadas sozinhas diferir do esperado (8 mil de 20 mil).

Cria um usuário descartável com N transações sintéticas (padrão 10 mil), roda o k6 por 20 s com 10 usuários virtuais passando pelo gateway e remove tudo ao final. Medido na máquina de desenvolvimento (p95, em ms):

| Rota | 10 mil transações | 100 mil transações |
|---|---|---|
| Lista de transações | 30 | 97 |
| Lista com filtros (tipo, período, texto) | 35 | 35 |
| Página profunda (página 400) | 34 | 88 |
| Resumo mensal | 24 | 20 |
| Resumo atual | 45 | 301 |
| Série mensal (6 meses) | 48 | — |

Leitura: para uso pessoal (10 mil linhas equivalem a quase 3 anos com 10 lançamentos por dia) tudo fica abaixo de 50 ms. O `/summary` é o primeiro a crescer, porque soma todas as transações a cada leitura (saldo derivado, [ADR-013](../02_Arquitetura/Arquitetura/ADR/013-saldo-derivado-e-classes-de-liquidez.md)). Se um dia passar de ~300 ms em uso real, o caminho é uma visão materializada ou um cache do saldo.

## Dashboard (tela inicial)

A tela inicial mostra, de cima para baixo: a **posição de hoje** (caixa disponível, restrito em VA/VR, investido e patrimônio), a **movimentação do mês** e a lista de contas.

- **Caixa disponível** é o que existe hoje em conta corrente, poupança e dinheiro. **Não é previsão** do que sobrará no mês, e VA/VR nunca entram nele ([ADR-019](../02_Arquitetura/Arquitetura/ADR/019-dashboard-com-serie-mensal-e-caixa-disponivel-como-posicao.md)).
- **Movimentação do mês:** escolha o mês nas setas (não passa do mês atual). Mostra receitas, gastos (já descontados os estornos), sobra e investido, com a comparação escrita com o mês anterior. Transferências entre contas e investimentos não contam como receita nem gasto.
- **Barras dos últimos 6 meses:** receitas e gastos lado a lado, com legenda em texto e tabela para leitor de tela.
- **Gastos por categoria e por contexto:** a categoria abre as subcategorias. **Ver lançamentos de X** abre as transações já filtradas pelo mês e pela categoria ou contexto.
- Limitações: "Sem categoria" e "Sem contexto" não têm atalho, e a lista do atalho mostra só despesas (o total pode diferir do gráfico por causa dos estornos).

## Painéis do Grafana

Além de Visão geral, PostgreSQL e Redis, o painel **Negócio** mostra as transações registradas por tipo, as aberturas do mês e o p95 da série mensal na seção **Dashboard**, o p95 das telas financeiras (última hora) e as respostas 4xx/5xx por rota. Nenhuma métrica carrega valor, descrição ou dado pessoal. Limitação do Prometheus a conhecer: a **primeira** ocorrência de uma série nova (por exemplo, o primeiro 404 de uma rota) não aparece em `rate()`/`increase()`; a partir da segunda aparece. Os contadores de negócio são registrados em 0 na partida justamente para evitar isso.

## Rede e isolamento

| Rede | Tipo | Serviços |
|---|---|---|
| `edge` | bridge | `gateway`, `cloudflared` (opcional) |
| `app` | internal (sem saída à Internet) | backend, web, Postgres, Redis, gateway; MQTT no perfil `iot` |
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

## Memória

Cada serviço tem um teto, e a soma do núcleo com o túnel fica em 944 MiB ([ADR-021](../02_Arquitetura/Arquitetura/ADR/021-orcamento-de-memoria-de-1-gb.md)). Medido em 2 núcleos, contando o swap: cerca de 430 MiB parado, 440 na importação de 20 mil linhas e na carga do `bench` e 590 com uploads em paralelo (maior total simultâneo). `./cf mem` mostra o uso de agora.

Para caber nisso, o sistema faz **uma importação por vez** e calcula no máximo 2 senhas ao mesmo tempo. Um aviso de "ocupado" (503) significa que a fila demorou demais: basta tentar de novo.

Se um serviço reiniciar sozinho, confira com `./cf mem --for 60s` enquanto repete a ação e procure `Memory cgroup out of memory` em `journalctl -k`. Aumentar um limite exige tirar de outro: um teste mantém a soma em até 1024 MiB.

## Categorias sugeridas, regras e favorecidos

O sistema sugere a categoria e o contexto de cada receita e despesa, de três jeitos, nesta ordem: **suas regras**, o que ele **aprendeu dos favorecidos de PIX** e as **regras do sistema** (palavras comuns como "supermercado", "farmácia", "posto"). Ele só aplica sozinho quando tem muita certeza; no meio-termo, sugere e espera você confirmar; sem base, não sugere nada.

| Confiança | O que acontece | Como aparece |
|---|---|---|
| 85% ou mais | A categoria é aplicada sozinha | Selo **Automática** (com quem decidiu e a confiança) |
| de 50% a menos de 85% | Nada é aplicado: fica uma **sugestão** para você confirmar | Selo **Sugestão** e a transação entra na **Revisão** |
| menos de 50% | Sem sugestão | "Sem sugestão" |

- **Na importação:** a coluna **Categoria** da prévia mostra o que o sistema decidiu. Você troca a categoria ou o contexto de qualquer linha, ou clica em **Aceitar** na sugestão. Nada é aprendido até você confirmar a importação.
- **Revisão:** reúne as sugestões e o que ficou sem categoria. **Confirmar sugestão** com um clique, ou **Escolher…**, e marque "aplicar sempre que a descrição tiver este trecho" para criar uma regra. O número ao lado de "Revisão" no menu é quantas sugestões esperam por você. Em **Categorizar o que está sem categoria** você vê antes o que seria feito e só depois aplica; o que você já classificou nunca é alterado.
- **Regras:** "se a descrição tiver este trecho, a categoria é esta". O trecho ignora maiúsculas, acentos e pontuação e vale por palavras inteiras ("posto" casa com "COMPRA POSTO ABC", não com "COMPOSTO"). Suas regras valem antes de tudo e são aplicadas sozinhas; menor prioridade vale primeiro. Use **Testar um texto** para conferir antes de confiar.
- **Favorecidos:** o sistema aprende **só com as suas decisões** (confirmar, escolher, lançar com categoria), nunca com o que ele mesmo aplicou. Um único caso só gera sugestão; são precisas cerca de 6 decisões iguais para ele passar a aplicar sozinho, e discordâncias baixam a confiança. O que você paga a alguém e o que essa pessoa te paga são aprendidos separadamente.
- **Desfazer uma importação** também desfaz o que ela ensinou.

**Privacidade:** o nome de um favorecido é dado de outra pessoa. Ele fica só na sua conta, não vai para logs nem métricas e some quando você apaga o usuário. Em **Favorecidos** há "Esquecer", que apaga o aprendizado de uma pessoa sem mexer nas transações.

## Observabilidade

O monitoramento é opcional: ligue com `./cf obs up`. Para ver a memória sem ele, use `./cf mem`.

Grafana em `http://127.0.0.1:8080/grafana/` (usuário `admin`, senha `GRAFANA_PASSWORD` do `.env`). Três dashboards vêm provisionados como código (Visão geral, PostgreSQL, Redis). Prometheus guarda 15 dias de métricas e o Loki 7 dias de logs. Decisões em [ADR-012](../02_Arquitetura/Arquitetura/ADR/012-observabilidade-com-stack-grafana-e-docker-proxy.md).

- **Memória:** ligado, o monitoramento acrescenta cerca de 600 MiB ao núcleo (a stack completa mediu 1,3 GiB parada e 1,6 GiB sob carga, contando o swap). Não cabe junto com o núcleo em 1 GB.
- **Sem Alertmanager:** os alertas só aparecem no Prometheus e no Grafana, não notificam ninguém.
- **Postgres:** o `postgres-exporter` usa o mesmo usuário da aplicação. Um usuário dedicado com o papel `pg_monitor` é um endurecimento futuro.
- **Dashboards como código:** o gerador está em `tools/gen-dashboards`. Edite lá e regenere os JSON.

## Exposição pública com Cloudflare Tunnel (opcional)

Por padrão nada é exposto à Internet. Para publicar o app sem abrir portas:

1. No painel Cloudflare Zero Trust → Networks → Tunnels, crie um túnel e copie o token.
2. Em *Public hostname*, aponte seu domínio para `http://gateway:8081` (**8081**, não 80).
3. No `.env`, adicione `tunnel` ao `COMPOSE_PROFILES` e `TUNNEL_TOKEN=<token>`.
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
