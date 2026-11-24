# Dados sintéticos

`scenario.json` é o cenário **fictício** que o `./cf seed` carrega num usuário demo: 7 contas e 154 transações dos últimos 90 dias (datas relativas a "hoje"), cobrindo todos os tipos de conta e de transação, com VA/VR, cartão de crédito, investimento, estorno e contextos.

- **Nunca** coloque aqui dados reais (extratos, CPFs, chaves PIX, tokens). A pasta `datasets/real/` é ignorada pelo Git de propósito.
- O arquivo é gerado por `python3 tools/gen-synthetic/gen_synthetic.py` com semente fixa: rodar de novo produz exatamente o mesmo arquivo. Não edite o JSON à mão.
- Os valores são centavos inteiros no gerador (sem ponto flutuante), nenhuma conta comum fica negativa em nenhum momento, e o campo `expected` traz o resumo que o backend precisa devolver depois do carregamento. O `./cf seed` confere isso.
- O CI regenera o arquivo e falha se ele estiver diferente do versionado.

## Extratos de teste (`imports/`)

`imports/` guarda **extratos fictícios** em OFX e CSV, gerados por `python3 tools/gen-synthetic/gen_import_fixtures.py` (determinístico, sem dado real). Servem aos testes dos parsers, à deduplicação e ao E2E de importação.

| Arquivo | O que exercita |
|---|---|
| `conta-corrente-1.ofx` | OFX 1.x (SGML) em Windows-1252, sem tag de fechamento nos campos, data com hora e fuso; 12 transações, duas idênticas no mesmo dia |
| `conta-corrente-2.ofx` | OFX 2.x (XML) em UTF-8, datas só com o dia; repete as 4 últimas do arquivo acima e traz 3 novas (teste de sobreposição) |
| `fatura-cartao.ofx` | Fatura de cartão (compras negativas, pagamento positivo) |
| `banco-a.csv` | `;`, vírgula decimal, milhar com ponto, `dd/MM/yyyy`, Windows-1252, coluna de documento |
| `banco-b.csv` | `,`, UTF-8 com BOM, data ISO, débito e crédito em colunas separadas, campos entre aspas |
| `banco-c.csv` | tabulação, Windows-1252, 3 linhas de título antes do cabeçalho, tipo D/C com valor positivo, `dd/MM/yy` |
| `banco-a-invalido.csv` | linhas ruins no meio (data impossível, 3 casas, valor ausente ou texto, zero, antes de 2000, futuro, sem data) |
| `moeda-usd.ofx`, `duas-contas.ofx`, `sem-transacoes.ofx` | arquivos recusados inteiros |

Os layouts imitam padrões comuns, mas **não são cópias de extrato de banco algum**. Os arquivos `.ofx` e `.csv` só são versionados aqui: o `.gitignore` bloqueia essas extensões em qualquer outro lugar, para extrato real nunca entrar no repositório. Se um banco real não for lido corretamente, a correção entra com uma amostra **anonimizada** e sintetizada, nunca com o arquivo original.

## Conjunto de classificação (`classification/`)

`classification/cases.json` é um **fluxo ordenado de 766 transações fictícias com a categoria e o contexto corretos** ("verdade"), gerado por `python3 tools/gen-synthetic/gen_classification.py` (determinístico, sem dado real). O teste de precisão do backend reproduz o fluxo: o motor classifica cada transação e, quando só sugere ou não sabe, a simulação faz o usuário confirmar a verdade, que é como os favorecidos aprendem. Decisões automáticas nunca ensinam nem são corrigidas (pior caso).

| Tipo (`kind`) | O que exercita |
|---|---|
| `merchant` | estabelecimentos que as regras padrão do sistema conhecem, com ruído (datas, cidades, números) |
| `unknown` | estabelecimentos que nenhuma regra conhece: o motor precisa se abster |
| `trap` | palavra-chave que engana (`POSTO CULTURAL`): conta contra a precisão |
| `userRule` | descrições cobertas pelas regras do usuário do próprio arquivo (inclui regra só de contexto) |
| `pix` | 25 favorecidos em 6 formatos de descrição, 5 deles de comportamento misto (90% e 70% constante) e 2 que enviam e recebem |

Medido na M4.2: categoria aplicada sozinha em 67% dos casos com **98,1% de precisão** (regras padrão 97,4%, favorecidos 98,9%, regras do usuário 100%), contexto aplicado sozinho com 100%, e 111 casos sem sugestão.

