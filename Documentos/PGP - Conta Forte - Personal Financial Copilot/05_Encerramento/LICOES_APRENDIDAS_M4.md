# Lições aprendidas — Milestone 4 (PIX Intelligence)

## Entregue
Classificação de categoria e contexto em estágios (regra do usuário, favorecido PIX conhecido e 40 regras padrão), com confiança calibrada, decisão em três faixas (aplicar, sugerir, calar), fila de Revisão, regras e favorecidos editáveis, feedback gravado e revertido ao desfazer uma importação, reclassificação do histórico em duas fases, demonstração que se autoverifica, métricas e painel. Números ao fechar: **617 testes de backend**, **229 de frontend**, **19 E2E no navegador**, mais os de Go; precisão de **98,1%** nas categorias e **100%** nos contextos aplicados sozinhos (766 casos sintéticos); 20 mil linhas classificadas pelo motor em cerca de 0,4 s, prévia completa em 3,6 a 4,1 s e pico de 500 MiB nos 768 MiB do backend.

## O que funcionou
- **Decidir antes de codar (ADR-017/018):** "primeiro estágio com confiança ≥ 0,50 vence", os limiares e a regra de que só decisão do usuário ensina foram escritos antes do código. As seis etapas só aplicaram isso.
- **Motor puro:** sem banco nem relógio, o motor é testado com tabelas de casos e roda 20 mil linhas em frações de segundo. Toda a parte de persistência ficou fora dele.
- **Confiança como fração exata:** comparar `k/(n+1)` com 0,85 sem arredondar acabou com a classe de bug "0,849 virou 0,85". O valor gravado é truncado, nunca arredondado para cima.
- **Medir a precisão em vez de achar:** o conjunto sintético com resposta conhecida mostrou onde as regras padrão erravam (palavras genéricas) antes de qualquer tela, e virou uma trava no CI.
- **Suavização contra poucos casos:** com `k/(n+1)`, uma ou duas confirmações só sugerem. O automático exige cerca de seis, e isso se ensina na demonstração.
- **Privacidade desde o desenho:** nomes de favorecidos só por usuário, fora de log, métrica e auditoria, com "esquecer favorecido" na tela.
- **Autoverificação no seed e no bench:** o seed confere 9 automáticas e 2 sugeridas; o bench confere a contagem exata de linhas categorizadas. Foi isso que mostrou que o bench antigo nunca exercitava o motor.
- **Integridade no banco:** restrições que ligam estado, origem e sugestão (`ck_transactions_suggestion`), chaves compostas por dono e gatilhos com `WHEN` impediram estados impossíveis mesmo com bug de aplicação.

## O que deu trabalho
| Problema | Causa | Solução |
|---|---|---|
| `PIX-ENVIADO` colado não era separado do nome | extrator só cortava prefixos com espaço | `splitPixPrefix` com teste |
| Favorecido que recebe e envia contaminava os votos | um só conjunto de votos por favorecido | V8: votos por direção; fixação (`pinned_*`) removida |
| PATCH em transação com sugestão violava a restrição | edição manual não passava pelo serviço de classificação | toda edição manual passa por `ClassificationService` e vira decisão do usuário |
| Editar regra devolvia 500 (índice único) | auto-flush do JPA antes da validação | validar com os valores novos antes de mexer na entidade |
| Busca de favorecido só com pontuação listava todos | padrão vazio depois de normalizar | resultado vazio |
| Prévia 1,3 a 2 s mais lenta | suspeita: gatilhos; realidade: custo de inserção pela camada JPA (SQL puro: 440 ms para 20 mil linhas) | A/B medido; só mantive a cláusula `WHEN` nos gatilhos |
| Docker quebrou no meio da etapa | `dnf update` no host desalinhou o containerd | reiniciar o daemon (feito pelo usuário); nada era do projeto |
| Selo da Revisão sem nome acessível | `aria-label` em `span` é ignorado | texto `sr-only` |
| Checkbox otimista voltava ao estado antigo | React restaura o controlado antes da mutação assíncrona | estado local `asked` |
| E2E: seletores ambíguos | região de anúncio de rota (`role=alert`) e "Categoria" também no filtro | seletores por papel e nome |
| "Joana E2E" virava só "Joana" | "E2E" é palavra de ruído do extrator | usei o nome normalizado nas asserções |
| Menu no celular empilhado em coluna | cabeçalho com tudo na mesma linha | navegação em segunda linha própria |
| Métrica de reclassificação contava tudo como "sem decisão" | decisão lida depois de aplicar, quando a categoria já estava preenchida | ler a decisão efetiva **antes** de aplicar |
| Teste de "só rótulos fechados" quebrou | Micrometer acrescenta a etiqueta comum `application` | o teste ignora essa etiqueta |
| Bench não exercitava o motor | descrições sem nenhuma regra correspondente | CSV com descrições conhecidas e contagem exata esperada |
| E2E deixava usuários para trás | contas descartáveis fora do prefixo de limpeza | `e2e-<hora>-N@contaforte.local` e limpeza que apaga `users` com esse prefixo |
| Primeiro incremento de série nova invisível no Prometheus | mesma limitação do M2 e do M3 | todos os contadores registrados em 0 na partida |

## Decisões que valem lembrar
- Só decisão do usuário ensina. O que o sistema aplicou sozinho nunca reforça a si mesmo.
- Em cada dimensão (categoria e contexto) vence o primeiro estágio com confiança ≥ 0,50; o resumo da decisão é o elo mais fraco.
- Regra casa palavra inteira em texto normalizado; entre as padrão, a mais específica (mais longa) ganha.
- Automático ≥ 0,85, sugestão de 0,50 a menos de 0,85, o resto fica sem palpite. Sugestão fica guardada à parte e nunca vira categoria sem decisão.
- Desfazer uma importação desfaz também o aprendizado dela.
- Reclassificar é em duas fases e devolve 409 se o cenário mudou entre a prévia e a aplicação (teto de 20 mil transações).
- Conciliação de transferência entre contas próprias continua fora: dívida sem milestone.

## Dívidas conhecidas (candidatas a milestones futuros)
- Apagar (exclusão lógica) uma transação não retira o aprendizado dela, e a média do favorecido não é recalculada ao retirar um voto.
- A reclassificação para em 20 mil transações (a prévia avisa).
- "Aplicar a escolha a todas as linhas do mesmo favorecido" não existe: uma linha por vez.
- A regra "sempre assim" só existe na Revisão; na prévia da importação a escolha vale para a linha.
- Os contadores `autoClassified` e `suggested` da prévia só contam linhas novas e possíveis duplicatas.
- A métrica conta linhas classificadas na prévia (inclusive de prévias descartadas), não transações criadas; não há métrica de sugestões pendentes ao longo do tempo.
- Transferência entre contas próprias importada ainda aparece como receita e despesa; sem teste com leitor de tela.
