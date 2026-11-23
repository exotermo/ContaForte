# Contribuindo

Obrigado pelo interesse! O projeto está em fase de design, então por enquanto as contribuições mais úteis são **revisão do SDD, ADRs e requisitos** (abra uma issue).

## Regras

1. **Nunca envie dados financeiros reais** (extratos, CPFs, chaves PIX, tokens). Use apenas dados sintéticos em `datasets/synthetic`.
2. Uma mudança por PR, com descrição do porquê.
3. Decisões arquiteturais novas viram um ADR em `Documentos/.../02_Arquitetura/Arquitetura/ADR/`.
4. Valores monetários usam `BigDecimal`/`NUMERIC`, nunca `float`/`double`.
5. Recomendações financeiras precisam ser explicáveis, com evidências nos dados.
6. Commits curtos e claros, de preferência no estilo Conventional Commits (`feat:`, `fix:`, `docs:`).

Ao contribuir, você concorda que sua contribuição será licenciada sob a [AGPL-3.0](LICENSE).
