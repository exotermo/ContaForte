# Registro de Riscos

Escala: Probabilidade e Impacto de 1 (muito baixo) a 5 (muito alto).

| ID | Categoria | Risco | P | I | Estratégia | Ação |
|---|---|---|---|---|---|---|
| R1 | Gestão-Prazo | Escopo grande demais para uma pessoa, e o projeto estagna | 4 | 5 | Mitigar | Entregar fatias verticais, com M1–M5 antes de qualquer IoT ou ML |
| R2 | Técnico-Requisitos | Formatos OFX/CSV variam entre bancos | 4 | 3 | Mitigar | Parsers por banco, com fixtures sintéticas |
| R3 | Externo-Regulatório | Open Finance exige credenciamento | 3 | 3 | Evitar | Fora do MVP, usar OFX/CSV |
| R4 | Técnico-Segurança | Vazamento de dados financeiros ou de segredos no repo | 2 | 5 | Prevenir | `.gitignore`, secrets via ambiente, revisão antes de cada push e dados sintéticos |
| R5 | Externo-Legal | Não conformidade com a LGPD | 2 | 4 | Mitigar | Minimização, exportação e exclusão de conta e pseudonimização para ML |
| R6 | Técnico-Tecnologia | Complexidade prematura (microserviços, Kafka, ML pesado) | 3 | 3 | Prevenir | Monólito modular, estatística simples primeiro e ADRs |
| R7 | Técnico-Tecnologia | Baixa acurácia da categorização no início | 4 | 2 | Aceitar | Regras determinísticas primeiro, human-in-the-loop e feedback |
| R8 | Externo-Legal | Contaminação com conteúdo de terceiros ou empregador | 2 | 5 | Prevenir | Repositório só com material próprio e revisão antes do push |
