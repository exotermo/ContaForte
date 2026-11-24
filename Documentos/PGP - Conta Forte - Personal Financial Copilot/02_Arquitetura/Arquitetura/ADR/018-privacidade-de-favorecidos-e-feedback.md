# ADR-018 — Privacidade de favorecidos PIX e do feedback de classificação

**Status:** Aceito · **Data:** 2026-11-24

## Contexto
Para aprender "PIX para Joana é Relacionamento", o sistema precisa guardar o nome de quem recebeu ou enviou. É **dado pessoal de terceiro**, que não consentiu e pode nem saber que está num app. O SDD §12 prevê também um `documentHash` do favorecido. O feedback das correções (SDD §14) liga transações a categorias e será usado para treinar modelos no futuro.

## Decisão
- **O favorecido é por usuário e só dele.** Nenhum dado de favorecido é compartilhado entre usuários, nem agregado entre eles. A chave é única por `(usuário, nome normalizado)`.
- **Mínimo necessário:** guardam-se o nome normalizado (chave), o nome para mostrar, as contagens, a média e a data da última transação. Não se guarda o texto original do extrato, o identificador PIX, a conta, o documento nem a instituição.
- **`documentHash` fica fora.** Extratos trazem o documento mascarado ou parcial; um hash de dado parcial identifica mal (colisões entre pessoas) e não traz ganho para a classificação, que já funciona pelo nome. Registrado como dívida, a rever se o Open Finance trouxer o documento completo.
- **Esquecer é um clique.** A tela de favorecidos permite apagar um favorecido (e seus votos); o que foi aprendido some. Apagar o usuário apaga tudo, em cascata (LGPD), como no restante do modelo.
- **Nada de favorecido em log, métrica, auditoria ou evento:** a auditoria registra só ids e contagens, as métricas só origem e decisão (`RULE`, `RECIPIENT`...; `AUTO`, `SUGGESTED`...). Mensagens de erro nunca citam o nome. O extrator de nomes não registra nada.
- **O feedback guarda ids, não texto:** transação, categorias e contextos anteriores e corrigidos, origem, confiança e versão do motor. O nome do favorecido e a descrição nunca são copiados para ele. Ele acompanha a transação: apagada a transação (ou o usuário), o feedback some.
- **Desfazer uma importação reverte o aprendizado dela:** os votos vindos das transações desfeitas são retirados e o feedback correspondente fica marcado (`reverted_at`), em vez de apagado.
- **Isolamento no banco**, como nos demais módulos ([ADR-013](013-saldo-derivado-e-classes-de-liquidez.md)): categoria e contexto de favorecidos, regras, votos e feedback precisam ser padrão do sistema ou do mesmo dono (gatilhos), e o feedback só referencia transação do mesmo usuário (chave estrangeira composta).

## Alternativas descartadas
- **Base global de favorecidos (todos os usuários):** daria sugestões melhores no começo, mas vazaria quem paga quem; incompatível com o princípio de privacidade por projeto.
- **Guardar só um hash do nome:** impediria mostrar o favorecido na tela e permitir ajustes; o nome precisa ser legível para o dono.
- **Guardar o documento (hash) para desambiguar homônimos:** ver acima; o ganho não compensa o risco com dado parcial.
- **Feedback com a descrição original:** serviria de dataset mais rico, mas copiaria dado pessoal para uma tabela de longa vida; o Milestone 6 pode reconstruir o texto a partir da transação, enquanto ela existir.

## Consequências
- Homônimos (duas pessoas com o mesmo nome) compartilham o mesmo favorecido; a categoria aprendida pode mesclar os dois. O usuário corrige, e a confiança cai sozinha com votos discordantes.
- O mesmo favorecido com grafias diferentes entre bancos ("JOANA E SILVA" e "JOANA SILVA") vira dois favorecidos; não há fusão automática (evolução possível, com decisão humana).
- Exportar ou apagar os dados do usuário (LGPD) cobre favorecidos, regras e feedback sem tratamento especial.
