# ADR-017 — Classificação em estágios, com limiares de confiança e aprendizado só por confirmação

**Status:** Aceito · **Data:** 2026-11-24

## Contexto
O Milestone 4 faz o sistema sugerir categoria e contexto para transações importadas e manuais (SDD §12–14). Aplicar uma categoria errada esconde dinheiro no lugar errado e o usuário confia no que vê; pedir confirmação de tudo, por outro lado, torna a importação cansativa. O SDD pede "alta confiança classifica sozinho, baixa pede confirmação", mas não define os limiares (lacuna registrada). Ainda não há ML (Milestone 6).

## Decisão
- **Estágios, na ordem do SDD §13; o primeiro que decide vence:** (1) regra do usuário, (2) favorecido PIX conhecido, (3) regra padrão do sistema (poucas palavras-chave genéricas, em pt-BR), (4) nenhuma sugestão. **Cada dimensão decide separadamente** (categoria e contexto): o primeiro estágio com confiança de pelo menos 0,50 para aquela dimensão decide, e a transação tem a origem e a confiança do elo mais fraco. O classificador de ML do Milestone 6 entra como mais um estágio, antes do 4, sem mudar a API nem o banco.
- **Três decisões possíveis**, pela confiança (de 0 a 1):
  - **AUTO, de 0,85 em diante:** categoria e/ou contexto são aplicados e a transação fica marcada como automática.
  - **SUGERIR, de 0,50 até abaixo de 0,85:** nada é aplicado; a sugestão fica guardada e a transação vai para a fila de revisão.
  - **NENHUMA, abaixo de 0,50:** a transação segue sem categoria.
- **Confiança por origem:** regra do usuário vale **1,0**; regra padrão vale **0,90**; favorecido aprendido usa a fórmula abaixo. Fixar a categoria de um favorecido é criar uma regra do usuário com o nome dele e o filtro de tipo (receita ou despesa), não um campo à parte.
- **Favorecido aprendido, por direção e com suavização:** os votos são separados por direção (despesa e receita): o que você paga à Joana e o que ela te paga de volta são categorias diferentes.  com `n` confirmações do usuário e `k` delas na categoria mais votada, `confiança = k / (n + 1)`. Um caso dá 0,50 (só sugere), quatro concordantes 0,80, seis 0,86 (passa a aplicar). Discordância derruba a confiança sozinha. O mesmo vale para o contexto, com os votos dele, **separadamente da categoria** ([ADR-009](009-categorias-separadas-de-contexto.md)).
- **Só a decisão do usuário ensina.** Confirmar uma sugestão ou corrigir uma categoria soma um voto; classificação automática **nunca** realimenta o aprendizado (senão um erro inicial se confirmaria sozinho).
- **Nunca sobrescrever uma escolha do usuário.** O motor só preenche categoria e contexto vazios. Reclassificar transações antigas é sempre em duas fases (prévia, depois aplicar), como a importação ([ADR-015](015-importacao-em-duas-fases-sem-guardar-o-arquivo.md)).
- **Estado na transação** (`classification_status`): `NONE` (nunca passou pelo motor; é o de todas as anteriores ao M4), `AUTO`, `SUGGESTED`, `CONFIRMED`, com origem (`USER`, `RULE`, `DEFAULT_RULE`, `RECIPIENT`) e confiança. O banco impõe as combinações válidas (por exemplo, AUTO e SUGGESTED nunca vêm do usuário, e a sugestão nunca cobre um campo já preenchido).
- **Feedback** (RF-10): cada confirmação ou correção grava um registro com categoria e contexto anteriores e corrigidos, origem e confiança da previsão e versão do motor. É o dataset do Milestone 6.
- **A confiança do favorecido não é guardada:** é calculada dos votos a cada uso, então nunca fica desatualizada.
- **Determinismo:** o mesmo conjunto de regras e votos dá sempre o mesmo resultado; empate entre regras se resolve por prioridade, depois por data de criação, depois por id.

- **Regras comparam por palavras inteiras** sobre o texto normalizado (sem acento, caixa e pontuação): "posto" casa com "COMPRA POSTO ABC", não com "COMPOSTO". Entre as regras padrão, a de padrão mais longo vence ("uber eats" antes de "uber", "posto de saude" antes de "posto"). Só receita e despesa são classificadas.
- **A confiança é uma fração exata**, comparada com os limiares sem arredondar (6/7 aplica, 84/100 só sugere); o valor gravado é truncado em 2 casas, nunca para cima.

## Alternativas descartadas
- **Aplicar sempre o melhor palpite, com opção de desfazer:** esconde erro silencioso; um saldo por categoria errado parece certo.
- **Limiar único (aplica ou não):** perde a faixa útil do meio, onde a sugestão economiza trabalho sem risco.
- **Confiança = proporção simples `k / n`:** um único caso daria 1,0 e passaria a aplicar sozinho; a suavização impede automático com poucos exemplos.
- **Aprender também com o que foi aplicado automaticamente:** ciclo de autoconfirmação.
- **ML já no Milestone 4:** sem dados do próprio usuário para treinar; as regras e os favorecidos geram o dataset primeiro.

## Consequências
- Cobertura baixa no início: a maioria das primeiras importações cai em "sugerir" ou "nenhuma". É o preço de não errar em silêncio; a cobertura sobe com as confirmações.
- Os limiares (0,50 e 0,85) são constantes do motor, cobertas por teste nos valores exatos; mudá-los é decisão registrada aqui, não ajuste solto.
- A precisão das classificações AUTO é medida num conjunto sintético com verdade conhecida (meta: 95% ou mais) a cada execução dos testes.
