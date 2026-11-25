# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/), com versionamento semântico.

## [0.4.0] — Milestone 4: PIX Intelligence

### Adicionado
- **Classificação em estágios** de categoria e contexto, em cada dimensão: regra do usuário, favorecido PIX conhecido e regra padrão do sistema (40 regras em pt-BR). Cada resposta traz a **confiança** e a origem; decisão em três faixas: **aplicada sozinha** (≥ 0,85), **sugerida** (0,50 a menos de 0,85) ou sem palpite ([ADR-017](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/017-classificacao-em-estagios-com-confianca-calibrada.md)).
- **Favorecidos PIX** aprendidos só com as decisões do usuário, com votos por direção (recebido e enviado) e confiança suavizada `k/(n+1)`: de 1 a 5 confirmações apenas sugere; **esquecer favorecido** apaga o que foi aprendido ([ADR-018](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/018-privacidade-de-favorecidos-e-feedback.md)).
- **Regras do usuário** (palavra inteira, prefixo ou expressão exata; até 200), com ativar e desativar, e **teste de texto** que mostra qual estágio decidiria e por quê.
- **Feedback**: toda confirmação ou correção é gravada (`ClassificationFeedback`) e o aprendizado é **revertido** quando a importação é desfeita.
- **Prévia de importação classificada**: cada linha chega com categoria e contexto sugeridos e pode ser ajustada antes de confirmar.
- **Fila de Revisão** de sugestões com decisão de um clique, contagem no menu e **reclassificação do histórico em duas fases** (prévia com contagens, aplicar só se nada mudou).
- Telas **Revisão**, **Regras** e **Favorecidos**; chips "Automática" e "Sugerida" nas transações e sugestão ao digitar uma transação manual.
- API `/api/v1/categorization-rules`, `/pix-recipients` e `/classification` (revisão, decisão, reclassificação) e `PATCH /imports/{id}/items/{itemId}`.
- `./cf seed` ensina duas regras e três favorecidos de demonstração e **se autoverifica**; `./cf bench` importa 20 mil linhas com 51 regras e confere a contagem exata de categorizadas.
- Métricas `contaforte_classifications_total{source,decision}` e `contaforte_classification_feedback_total{kind}` e seção **Classificação de transações** no painel Negócio.
- Conjunto sintético de 766 casos com resposta conhecida (`datasets/synthetic/classification`), regenerado e conferido no CI.

### Segurança e privacidade
- Nomes de favorecidos pertencem a cada usuário e **nunca** aparecem em log, métrica ou auditoria; apagar o usuário apaga tudo o que foi aprendido.
- Só decisão do usuário ensina: o que o sistema aplicou sozinho não reforça a si mesmo.
- Integridade no banco: restrições de coerência entre estado, origem e sugestão e chaves compostas por dono (migrações V7 a V9).

### Desempenho e qualidade
- Precisão medida no conjunto sintético: **98,1%** das categorias e **100%** dos contextos aplicados sozinhos (o teste exige 95%).
- Motor: 20 mil linhas em cerca de 0,4 s; prévia de 20 mil linhas classificada em 3,6 a 4,1 s, confirmação em 4,2 a 4,8 s, desfazer em 0,4 s e pico de 500 MiB no backend (limite 768 MiB).
- 617 testes de backend, 229 de frontend e 19 E2E.

## [0.3.0] — Milestone 3: Imports

### Adicionado
- **Importação de extratos OFX e CSV** em duas fases: o envio gera uma **prévia** (nada é criado) e só a confirmação cria as transações. Cada importação tem identificador e pode ser **desfeita** por inteiro ([ADR-015](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/015-importacao-em-duas-fases-sem-guardar-o-arquivo.md)).
- **Leitores** de OFX (1.x em SGML e 2.x em XML, sem parser XML e sem XXE) e de CSV com **mapeamento de colunas** (separador, cabeçalho, formato de data, valor com sinal, com tipo D/C ou em débito e crédito) e sugestão automática.
- **Deduplicação em camadas** (identificador e PIX, depois mesmo tipo, valor, dia e descrição, depois ±1 dia como "possível duplicata"), com contagem de ocorrências ([ADR-016](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/016-deduplicacao-em-camadas-com-contagem-de-ocorrencias.md)).
- **Tela de Importações**: assistente (arquivo, colunas, revisão, conclusão), histórico com revisar, descartar e desfazer, filtro de transações por importação e etiqueta de origem (OFX/CSV).
- API `/api/v1/imports` (enviar, sugerir mapeamento, listar, detalhar linhas, confirmar, desfazer, descartar) e filtro `importId` em transações.
- `./cf seed --with-imports` e `./cf bench --import-rows` (padrão 20 mil linhas); seção de importações no painel **Negócio**.
- Fixtures sintéticas de extratos em `datasets/synthetic/imports`.

