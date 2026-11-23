# Especificação de Escopo

## Escopo do produto

**Dentro (MVP, milestones 1 a 5):**
- Autenticação, com RBAC USER/ADMIN.
- Contas e carteiras com liquidez distinta (conta corrente, VA, VR, investimentos etc.).
- Transações com categoria **e** contexto.
- Importação OFX e CSV, com normalização e deduplicação.
- PIX Intelligence: entidade de favorecido, regras e categorização automática com confirmação em caso de baixa confiança.
- Dashboard: saldo, gastos mensais, distribuição por categoria e contexto, VA/VR e caixa disponível.

**Depois do MVP:** serviço de ML em Python (previsão, anomalias, insights), orçamentos e metas, capacidade de investimento, IoT (ESP32, OLED, impressora térmica) e Financial Twin.

## Não escopo
- Integração Open Finance e agregação bancária direta.
- Recomendação de ativos individuais de investimento.
- Movimentação de dinheiro, pagamentos ou qualquer operação transacional bancária.
- App nativo mobile (o PWA cobre).

## Escopo do projeto (trabalho)
Design (SDD, ADRs), implementação por milestone, testes automatizados, CI, documentação e publicação open source.

## Premissas e restrições
Ver [Termo de Abertura](Termo_de_Abertura.md).

## Critérios de aceite por milestone
Cada milestone é aceito quando: (1) as funcionalidades listadas estão implementadas, (2) os testes automatizados passam no CI, (3) a documentação da pasta correspondente está atualizada e (4) há uma demonstração com dados sintéticos.
