"""Gera o conjunto FICTÍCIO com verdade conhecida para medir a classificação (M4) em datasets/synthetic/classification.

Uso (a partir da raiz do repositório):  python3 tools/gen-synthetic/gen_classification.py

Determinístico (semente fixa): rodar de novo gera o mesmo arquivo, byte a byte. Nenhum dado real: nomes, estabelecimentos
e valores são inventados. O arquivo é um FLUXO ordenado de transações, cada uma com a categoria e o contexto corretos
("verdade"). O teste de precisão do backend reproduz o fluxo: o motor classifica cada uma, e quando a decisão é só sugerir
ou nenhuma, simula o usuário confirmando a verdade (é assim que os favorecidos aprendem). Decisões automáticas nunca
ensinam e nunca são corrigidas na simulação: é o pior caso para a precisão.

O conjunto inclui, de propósito, o que atrapalha um classificador: favorecidos com comportamento misto (90% e 70%
constante), palavras-chave que enganam ("POSTO CULTURAL"), nomes que casam com mais de uma regra ("UBER EATS") e
estabelecimentos que nenhuma regra conhece (o motor precisa abster-se, não chutar).
"""
import json
import os
import random

SEED = 20261124
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "datasets", "synthetic", "classification")
rng = random.Random(SEED)

NOMES = ["BOM PRECO", "ESTRELA", "CENTRAL", "VILA NOVA", "DO BAIRRO", "SAO JORGE", "ALVORADA", "HORIZONTE", "PRIMAVERA",
         "NORTE SUL", "BELA VISTA", "SANTA RITA", "MODELO", "UNIAO", "PORTAL"]
CIDADES = ["SAO PAULO BR", "CAMPINAS BR", "CURITIBA BR", "RECIFE BR", "BELO HORIZONTE BR"]


def ruido():
    return rng.choice(["", "", " " + str(rng.randint(100, 999999)), " %02d/%02d" % (rng.randint(1, 28), rng.randint(1, 12)),
                       " " + rng.choice(CIDADES)])


def nome():
    return rng.choice(NOMES)


# (categoria, tipo, [modelos]) cobertos pelas regras padrão do sistema
COBERTOS = [
    ("FOOD_GROCERIES", "EXPENSE", ["COMPRA CARTAO SUPERMERCADO {n}", "SUPERMERCADO {n}", "HORTIFRUTI {n}", "ACOUGUE {n}"]),
    ("FOOD_SNACKS", "EXPENSE", ["PADARIA {n}", "COMPRA PADARIA {n}", "LANCHONETE {n}"]),
    ("HEALTH_PHARMACY", "EXPENSE", ["FARMACIA {n}", "DROGARIA {n}", "COMPRA FARMACIA {n}"]),
    ("TRANSPORT_FUEL", "EXPENSE", ["POSTO {n} COMBUSTIVEL", "POSTO {n}", "COMPRA POSTO {n}"]),
    ("TRANSPORT_RIDE", "EXPENSE", ["UBER *TRIP", "UBER TRIP", "UBER *TRIP HELP"]),
    ("FOOD_DELIVERY", "EXPENSE", ["IFOOD *{n}", "RAPPI {n}", "UBER EATS {n}"]),
    ("FOOD_RESTAURANT", "EXPENSE", ["RESTAURANTE {n}", "PIZZARIA {n}", "CHURRASCARIA {n}"]),
    ("LEISURE_STREAMING", "EXPENSE", ["NETFLIX.COM", "SPOTIFY", "DISNEY PLUS"]),
    ("HOUSING_CONDO", "EXPENSE", ["CONDOMINIO RESIDENCIAL {n}", "PAGAMENTO CONDOMINIO {n}"]),
    ("HOUSING_RENT", "EXPENSE", ["ALUGUEL APTO {n}", "PAGAMENTO ALUGUEL {n}"]),
    ("HOUSING_ENERGY", "EXPENSE", ["CONTA DE ENERGIA {n}", "ENERGIA ELETRICA {n}"]),
    ("HOUSING_INTERNET", "EXPENSE", ["INTERNET FIBRA {n}", "INTERNET {n}"]),
    ("HOUSING_WATER", "EXPENSE", ["SABESP", "SANEAMENTO {n}"]),
    ("HEALTH_INSURANCE", "EXPENSE", ["PLANO DE SAUDE {n}", "UNIMED {n}"]),
    ("HEALTH_APPOINTMENT", "EXPENSE", ["CONSULTA {n}", "POSTO DE SAUDE {n}"]),
    ("EDUCATION_COURSES", "EXPENSE", ["CURSO {n}", "UDEMY", "ALURA"]),
    ("TRANSPORT_VEHICLE", "EXPENSE", ["PEDAGIO {n}", "ESTACIONAMENTO {n}"]),
    ("FEES_BANK", "EXPENSE", ["TARIFA PACOTE DE SERVICOS", "TARIFA {n}"]),
    ("INCOME_SALARY", "INCOME", ["SALARIO {n} LTDA", "CREDITO SALARIO"]),
    ("INCOME_YIELD", "INCOME", ["RENDIMENTO POUPANCA", "RENDIMENTOS CDB"]),
]

