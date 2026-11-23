# Termo de Abertura do Projeto

**Projeto:** ContaForte — Personal Financial Copilot
**Versão:** 1.0 · **Data:** 2026-11-23
**Patrocinador e gerente:** mantenedor do projeto (Alexandre)
**Licença:** AGPL-3.0 · **Repositório:** https://github.com/exotermo/ContaForte

## 1. Objetivo
Construir uma plataforma open source de finanças pessoais que mantenha um modelo financeiro contextual do usuário: importa transações (PIX, OFX, CSV), categoriza com ML explicável, separa dinheiro líquido de VA/VR, projeta fluxo de caixa, detecta anomalias e recomenda ações.

## 2. Motivação
- Ajudar pessoas a entender e controlar suas finanças com privacidade (dados sob controle do usuário, auto-hospedável).
- Servir como projeto de portfólio público, com arquitetura, testes e documentação completos.

## 3. Benefícios esperados
- Ferramenta útil e gratuita para o contexto brasileiro (PIX, VA/VR, LGPD).
- Referência de arquitetura: monólito modular, ML isolado, event-driven e Zero Trust.

## 4. Escopo inicial
Ver [Especificação de Escopo](Especificacao_de_Escopo.md). Prioridade: milestones 1 a 5 do SDD.

## 5. Premissas
- Desenvolvimento por uma pessoa, em tempo parcial.
- Dados de teste são sempre sintéticos.
- Importação por OFX/CSV precede qualquer integração Open Finance.

## 6. Restrições
- Sem orçamento de infraestrutura além de um servidor doméstico ou VPS pequeno.
- Conformidade com a LGPD desde o início.
- Open Finance exige credenciamento regulatório, e por isso está fora do MVP.

## 7. Marcos de entrega
| Marco | Entrega |
|---|---|
| M1 Foundation | Stack sobe com `docker compose up`, login funcionando e CI verde |
| M2 Financial Core | CRUD de contas e transações com categorias e contextos |
| M3 Imports | Importação OFX/CSV com deduplicação |
| M4 PIX Intelligence | Categorização por regras e favorecidos, com feedback |
| M5 Dashboard | Painel de saldo, gastos e VA/VR |
| M6–M9 | Inteligência, metas, IoT, Financial Twin |

## 8. Riscos
Ver [Riscos](Riscos.md).

## 9. Custos
Zero de licenças. Custo recorrente opcional de domínio, Cloudflare (plano gratuito) e VPS.

## 10. Aprovação
Aprovado pelo mantenedor em 2026-11-23.
