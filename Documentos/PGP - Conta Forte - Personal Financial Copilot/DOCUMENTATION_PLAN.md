# Plano de Documentação

## Princípio: pragmatismo

Documentar o suficiente para decidir e construir, e nada além disso. Um documento só é criado quando alguém precisa dele. Formato padrão: Markdown, para facilitar revisão por diff e leitura no GitHub.

## Referência de processo

A estrutura de pastas vem do PGP/MCTIC (Iniciação, Planejamento, Execução, Monitoramento e Controle, Encerramento). Os artefatos do PGP são adotados em versão reduzida:

| Artefato PGP | Equivalente aqui |
|---|---|
| Pré-Projeto / Termo de Abertura | `00/Termo_de_Abertura.md` |
| Especificação de Escopo | `00/Especificacao_de_Escopo.md` |
| Planilha de Riscos | `00/Riscos.md` |
| Lições Aprendidas | `05/` ao fim de cada milestone |
| Termo de Aceite | critérios de aceite por milestone nos requisitos |

Os arquivos do MCTI não são redistribuídos neste repositório.

## Quando criar cada documento

| Gatilho | Documento |
|---|---|
| Decisão arquitetural relevante | novo ADR |
| Início de um milestone | atualizar requisitos e criar o README da pasta correspondente |
| Primeiro código executável | `03/DEVELOPMENT.md` e `06/QUICKSTART.md` |
| Primeiro deploy | runbook, backup e monitoramento em `06/` |
| Fim de um milestone | lições aprendidas em `05/` |

## Fontes de verdade

1. Código e configurações de segurança.
2. Docker/Compose.
3. Migrations (Flyway) e entidades.
4. OpenAPI gerado.
5. ADRs.
6. SDD (histórico de design, não é fonte de implementação).

## Regras

- Nunca incluir dados reais, segredos ou conteúdo de outros projetos.
- Cada ADR tem status: Proposto, Aceito ou Substituído.
