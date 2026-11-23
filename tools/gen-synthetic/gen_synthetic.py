"""Gera o cenário sintético do ContaForte em datasets/synthetic/scenario.json.

Uso (a partir da raiz do repositório):  python3 tools/gen-synthetic/gen_synthetic.py

Tudo é FICTÍCIO e determinístico (semente fixa): rodar de novo gera o mesmo arquivo, byte a byte. Nenhum dado real
entra aqui (CONTRIBUTING, regra 1). O cenário cobre 90 dias, em 3 "meses" de 30 dias; as datas são relativas a
"hoje" (daysAgo), então o `./cf seed` sempre carrega os últimos 90 dias. Valores em centavos inteiros: nada de float.

O gerador confere invariantes (contas comuns nunca ficam negativas) e calcula o resultado esperado do /summary,
que o `./cf seed` usa para se autoverificar depois de carregar.
"""
import json
import os
import random

SEED = 20260101
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "datasets", "synthetic", "scenario.json")
rng = random.Random(SEED)

ACCOUNTS = [
    ("corrente", "Conta corrente", "CHECKING_ACCOUNT", 350000),
    ("poupanca", "Reserva de emergência", "SAVINGS_ACCOUNT", 1200000),
    ("carteira", "Carteira", "CASH", 15000),
    ("cartao", "Cartão de crédito", "CREDIT_CARD", 0),
    ("va", "Vale-alimentação", "VA", 0),
    ("vr", "Vale-refeição", "VR", 0),
    ("corretora", "Corretora", "INVESTMENT_ACCOUNT", 800000),
]
LIQUIDITY = {"CHECKING_ACCOUNT": "available", "SAVINGS_ACCOUNT": "available", "CASH": "available",
             "CREDIT_CARD": "card", "VA": "restricted", "VR": "restricted", "INVESTMENT_ACCOUNT": "invested"}
SIGN = {"INCOME": 1, "REFUND": 1, "EXPENSE": -1, "TRANSFER": -1, "INVESTMENT": -1}

txs = []


def money(cents):
    return f"{cents // 100}.{cents % 100:02d}"


def add(block, day, hour, kind, account, cents, desc, merchant=None, cat=None, ctx="INDIVIDUAL", to=None):
    """block: 2 (mais antigo) .. 0 (atual); day: 1..30 dentro do bloco."""
    txs.append({"daysAgo": block * 30 + (30 - day), "hour": hour, "type": kind, "account": account,
                "counterpart": to, "amount": money(cents), "description": desc, "merchant": merchant,
                "category": cat, "context": ctx, "_cents": cents})


def hour():
    return rng.randint(8, 21)


card_spend = {2: 0, 1: 0, 0: 0}   # gasto líquido no cartão por bloco, para pagar a fatura no mês seguinte


def card(block, day, cents, desc, merchant, cat, ctx="INDIVIDUAL"):
    add(block, day, hour(), "EXPENSE", "cartao", cents, desc, merchant, cat, ctx)
    card_spend[block] += cents


