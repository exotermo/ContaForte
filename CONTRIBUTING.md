# Contribuindo

Obrigado pelo interesse! A fundação (Milestone 1) está pronta e o desenvolvimento segue pelos milestones do roadmap. As contribuições mais úteis agora são **revisão do SDD e dos ADRs, testes e relatos de bugs** (abra uma issue). Para rodar e testar localmente, veja o [guia de desenvolvimento](Documentos/PGP%20-%20Conta%20Forte%20-%20Personal%20Financial%20Copilot/03_Desenvolvimento/DEVELOPMENT.md).

## Regras

1. **Nunca envie dados financeiros reais** (extratos, CPFs, chaves PIX, tokens). Use apenas dados sintéticos em `datasets/synthetic`.
2. Uma mudança por PR, com descrição do porquê.
3. Decisões arquiteturais novas viram um ADR em `Documentos/.../02_Arquitetura/Arquitetura/ADR/`.
4. Valores monetários usam `BigDecimal`/`NUMERIC`, nunca `float`/`double`.
5. Recomendações financeiras precisam ser explicáveis, com evidências nos dados.
6. Commits curtos e claros, de preferência no estilo Conventional Commits (`feat:`, `fix:`, `docs:`).

Ao contribuir, você concorda que sua contribuição será licenciada sob a [AGPL-3.0](LICENSE).
