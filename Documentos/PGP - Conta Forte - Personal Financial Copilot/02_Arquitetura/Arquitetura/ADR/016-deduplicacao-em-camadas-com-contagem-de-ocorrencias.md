# ADR-016 — Deduplicação em camadas, com contagem de ocorrências

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
A mesma transação pode chegar por fontes diferentes (um extrato OFX, um CSV, um lançamento manual, e no futuro Open Finance). Duplicar soma dinheiro que não existe; deduplicar demais some um gasto legítimo. O SDD (§31) lista os sinais: conta, valor, data, identificador da transação, estabelecimento, identificador do PIX e hash da descrição.

## Decisão
A deduplicação roda sempre **dentro da mesma conta**, contra as transações **ativas** (excluídas logicamente não contam), em camadas da mais para a menos certa:

1. **Identificador externo** (FITID do OFX, ou a coluna de id do CSV, quando existe): igual ao de uma transação ativa da conta → **DUPLICADA** (certeza). O banco também impede o repetido (índice único parcial).
2. **Identificador PIX fim a fim**, quando a descrição o traz (`E` + 31 caracteres): serve de chave entre fontes. É a última peça do milestone; a inteligência de PIX é o M4.
3. **Mesmo valor, mesmo dia (America/Sao_Paulo) e mesma descrição normalizada** (sem acento, caixa, pontuação e espaços repetidos), **inclusive contra lançamentos manuais** → **DUPLICADA** (provável).
4. **Mesmo valor e dia a até 1 dia de diferença, com descrição diferente** → **POSSÍVEL DUPLICATA**. Vem desmarcada na prévia: o usuário decide.
5. Nenhuma das anteriores → **NOVA**.

**Contagem de ocorrências.** Linhas idênticas dentro do mesmo arquivo não são duplicatas entre si: dois cafés de R$ 8,00 no mesmo dia são dois lançamentos. Para cada chave (valor, dia, descrição), as linhas do arquivo são casadas **uma a uma** com as transações existentes de mesma chave: se o banco já tem 1 e o arquivo traz 2, a primeira é duplicada e a segunda é nova. Cada transação existente só pode "consumir" uma linha.

Sinal do valor: negativo é despesa, positivo é receita; zero e valor com mais de 2 casas são inválidos. Data sem hora vira 12:00 em São Paulo, para o dia não mudar por fuso. Só reais (BRL).

## Alternativas descartadas
- **Hash único (conta + valor + data + descrição) como "duplicata":** barra os dois cafés idênticos e é o erro clássico dos deduplicadores.
- **Tolerância de dias já na duplicata provável:** pegaria mais casos, mas esconderia gastos reais parecidos (assinaturas mensais de mesmo valor). Por isso o dia vizinho só gera "possível", com decisão humana.
- **Deduplicar entre contas:** a mesma transferência aparece nos dois extratos e é, de fato, uma de cada lado; tratá-la como duplicata perderia dinheiro.
- **Fuzzy match por similaridade de texto:** mais recall, menos explicabilidade (o usuário não saberia por que a linha foi marcada). Fica como evolução, se os dados mostrarem necessidade.

## Consequências
- Falso positivo é possível apenas na camada "provável" (mesmo valor, dia e texto de outro lançamento real, o que a contagem de ocorrências já reduz); falso negativo ocorre quando a descrição muda entre as fontes (cai em "possível" no máximo, ou em "nova").
- **Transferências entre contas próprias** aparecem como receita numa conta e despesa na outra; detectá-las é trabalho do M4.
- Os candidatos vêm de **uma consulta** limitada ao intervalo de datas do arquivo, e a comparação é feita em memória: custo previsível para 20 mil linhas.
- A normalização fica num único lugar (testável) e é a mesma usada ao comparar linhas do arquivo com transações existentes.