for b in (2, 1, 0):
    # --- renda e benefícios
    add(b, 1, 7, "INCOME", "va", 90000, "Recarga do vale-alimentação", "Benefício Exemplo", "INCOME_OTHER", "WORK")
    add(b, 1, 7, "INCOME", "vr", 60000, "Recarga do vale-refeição", "Benefício Exemplo", "INCOME_OTHER", "WORK")
    add(b, 5, 9, "INCOME", "corrente", 650000, "Salário", "Empresa Exemplo", "INCOME_SALARY", "WORK")
    add(b, 20, 10, "INCOME", "corretora", rng.randint(6000, 9500), "Rendimentos do mês", None, "INCOME_YIELD")
    # --- dinheiro que muda de lugar
    add(b, 3, 12, "TRANSFER", "corrente", 20000, "Saque para a carteira", None, "TRANSFERS_OWN", to="carteira")
    add(b, 6, 9, "INVESTMENT", "corrente", 80000, "Aporte mensal", None, "INVEST_FIXED", to="corretora")
    add(b, 7, 9, "TRANSFER", "corrente", 50000, "Reserva de emergência", None, "TRANSFERS_OWN", to="poupanca")
    # --- moradia e contas fixas
    add(b, 8, 9, "EXPENSE", "corrente", 180000, "Aluguel", "Imobiliária Exemplo", "HOUSING_RENT", "FAMILY")
    add(b, 8, 9, "EXPENSE", "corrente", 45000, "Condomínio", "Condomínio Exemplo", "HOUSING_CONDO", "FAMILY")
    add(b, 10, 10, "EXPENSE", "corrente", rng.randint(15000, 23000), "Conta de energia", "Energia Exemplo", "HOUSING_ENERGY", "FAMILY")
    add(b, 10, 10, "EXPENSE", "corrente", 11990, "Internet", "Provedor Exemplo", "HOUSING_INTERNET", "FAMILY")
    add(b, 7, 11, "EXPENSE", "corrente", 38000, "Plano de saúde", "Plano Exemplo", "HEALTH_INSURANCE")
    # --- mercado no VA (4 idas, soma abaixo da recarga de R$ 900) e almoços no VR
    for day in (3, 10, 17, 24):
        add(b, day, hour(), "EXPENSE", "va", rng.randint(14000, 21000), "Compras do mês", "Mercado Exemplo", "FOOD_GROCERIES", "FAMILY")
    for day in sorted(rng.sample(range(2, 30), 8)):
        add(b, day, rng.randint(11, 14), "EXPENSE", "vr", rng.randint(2800, 5800), "Almoço", "Restaurante Exemplo", "FOOD_RESTAURANT", "WORK")
    # --- cartão de crédito
    for day in sorted(rng.sample(range(2, 29), 2)):
        card(b, day, rng.randint(9000, 18000), "Jantar a dois", "Restaurante Exemplo", "FOOD_RESTAURANT", "RELATIONSHIP")
    for day in sorted(rng.sample(range(2, 29), 3)):
        card(b, day, rng.randint(3500, 7000), "Pedido de delivery", "Delivery Exemplo", "FOOD_DELIVERY")
    for day in sorted(rng.sample(range(1, 30), 6)):
        card(b, day, rng.randint(1400, 3800), "Corrida de aplicativo", "Transporte Exemplo", "TRANSPORT_RIDE")
    for day in (9, 23):
        card(b, day, rng.randint(18000, 24000), "Combustível", "Posto Exemplo", "TRANSPORT_FUEL")
    card(b, 4, 3990, "Assinatura de streaming", "Streaming Exemplo", "LEISURE_STREAMING")
    card(b, 4, 2190, "Assinatura de música", "Música Exemplo", "LEISURE_STREAMING")
    card(b, 12, 5590, "Assinatura de vídeo", "Vídeo Exemplo", "LEISURE_STREAMING")
    card(b, 12, 18990, "Curso online", "Escola Exemplo", "EDUCATION_COURSES")
    card(b, rng.randint(14, 27), rng.randint(6000, 14000), "Ingressos", "Eventos Exemplo", "LEISURE_EVENTS", "RELATIONSHIP")
    for day in (6, 20):
        card(b, day, rng.randint(9000, 16000), "Ração e petiscos", "Pet Shop Exemplo", "FOOD_GROCERIES", "PET")
    # --- saúde, pet e dinheiro em espécie
    add(b, rng.randint(11, 26), hour(), "EXPENSE", "corrente", rng.randint(3000, 12000), "Farmácia", "Farmácia Exemplo", "HEALTH_PHARMACY")
    if b != 1:
        add(b, 18, 15, "EXPENSE", "corrente", 22000, "Consulta veterinária", "Clínica Exemplo", "HEALTH_APPOINTMENT", "PET")
    for day in sorted(rng.sample(range(4, 29), 4)):
        add(b, day, hour(), "EXPENSE", "carteira", rng.randint(1200, 2500), "Lanche", "Padaria Exemplo", "FOOD_SNACKS")

