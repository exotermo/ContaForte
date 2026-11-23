# Dados sintéticos

`scenario.json` é o cenário **fictício** que o `./cf seed` carrega num usuário demo: 7 contas e 154 transações dos últimos 90 dias (datas relativas a "hoje"), cobrindo todos os tipos de conta e de transação, com VA/VR, cartão de crédito, investimento, estorno e contextos.

- **Nunca** coloque aqui dados reais (extratos, CPFs, chaves PIX, tokens). A pasta `datasets/real/` é ignorada pelo Git de propósito.
- O arquivo é gerado por `python3 tools/gen-synthetic/gen_synthetic.py` com semente fixa: rodar de novo produz exatamente o mesmo arquivo. Não edite o JSON à mão.
- Os valores são centavos inteiros no gerador (sem ponto flutuante), nenhuma conta comum fica negativa em nenhum momento, e o campo `expected` traz o resumo que o backend precisa devolver depois do carregamento. O `./cf seed` confere isso.
- O CI regenera o arquivo e falha se ele estiver diferente do versionado.
