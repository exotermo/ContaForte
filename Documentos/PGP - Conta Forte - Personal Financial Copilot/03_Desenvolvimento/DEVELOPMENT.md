# Guia de desenvolvimento

Como trabalhar no código do ContaForte. Para rodar e operar a stack, veja [06_Operacao](../06_Operacao/README.md).

## Pré-requisitos
Só Docker (Compose v2). Os builds e testes rodam em containers, então não é preciso instalar Java 21, Node nem Go. O `./cf` usa o Go local se existir e, senão, compila o CLI num container.

## Mapa do código

| Caminho | O que é |
|---|---|
| `apps/backend` | Monólito modular Java 21 + Spring Boot. Pacotes `auth`, `users`, `audit`, `common`, `system`. Migrações Flyway em `src/main/resources/db/migration` |
| `apps/web` | Next.js 16 (React 19, TypeScript, Tailwind 4, TanStack Query). BFF com cookies httpOnly em `app/session/*`, guarda de navegação em `proxy.ts`, testes Playwright em `e2e/` |
| `tools/cf` | CLI de configuração e operação (Go, só biblioteca padrão) |
| `tools/container-exporter` | Exporter Prometheus de CPU, memória e rede por container (Go) |
| `tools/gen-dashboards` | Gerador dos dashboards do Grafana (Python) |
| `tools/gen-synthetic` | Gerador do cenário sintético de demonstração (Python, determinístico) |
| `tools/loadtest` | Script k6 usado pelo `./cf bench` |
| `infrastructure/` | Gateway nginx, MQTT, monitoramento (Prometheus, Loki, Alloy, Grafana) |
| `docker-compose.yml` | A stack inteira, em três redes: `edge`, `app` (internal) e `obs` (internal) |
| `datasets/synthetic` | Cenário fictício do `./cf seed`. **Nunca** dados reais |
| `Documentos/` | SDD, ADRs, requisitos e operação |

## Testes

```bash
./cf test            # tudo: Go, backend e web
./cf test backend    # Gradle + Testcontainers (Postgres e Redis reais)
./cf test web        # lint, tipos e Vitest
./cf test go         # cf e container-exporter
./cf e2e             # Playwright contra a stack em execução (precisa do ./cf up)
```

Os mesmos passos rodam no CI (`.github/workflows/ci.yml`). Atenção: o teste do backend monta o `docker.sock` para o Testcontainers, o que equivale a acesso root ao daemon. Use só em desenvolvimento.

## Frontend: como os dados chegam à tela
- O navegador **nunca** fala com `/api` nem vê tokens. Todas as chamadas de dados passam por `/bff/*` (`apps/web/app/bff/[...path]/route.ts`), que lê o access token do cookie httpOnly e o repassa ao backend. O proxy só alcança `accounts`, `categories`, `contexts`, `transactions`, `summary`, `imports`, `categorization-rules`, `pix-recipients` e `classification`; valida cada segmento do caminho; recusa `Origin` de outro site em POST/PATCH/DELETE (CSRF) e limita corpo e consulta. O upload de extrato (`POST /bff/imports`) só aceita `multipart/form-data`, lê o corpo em pedaços e corta em 5,5 MB mesmo sem `Content-Length`; a confirmação de uma importação aceita JSON de até 1 MB (listas de ids), e os demais corpos, 64 KB.
- O cliente (`lib/finance.ts`) renova a sessão sozinho ao receber 401 (single-flight) e traduz erros para pt-BR. Sessão inválida em qualquer chamada leva ao login (`components/providers.tsx`).
- **Dinheiro nunca passa por `Number`:** a API devolve string decimal (`"1234.50"`). `lib/money.ts` converte o que o usuário digita (`1.234,56`) em string canônica e formata de volta (`R$ 1.234,56`), recusando mais de 2 casas e valores acima de 12 dígitos inteiros. Não use `parseFloat` para valores em reais.
- Telas: Resumo (`/`), Contas (`/contas`) e Transações (`/transacoes`) dentro do grupo `app/(app)`, que compartilha o cabeçalho (`components/app-shell.tsx`). Tabelas viram pilha de cartões no celular.

## Fluxos comuns
- **Nova migração:** crie `V<N>__descricao.sql` em `db/migration`. Nunca edite uma migração já aplicada.
- **Novo endpoint:** controller no pacote do domínio, regra de acesso em `SecurityConfig`, teste de integração estendendo `AbstractIntegrationTest`. A API sai documentada em `/api/v1/openapi`.
- **Cenário de demonstração:** edite `tools/gen-synthetic/gen_synthetic.py` e regenere `datasets/synthetic/scenario.json` (o gerador confere que nenhuma conta comum fica negativa e calcula o resumo esperado). O CI regenera os dois geradores e falha se o arquivo versionado estiver desatualizado.
- **Dashboards:** edite `tools/gen-dashboards/gen_dashboards.py`, regenere os JSON em `infrastructure/monitoring/grafana/dashboards` e reinicie o Grafana (o monitoramento precisa estar ligado: `./cf obs up`).
- **E2E e dados de teste:** os testes Playwright criam contas, transações e importações com o prefixo `e2e-`; o `./cf e2e` remove tudo o que tem esse prefixo ao terminar (passando ou falhando) e zera os contadores de limite de importação no Redis. Use o prefixo em qualquer dado novo de E2E. Os testes E2E de classificação rodam com um **usuário descartável** (`e2e-...@contaforte.local`, criado pela API de administração): regras, favorecidos e feedback aprendidos não podem sobrar na conta real nem contaminar a próxima execução, e o `cf e2e` apaga os usuários `e2e-%` ao terminar (cascata leva tudo). O E2E roda **em série** (`workers: 1`): as specs compartilham o usuário admin e algumas medem a variação do resumo dele, então rodar em paralelo gera falhas intermitentes. As fixtures de extrato chegam ao contêiner do Playwright por `FIXTURES_DIR` (`datasets/` montado somente leitura).
- **Mudou arquivo montado** (Prometheus, nginx, Alloy)? `./cf up` não recria o container: reinicie o serviço (`docker compose restart <serviço>`).
- **Decisão arquitetural nova:** vira um ADR em `02_Arquitetura/Arquitetura/ADR`.

## Regras do projeto
Veja o [CONTRIBUTING](../../../CONTRIBUTING.md). Em resumo: sem dados financeiros reais, valores monetários em `BigDecimal`/`NUMERIC`, recomendações sempre explicáveis, commits no estilo Conventional Commits.

## Convenções de segurança já adotadas
- Segredos só no `.env` (permissão 600, ignorado pelo Git). O `./cf doctor` confere.
- Tokens nunca ficam acessíveis ao JavaScript do navegador (BFF com cookies httpOnly + SameSite=Strict).
- Logs e auditoria sem dados pessoais.
- Containers sem `docker.sock`, exceto o proxy somente-leitura do monitoramento.

## Modelo de dados e API
Veja [MODELO_DE_DADOS.md](MODELO_DE_DADOS.md): diagrama, regras impostas pelo banco, liquidez e a tabela de endpoints.

## Releases
Versionamento semântico com tags `vX.Y.Z` na `main`. O histórico de mudanças está em [CHANGELOG.md](../../../CHANGELOG.md).