# estabelecimentos que nenhuma regra padrão conhece: o motor deve se abster
DESCONHECIDOS = [
    ("SHOPPING_CLOTHES", "EXPENSE", ["LOJA {n} MODA", "BOUTIQUE {n}"]),
    ("SHOPPING_ELECTRONICS", "EXPENSE", ["ELETRO {n}", "INFORMATICA {n}"]),
    ("LEISURE_EVENTS", "EXPENSE", ["CINEMA {n}", "INGRESSO TEATRO {n}"]),
    ("HEALTH_WELLNESS", "EXPENSE", ["STUDIO PILATES {n}", "ACADEMIA {n}"]),
    ("FOOD_RESTAURANT", "EXPENSE", ["BAR DO {n}", "BOTECO {n}"]),
    ("INCOME_FREELANCE", "INCOME", ["RECEBIMENTO PROJETO {n}", "HONORARIOS {n}"]),
]

# palavra-chave que engana: o motor erra de propósito (conta contra a precisão)
ARMADILHAS = [("LEISURE_EVENTS", "EXPENSE", ["POSTO CULTURAL {n}"])]

# regras do usuário (precisam existir no teste): uma delas sobrepõe uma regra padrão
REGRAS_USUARIO = [
    {"matchType": "CONTAINS", "pattern": "mercadinho do ze", "type": "EXPENSE", "category": "FOOD_GROCERIES", "context": None},
    {"matchType": "STARTS_WITH", "pattern": "academia forca", "type": "EXPENSE", "category": "HEALTH_WELLNESS", "context": None},
    {"matchType": "CONTAINS", "pattern": "racao", "type": "EXPENSE", "category": "SHOPPING_HOME", "context": "PET"},
]
DO_USUARIO = [
    ("FOOD_GROCERIES", "EXPENSE", None, ["MERCADINHO DO ZE", "COMPRA MERCADINHO DO ZE"]),
    ("HEALTH_WELLNESS", "EXPENSE", None, ["ACADEMIA FORCA TOTAL", "ACADEMIA FORCA TOTAL MENSALIDADE"]),
    ("SHOPPING_HOME", "EXPENSE", "PET", ["COMPRA RACAO PREMIUM", "RACAO E PETISCOS"]),
]

# favorecidos PIX: (nome, direção, categoria, contexto, constância, categoria alternativa)
FAVORECIDOS = [
    ("JOANA EXEMPLO", "OUT", "LEISURE_EVENTS", "RELATIONSHIP", 1.0, None),
    ("CAFE DA PRACA", "OUT", "FOOD_SNACKS", "INDIVIDUAL", 1.0, None),
    ("PEDRO HENRIQUE LIMA", "OUT", "FOOD_RESTAURANT", "FAMILY", 1.0, None),
    ("MARIA DA SILVA SOUZA", "OUT", "HOUSING_MAINTENANCE", "FAMILY", 1.0, None),
    ("CARLOS ALBERTO MENDES", "OUT", "TRANSPORT_VEHICLE", "INDIVIDUAL", 1.0, None),
    ("PADARIA SAO JOAO", "OUT", "FOOD_SNACKS", "INDIVIDUAL", 1.0, None),
    ("CLINICA VET AMIGO FIEL", "OUT", "HEALTH_APPOINTMENT", "PET", 1.0, None),
    ("ANA PAULA RIBEIRO", "OUT", "FOOD_RESTAURANT", "RELATIONSHIP", 1.0, None),
    ("LOJA DO ZE", "OUT", "SHOPPING_HOME", "FAMILY", 1.0, None),
    ("TATIANE COSTA", "OUT", "EDUCATION_COURSES", "FAMILY", 1.0, None),
    ("FEIRA LIVRE DO TIO", "OUT", "FOOD_GROCERIES", "INDIVIDUAL", 1.0, None),
    ("ROBERTO ALMEIDA ME", "OUT", "WORK", "WORK", 1.0, None),
    ("SALAO BELA IMAGEM", "OUT", "HEALTH_WELLNESS", "INDIVIDUAL", 1.0, None),
    ("PET SHOP AUMIGOS", "OUT", "SHOPPING_HOME", "PET", 1.0, None),
    ("LUCAS FERREIRA GOMES", "OUT", "LEISURE_EVENTS", "INDIVIDUAL", 1.0, None),
    ("EMPRESA EXEMPLO LTDA", "IN", "INCOME_SALARY", "WORK", 1.0, None),
    ("CLIENTE PROJETO ALFA", "IN", "INCOME_FREELANCE", "WORK", 1.0, None),
    ("CLIENTE PROJETO BETA", "IN", "INCOME_FREELANCE", "WORK", 1.0, None),
    ("MARIA DA SILVA SOUZA", "IN", "INCOME_OTHER", "FAMILY", 1.0, None),
    ("JOANA EXEMPLO", "IN", "INCOME_OTHER", "RELATIONSHIP", 1.0, None),
    ("BRUNO TEIXEIRA", "IN", "INCOME_OTHER", "INDIVIDUAL", 1.0, None),
    # comportamento misto: a confiança nunca deve chegar longe de 1
    ("MERCADO DA ESQUINA", "OUT", "FOOD_GROCERIES", "FAMILY", 0.9, "FOOD_SNACKS"),
    ("RODRIGO MARTINS", "OUT", "LEISURE_EVENTS", "INDIVIDUAL", 0.9, "FOOD_RESTAURANT"),
    ("GABRIELA NUNES", "OUT", "FOOD_RESTAURANT", "RELATIONSHIP", 0.9, "LEISURE_EVENTS"),
    ("SERVICOS GERAIS BARROS", "OUT", "HOUSING_MAINTENANCE", "FAMILY", 0.7, "SHOPPING_HOME"),
    ("VINICIUS ARAUJO", "OUT", "LEISURE_EVENTS", "INDIVIDUAL", 0.7, "FOOD_RESTAURANT"),
]
PIX_OCORRENCIAS = 14


