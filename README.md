# ContaForte — Personal Financial Copilot

Plataforma open source e auto-hospedável de gestão financeira pessoal para o contexto brasileiro. Organiza contas e transações, separa dinheiro disponível de benefícios como VA/VR e permite analisar gastos por **categoria e contexto**.

A visão do produto é evoluir para um copiloto com categorização de PIX, previsões e recomendações explicáveis. Esses recursos fazem parte do roadmap; ainda não estão disponíveis.

[![CI](https://github.com/exotermo/ContaForte/actions/workflows/ci.yml/badge.svg)](https://github.com/exotermo/ContaForte/actions/workflows/ci.yml)

> **Status — revisão de 24/11/2026:** M1, M2 e M3 entregues, com tags `v0.1.0`, `v0.2.0` e `v0.3.0`. O MVP completo corresponde a M1–M5.

## O que já funciona

- Autenticação, perfis USER/ADMIN e sessão web com tokens em cookies `HttpOnly`.
- Contas e carteiras, categorias em dois níveis e contextos independentes: por exemplo, categoria “Alimentação / Restaurante” e contexto “Relacionamento”.
- Receitas, despesas, transferências, investimentos e reembolsos manuais.
- Resumo financeiro que separa caixa disponível, saldo restrito (VA/VR), investimentos e patrimônio líquido. O caixa disponível não é uma previsão de quanto sobra após compromissos futuros.
- Ambiente Docker, CLI de operação, dados sintéticos de demonstração e monitoramento provisionado.
- **Importação de extratos OFX e CSV:** envie o arquivo, revise a prévia (novas, duplicadas, possíveis duplicatas e inválidas), confirme e, se precisar, desfaça a importação inteira. O arquivo não é guardado. Limites: BRL, até 5 MB e 20 mil linhas. Transferências entre contas próprias importadas ainda aparecem como receita numa conta e despesa na outra, sem conciliação automática.

## Stack atual

| Camada | Implementação |
|---|---|
| Interface e sessão | Next.js 16, React 19, TypeScript, Tailwind 4, TanStack Query; BFF para chamadas autenticadas da interface |
| Domínio e API | Java 21, Spring Boot 3.5, monólito modular, REST `/api/v1`, Spring Security, JPA e Flyway |
| Dados | PostgreSQL 16 como fonte da verdade; valores em `NUMERIC`/`BigDecimal`, sem ponto flutuante monetário |
| Controle de taxa | Redis 7 |
| Entrada | Nginx local; Cloudflare Tunnel opcional |
| Observabilidade | Prometheus, Grafana, Loki, Alloy e exporters de PostgreSQL, Redis e containers |
| Operação e qualidade | CLI `cf` em Go, Docker Compose, GitHub Actions, Testcontainers, Vitest, Playwright e gitleaks |

No acesso local, o gateway recebe o navegador em `127.0.0.1:8080`, encaminha a interface ao Next.js e `/api/` ao backend. O BFF do Next.js chama a API pela rede interna. O Grafana fica em `/grafana/`, apenas na entrada local.

**Componentes futuros:** Python/FastAPI para inteligência e firmware ESP32 para IoT. O broker MQTT já faz parte do Compose, mas ainda não há integração funcional com dispositivos. As pastas `apps/intelligence/` e `firmware/esp32/` estão reservadas para essas etapas.

O [Compose](docker-compose.yml) descreve os serviços executáveis. A [arquitetura](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ARCHITECTURE.md) e o [SDD](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/SDD/SDD_Personal_Financial_Copilot.md) também descrevem a visão futura; consulte os [ADRs](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/README.md) para as decisões de implementação.

## Quickstart

Em um terminal Bash, com o repositório clonado, precisa de Docker com Compose v2 e cerca de 4 GB de RAM livre para a stack. Reserve memória adicional para builds e testes. Java, Node e Go não são necessários no host; o wrapper pode usar Go local se disponível.

```bash
./cf init     # verifica pré-requisitos e gera o .env com segredos aleatórios
./cf up       # constrói e sobe a stack, esperando ficar saudável
./cf open     # http://127.0.0.1:8080 (admin inicial: veja ADMIN_EMAIL/ADMIN_PASSWORD no .env)
./cf seed --with-imports   # opcional: 90 dias de dados FICTÍCIOS num usuário demo (demo@contaforte.local, senha em DEMO_PASSWORD no .env) e dois extratos OFX sintéticos importados
```

O CLI `./cf` também cuida de `status`, `doctor`, `logs`, `test`, `e2e`, `bench`, `backup` e `restore`. O Grafana fica em `http://127.0.0.1:8080/grafana/` (usuário `admin`, senha `GRAFANA_PASSWORD`). Para publicar na Internet sem abrir portas há um perfil opcional de Cloudflare Tunnel. Guia completo em [Operação](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/06_Operacao/README.md).

### Segurança por padrão

- Só o gateway publica porta, e apenas em `127.0.0.1`. Bancos e serviços ficam em redes Docker `internal`, sem saída à Internet.
- Segredos gerados aleatoriamente pelo `./cf init` (`.env` com permissão 600, fora do Git). Login com JWT RS256, refresh com rotação e rate limit.
- Alloy e o exporter de containers acessam a API Docker por um proxy com operações de escrita bloqueadas. O `docker-proxy` monta o `docker.sock` do host; a montagem `:ro` não substitui o controle da API. Os testes locais com Testcontainers também precisam de acesso ao daemon Docker.

### Operação: o que saber

- **Memória do host:** os limites configurados somam cerca de 3,7 GiB sem o túnel opcional. O consumo depende da carga; acompanhe com `./cf status`.
- **Postgres do monitoramento:** o `postgres-exporter` usa o mesmo usuário da aplicação. Um usuário dedicado com o papel `pg_monitor` é um endurecimento futuro.
- **Sem Alertmanager:** os alertas só aparecem no Prometheus e no Grafana, não notificam ninguém.
- **Gerador de dashboards:** `tools/gen-dashboards` reproduz exatamente os JSON provisionados. Edite lá e regenere.

## Roadmap

| Marco | Foco | Estado |
|---|---|---|
| M1 — Foundation | Docker, autenticação, CI e observabilidade | Entregue (`v0.1.0`) |
| M2 — Financial Core | Contas, transações, categorias, contextos e resumo de liquidez | Entregue (`v0.2.0`) |
| M3 — Imports | OFX/CSV, normalização, deduplicação e desfazimento | Entregue (`v0.3.0`) |
| M4 — PIX Intelligence | Favorecidos, regras, categorização e feedback | Planejado |
| M5 — Dashboard | Gastos por categoria/contexto, VA/VR e caixa disponível | Planejado; o resumo básico já existe no M2 |
| M6 — Intelligence | Python, previsões, anomalias e insights explicáveis | Futuro |
| M7 — Goals | Orçamentos, reserva, metas e capacidade de investimento | Futuro |
| M8 — IoT | ESP32, OLED e impressora térmica via MQTT | Futuro |
| M9 — Financial Twin | Simulação de cenários | Futuro |

A prioridade é entregar M1–M5 de ponta a ponta. Open Finance, movimentação bancária, recomendação de ativos individuais e aplicativo mobile nativo estão fora do MVP. Veja a [especificação de escopo](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/00_Gestao_do_Projeto/Especificacao_de_Escopo.md).

## Estrutura do repositório

| Caminho | Conteúdo |
|---|---|
| `apps/backend/` | API, domínio financeiro, migrations e testes Java |
| `apps/web/` | Interface, BFF, testes de componente e E2E |
| `infrastructure/` | Gateway, MQTT e configuração de observabilidade |
| `tools/` | CLI, exporter e geradores de dados sintéticos e dashboards |
| `datasets/synthetic/` | Cenários e extratos fictícios para demonstração e testes |
| `.github/` | CI e configuração de atualizações de dependências |
| `Documentos/` | Gestão, requisitos, arquitetura, desenvolvimento, testes e operação |

O `checklist.md` é um acompanhamento **local e não versionado**. Entregas públicas são registradas no roadmap, nos requisitos e no CHANGELOG; marcar uma etapa local não equivale a publicar uma versão.

## Limites da versão atual

- Criação de usuários pela API administrativa; cadastro público e recuperação de senha ainda não implementados.
- Exportação de dados e fluxo de exclusão pelo usuário ainda pendentes. Privacidade e LGPD são requisitos do projeto, não uma declaração de conformidade concluída.
- O túnel opcional tem configuração e verificações locais documentadas; a validação com domínio real, HTTPS e cookies seguros permanece pendente no acompanhamento local.
- A configuração atual é uma base de desenvolvimento e auto-hospedagem. Operação comercial exige validar onboarding, suporte, recuperação de dados, alertas e capacidade com a carga esperada.

## Verificação

```bash
./cf status        # estado dos serviços
./cf doctor        # diagnóstico de rede, segredos e observabilidade
./cf test all      # testes de backend, frontend e ferramentas Go
./cf e2e           # testes no navegador contra a stack em execução
```

O [workflow de CI](.github/workflows/ci.yml) cobre backend, frontend, Go, arquivos gerados, segredos e smoke test da stack. A execução de Playwright é disponibilizada pelo CLI; o workflow atual não chama `./cf e2e`. Estratégia e evidências de releases em [Testes](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/04_Testes/README.md).

## Documentação

Para desenvolver, veja o [guia de desenvolvimento](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/03_Desenvolvimento/DEVELOPMENT.md) e o [CHANGELOG](CHANGELOG.md). Toda a documentação de projeto está em [`Documentos/`](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/README.md), organizada seguindo o PGP/MCTI de forma enxuta.

## Contribuindo

Veja [CONTRIBUTING.md](CONTRIBUTING.md). Problemas de segurança: [SECURITY.md](SECURITY.md).

## Licença

[GNU AGPL-3.0](LICENSE). Consulte o texto da licença para as condições de uso, modificação e distribuição.