# compras maiores e um estorno, para a lista ter variedade
card(1, 18, 42990, "Fone de ouvido", "Eletrônicos Exemplo", "SHOPPING_ELECTRONICS")
add(1, 25, 14, "REFUND", "cartao", 12990, "Estorno de compra", "Eletrônicos Exemplo", "SHOPPING_ELECTRONICS")
card_spend[1] -= 12990
card(0, 16, 24990, "Roupas", "Loja Exemplo", "SHOPPING_CLOTHES")

# a fatura de cada mês é paga no dia 15 do mês seguinte (blocos 1 e 0 pagam os blocos 2 e 1)
for b, prev in ((1, 2), (0, 1)):
    add(b, 15, 10, "TRANSFER", "corrente", card_spend[prev], "Pagamento da fatura do cartão", None, "TRANSFERS_CARD_BILL", to="cartao")

# ordem cronológica estável: do mais antigo ao mais recente, por hora e, no empate, pela ordem de geração
txs = [t for _, t in sorted(enumerate(txs), key=lambda p: (-p[1]["daysAgo"], p[1]["hour"], p[0]))]

# --- invariantes e resultado esperado -------------------------------------------------------------------------------
bal = {k: c for k, _, _, c in ACCOUNTS}
kind = {k: t for k, _, t, _ in ACCOUNTS}
for t in txs:
    c = t["_cents"]
    bal[t["account"]] += SIGN[t["type"]] * c
    if t["counterpart"]:
        bal[t["counterpart"]] += c
    # contas comuns nunca ficam negativas, em nenhum momento (o cartão é a dívida: pode)
    for k, v in bal.items():
        assert kind[k] == "CREDIT_CARD" or v >= 0, f"saldo negativo em {k} após {t['description']} (daysAgo={t['daysAgo']})"
assert 120 <= len(txs) <= 200, len(txs)

totals = {"available": 0, "restricted": 0, "invested": 0, "card": 0}
for k, v in bal.items():
    totals[LIQUIDITY[kind[k]]] += v
expected = {
    "availableCash": money(totals["available"]),
    "restrictedBalance": money(totals["restricted"]),
    "investedAssets": money(totals["invested"]),
    "creditCardBalance": "-" + money(-totals["card"]) if totals["card"] < 0 else money(totals["card"]),
    "totalNetWorth": money(sum(totals.values())),
}
for t in txs:
    del t["_cents"]

doc = {
    "version": 1,
    "fictional": True,
    "seed": SEED,
    "demoEmail": "demo@contaforte.local",
    "accounts": [{"key": k, "name": n, "type": t, "openingBalance": money(c)} for k, n, t, c in ACCOUNTS],
    "expected": expected,
    "transactions": txs,
}
with open(OUT, "w", encoding="utf-8") as f:
    f.write("{\n")
    for key in ("version", "fictional", "seed", "demoEmail"):
        f.write(f'  "{key}": {json.dumps(doc[key], ensure_ascii=False)},\n')
    f.write('  "accounts": [\n' + ",\n".join("    " + json.dumps(a, ensure_ascii=False) for a in doc["accounts"]) + "\n  ],\n")
    f.write(f'  "expected": {json.dumps(expected, ensure_ascii=False)},\n')
    f.write('  "transactions": [\n' + ",\n".join("    " + json.dumps(t, ensure_ascii=False) for t in txs) + "\n  ]\n}\n")

print(f"{len(txs)} transações em {OUT}")
print("saldos finais:", {k: money(v) if v >= 0 else "-" + money(-v) for k, v in bal.items()})
print("esperado:", expected)