def pix_descricao(nome, direcao):
    cap = nome.title()
    suf = rng.choice(["", "", " " + str(rng.randint(10, 99)) + "/0" + str(rng.randint(1, 9)), " ***.%03d.%03d-**" % (rng.randint(0, 999), rng.randint(0, 999))])
    if direcao == "OUT":
        modelo = rng.choice(["PIX ENVIADO {N}", "Pix enviado - {C}", "TRANSFERENCIA PIX {N}", "PIX TRANSF {N}", "PAGAMENTO PIX QR CODE {N}"])
    else:
        modelo = rng.choice(["PIX RECEBIDO {N}", "Pix recebido - {C}", "PIX RECEBIDO DE {N}", "TRANSFERENCIA PIX RECEBIDA {N}"])
    if "RECEBIDA" in modelo:           # "recebida" vem antes do nome em "TRANSFERENCIA PIX RECEBIDA NOME": é palavra de modelo
        pass
    return modelo.replace("{N}", nome).replace("{C}", cap) + suf


casos = []
for cat, tipo, modelos in COBERTOS:
    for _ in range(14):
        casos.append({"kind": "merchant", "description": rng.choice(modelos).replace("{n}", nome()) + ruido(), "type": tipo, "category": cat, "context": None})
for cat, tipo, modelos in DESCONHECIDOS:
    for _ in range(14):
        casos.append({"kind": "unknown", "description": rng.choice(modelos).replace("{n}", nome()) + ruido(), "type": tipo, "category": cat, "context": None})
for cat, tipo, modelos in ARMADILHAS:
    for _ in range(8):
        casos.append({"kind": "trap", "description": rng.choice(modelos).replace("{n}", nome()) + ruido(), "type": tipo, "category": cat, "context": None})
for cat, tipo, ctx, modelos in DO_USUARIO:
    for _ in range(10):
        casos.append({"kind": "userRule", "description": rng.choice(modelos) + ruido(), "type": tipo, "category": cat, "context": ctx})
for nome_f, direcao, cat, ctx, p, alt in FAVORECIDOS:
    for _ in range(PIX_OCORRENCIAS):
        c = cat if (p >= 1.0 or rng.random() < p) else alt
        casos.append({"kind": "pix", "description": pix_descricao(nome_f, direcao), "type": "EXPENSE" if direcao == "OUT" else "INCOME", "category": c, "context": ctx})

rng.shuffle(casos)

# o favorecido compartilhado entre OUT e IN ("JOANA EXEMPLO", "MARIA DA SILVA SOUZA") é o mesmo favorecido para o motor:
# a verdade muda com a direção, então o conjunto inclui esse caso difícil de propósito.
doc = {"version": 1, "fictional": True, "seed": SEED, "userRules": REGRAS_USUARIO, "cases": casos}
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "cases.json"), "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("casos:", len(casos))
