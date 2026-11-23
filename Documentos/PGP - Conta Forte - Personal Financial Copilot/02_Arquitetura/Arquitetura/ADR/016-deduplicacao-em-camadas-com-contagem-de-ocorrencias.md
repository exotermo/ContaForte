# ADR-016 — Deduplicação em camadas, com contagem de ocorrências

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
A mesma transação pode chegar por fontes diferentes (um extrato OFX, um CSV, um lançamento manual, e no futuro Open Finance). Duplicar soma dinheiro que não existe; deduplicar demais some um gasto legítimo. O SDD (§31) lista os sinais: conta, valor, data, identificador da transação, estabelecimento, identificador do PIX e hash da descrição.

## Decisão
A deduplicação roda sempre **dentro da mesma conta**, contra as transações **ativas** (excluídas logicamente não contam), em **três passadas**, cada uma sobre todas as linhas antes da seguinte, da mais certa para a menos certa. Uma correspondência fraca nunca rouba a transação que pertence a outra linha com correspondência forte, qualquer que seja a ordem das linhas.

**Passada 1 — certeza**
1. **Linha inválida** (data, valor ou tipo ilegível) fica INVÁLIDA, com o motivo, e nunca é importada.
2. **Identificador repetido no próprio arquivo** (o mesmo FITID ou PIX em duas linhas): a segunda é DUPLICADA (o índice único de identificador recusaria a inserção de qualquer forma).
3. **Identificador externo** (FITID do OFX, ou a coluna de id do CSV) igual ao de uma transação ativa da conta, **em qualquer data**: DUPLICADA. Vale sempre, mesmo que a transação já tenha casado com outra linha, porque o índice único do banco vale sempre.
4. **Identificador PIX fim a fim**, quando a descrição o traz (`E` ou `D` + 31 caracteres): igual ao de uma transação existente → DUPLICADA. É a chave entre fontes e entre bancos. A caixa do identificador é preservada: maiúsculas e minúsculas são identificadores diferentes.

**Passada 2 — provável**
5. Mesmo **tipo, valor, dia (America/Sao_Paulo) e descrição normalizada** (sem acento, caixa, pontuação e espaços repetidos) → DUPLICADA (provável), **inclusive contra lançamentos manuais**. Duas exceções: sem texto dos dois lados não há prova suficiente; e **dois identificadores externos diferentes** (um de cada lado) indicam dois lançamentos distintos do mesmo banco (dois cafés), então não é duplicata provável. A descrição comparada é a do campo de descrição (o memo no OFX), nunca o nome do estabelecimento, porque um CSV costuma trazer só o histórico.

**Passada 3 — possível**
6. Mesmo tipo e valor a até **1 dia** de diferença, com qualquer descrição, e uma transação existente ainda não "consumida" → POSSÍVEL duplicata. Vem **desmarcada** na prévia: o usuário decide. Havendo várias candidatas, vale a mais próxima em dias e, no empate, a mais antiga.
7. Nenhuma das anteriores → **NOVA**, marcada para importar.

**Contagem de ocorrências.** Cada transação existente só pode "consumir" UMA linha. Linhas idênticas dentro do mesmo arquivo não são duplicatas entre si: dois cafés de R$ 8,00 no mesmo dia são dois lançamentos. Se o banco já tem 1, só a primeira linha é duplicada; se tem 2 e o arquivo traz 3, a terceira é nova.

**Efeito na conta.** As transações existentes são vistas pelo seu efeito na conta: uma transferência ou investimento que **sai** pesa como despesa e o que **chega** como receita. Assim, o extrato que mostra a saída de uma transferência lançada à mão aparece como possível duplicata (não some sozinho, porque as descrições quase nunca coincidem), em vez de contar o dinheiro duas vezes. O identificador externo só vale do lado da conta em que foi importado.

Sinal do valor: negativo é despesa, positivo é receita; zero e valor com mais de 2 casas são inválidos. Data sem hora vira 12:00 em São Paulo, para o dia não mudar por fuso. Só reais (BRL).

## Alternativas descartadas
- **Hash único (conta + valor + data + descrição) como "duplicata":** barra os dois cafés idênticos e é o erro clássico dos deduplicadores.
- **Tolerância de dias já na duplicata provável:** pegaria mais casos, mas esconderia gastos reais parecidos (assinaturas mensais de mesmo valor). Por isso o dia vizinho só gera "possível", com decisão humana.
- **Deduplicar entre contas:** a mesma transferência aparece nos dois extratos e é, de fato, uma de cada lado; tratá-la como duplicata perderia dinheiro.
- **Fuzzy match por similaridade de texto:** mais recall, menos explicabilidade (o usuário não saberia por que a linha foi marcada). Fica como evolução, se os dados mostrarem necessidade.

## Consequências
- Falso positivo só é possível na camada "provável" (mesmo valor, dia e texto de outro lançamento real), e a contagem de ocorrências e a regra dos identificadores diferentes já o reduzem; falso negativo ocorre quando a descrição muda entre as fontes (cai em "possível" no máximo, ou em "nova").
- **Transferências entre contas próprias** aparecem como receita numa conta e despesa na outra; a detecção automática é trabalho do M4. Quando a transferência foi lançada à mão, o extrato a mostra como possível duplicata.
- Os candidatos vêm de **duas consultas** (as transações ativas no intervalo de datas do arquivo, com um dia de folga, e as que têm os identificadores externos do arquivo em qualquer data), e a comparação é feita em memória, em tempo linear: 20 mil linhas contra 20 mil transações levam frações de segundo.
- A normalização fica num único lugar (`TextNorm.key`), a mesma para as linhas do arquivo e para as transações existentes.
- O resultado não depende da ordem das linhas nem da ordem das transações existentes (há teste para isso).