### Segurança e privacidade
- O arquivo enviado **não é guardado**: só hash, nome e linhas normalizadas por 24 h; prévias vencidas são apagadas.
- Limites: 5 MB (também no proxy e no gateway), 20 mil linhas, BRL e 20 prévias por hora por usuário (falha fechada sem Redis).
- Integridade no banco: a importação pertence à conta e ao dono; identificador externo único por conta; contadores e datas conferidos por restrição.
- Auditoria e métricas só com ids e contagens, nunca com nome de arquivo, descrição ou valor.

### Desempenho
- Prévia de 20 mil linhas em 1,4 s, confirmação em 2,6 s e pico de 434 MiB no backend (limite 768 MiB).

## [0.2.0] — Milestone 2: Financial Core

### Adicionado
- **Contas e carteiras** (8 tipos) com classe de liquidez por tipo; o saldo é derivado das transações, nunca armazenado ([ADR-013](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/013-saldo-derivado-e-classes-de-liquidez.md)).
- **Categorias em 2 níveis** (12 categorias e 39 subcategorias padrão em pt-BR) e **contextos** (Individual, Família, Relacionamento, Pet, Trabalho, Outro), com os do usuário ao lado dos padrão ([ADR-014](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/02_Arquitetura/Arquitetura/ADR/014-categorias-em-dois-niveis-e-contextos.md)).
- **Transações manuais** (receita, despesa, transferência, investimento e estorno) com filtros por período, conta, tipo, categoria, contexto e texto, paginação e exclusão lógica.
- **Resumo financeiro**: `available_cash`, `restricted_balance` (VA/VR nunca entram no caixa disponível), `invested_assets`, cartão de crédito e patrimônio líquido; e **resumo mensal** por categoria e contexto.
- **Telas** de Resumo, Contas e Transações, com formulários acessíveis em pt-BR e proxy de dados `/bff` (o navegador continua sem ver tokens).
- `POST /api/v1/admin/users`: o administrador cria pessoas (sempre com papel USER).
- **Dados sintéticos** (`datasets/synthetic`, gerados de forma determinística) e `./cf seed`, que carrega 90 dias de dados fictícios num usuário demo e se autoverifica contra o resumo esperado.
- `./cf bench`: p95 das telas com 10 mil transações (k6).
- Painel **Negócio** no Grafana e a métrica `contaforte_transactions_recorded_total{type}`.
- `./cf version` passa a mostrar a tag do Git.

### Segurança
- Isolamento entre usuários também no banco (chave estrangeira composta e gatilhos); recurso de outro usuário responde 404.
- Valor e descrição de transações nunca vão para logs, métricas, eventos ou auditoria.
- Papéis SERVICE e DEVICE não acessam dados financeiros.

## [0.1.0] — Milestone 1: Foundation

### Adicionado
- Stack Docker com redes isoladas (`edge`, `app` e `obs` internas), PostgreSQL 16, Redis 7, Mosquitto e gateway nginx publicado só em `127.0.0.1`.
- CLI `cf` (Go): `init`, `up`, `down`, `reset`, `status`, `doctor`, `logs`, `open`, `test`, `e2e`, `backup`, `restore`.
- Backend Spring Boot (Java 21) com Flyway e Actuator.
- Autenticação com JWT RS256, refresh tokens com rotação e detecção de reuso, Argon2id, rate limit no Redis, RBAC e auditoria sem dados pessoais.
- Frontend Next.js com login, página protegida e PWA; sessão por cookies httpOnly (BFF).
- Monitoramento: Prometheus, Grafana (3 dashboards provisionados), Loki, Alloy, exporters, alertas básicos e proxy somente-leitura do Docker.
- CI no GitHub Actions (Go, backend, frontend, smoke test da stack, gitleaks) e Dependabot.
- Perfil opcional de Cloudflare Tunnel.
- ADRs 001 a 012 e documentação de operação e desenvolvimento.
