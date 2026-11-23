"""Gera os extratos FICTÍCIOS de teste em datasets/synthetic/imports (OFX e CSV).

Uso (a partir da raiz do repositório):  python3 tools/gen-synthetic/gen_import_fixtures.py

Tudo é inventado e determinístico: rodar de novo gera os mesmos arquivos, byte a byte. Nenhum dado real entra aqui.
Os layouts imitam padrões comuns (OFX 1.x em Windows-1252, OFX 2.x em XML, cartão de crédito, CSV com ponto e vírgula e
vírgula decimal, CSV com débito e crédito separados, CSV com tabulação, título no topo e tipo D/C), mas NÃO são cópias de
extrato de banco algum. As mesmas transações-base aparecem em vários arquivos, para testar a deduplicação entre eles.
"""
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "datasets", "synthetic", "imports")
os.makedirs(OUT, exist_ok=True)

# (dia em 2026-03, memo, nome, valor em centavos com sinal, fitid)
BASE = [
    (2, "COMPRA CARTAO MERCADO EXEMPLO", "Mercado Exemplo", -18745, "FIT0001"),
    (3, "PIX ENVIADO CAFÉ DA PRAÇA", "Café da Praça", -1850, "FIT0002"),
    (5, "CRÉDITO SALÁRIO EMPRESA EXEMPLO", "Empresa Exemplo", 650000, "FIT0003"),
    (6, "TARIFA PACOTE DE SERVIÇOS", "Banco Exemplo", -3990, "FIT0004"),
    (8, "PAGAMENTO ALUGUEL IMOBILIÁRIA EXEMPLO", "Imobiliária Exemplo", -180000, "FIT0005"),
    (10, "COMPRA PADARIA SÃO JOÃO", "Padaria São João", -2380, "FIT0006"),
    (10, "COMPRA PADARIA SÃO JOÃO", "Padaria São João", -2380, "FIT0007"),   # idêntica à anterior: dois cafés no mesmo dia
    (12, "PIX RECEBIDO JOANA EXEMPLO", "Joana Exemplo", 25000, "FIT0008"),
    (15, "DÉBITO AUTOMÁTICO ENERGIA EXEMPLO", "Energia Exemplo", -21437, "FIT0009"),
    (18, "COMPRA FARMÁCIA EXEMPLO", "Farmácia Exemplo", -6499, "FIT0010"),
    (20, "RENDIMENTO POUPANÇA", "Banco Exemplo", 1234, "FIT0011"),
    (22, "COMPRA POSTO EXEMPLO COMBUSTÍVEL", "Posto Exemplo", -21000, "FIT0012"),
]
# continuação do mês, só no segundo arquivo (que também repete as 4 últimas acima)
EXTRA = [
    (25, "SAQUE CAIXA ELETRÔNICO", "Banco Exemplo", -20000, "FIT0013"),
    (26, "COMPRA LIVRARIA EXEMPLO", "Livraria Exemplo", -8990, "FIT0014"),
    (28, "ESTORNO COMPRA FARMÁCIA", "Farmácia Exemplo", 6499, "FIT0015"),
]


def money(cents, comma=False, thousands=False):
    sign = "-" if cents < 0 else ""
    c = abs(cents)
    whole, frac = c // 100, c % 100
    w = f"{whole:,}".replace(",", ".") if thousands else str(whole)
    return f"{sign}{w}{',' if comma else '.'}{frac:02d}"


def write(name, text, encoding="utf-8", bom=False, newline="\r\n"):
    data = text.replace("\n", newline).encode(encoding)
    if bom:
        data = b"\xef\xbb\xbf" + data
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(data)


def ofx_sgml_header(charset="1252", encoding="USASCII"):
    return (f"OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nSECURITY:NONE\nENCODING:{encoding}\nCHARSET:{charset}\n"
            "COMPRESSION:NONE\nOLDFILEUID:NONE\nNEWFILEUID:NONE\n\n")


def sgml_stmt(rows, acct="12345-6", curdef="BRL", card=False):
    tag, tr = ("CCSTMTRS", "CREDITCARDMSGSRSV1") if card else ("STMTRS", "BANKMSGSRSV1")
    out = [f"<OFX>\n<SIGNONMSGSRSV1>\n<SONRS>\n<STATUS><CODE>0<SEVERITY>INFO</STATUS>\n<DTSERVER>20260331120000[-3:BRT]\n<LANGUAGE>POR\n</SONRS>\n</SIGNONMSGSRSV1>\n"
           f"<{tr}>\n<{'CCSTMTTRNRS' if card else 'STMTTRNRS'}>\n<TRNUID>1\n<STATUS><CODE>0<SEVERITY>INFO</STATUS>\n<{tag}>\n<CURDEF>{curdef}\n"]
    out.append("<CCACCTFROM>\n<ACCTID>" + acct + "\n</CCACCTFROM>\n" if card
               else "<BANKACCTFROM>\n<BANKID>0001\n<ACCTID>" + acct + "\n<ACCTTYPE>CHECKING\n</BANKACCTFROM>\n")
    out.append("<BANKTRANLIST>\n<DTSTART>20260301\n<DTEND>20260331\n")
    for day, memo, name, cents, fitid in rows:
        out.append(f"<STMTTRN>\n<TRNTYPE>{'CREDIT' if cents > 0 else 'DEBIT'}\n<DTPOSTED>202603{day:02d}120000[-3:BRT]\n"
                   f"<TRNAMT>{money(cents)}\n<FITID>{fitid}\n<NAME>{name}\n<MEMO>{memo}\n</STMTTRN>\n")
    out.append(f"</BANKTRANLIST>\n<LEDGERBAL><BALAMT>1234.56<DTASOF>20260331</LEDGERBAL>\n</{tag}>\n"
               f"</{'CCSTMTTRNRS' if card else 'STMTTRNRS'}>\n</{tr}>\n</OFX>\n")
    return "".join(out)


# 1) OFX 1.x (SGML), Windows-1252, sem tag de fechamento nos campos, data com hora e fuso
write("conta-corrente-1.ofx", ofx_sgml_header() + sgml_stmt(BASE), encoding="cp1252")

# 2) OFX 2.x (XML), UTF-8, datas só com o dia, valores com tags de fechamento; repete as 4 últimas e traz 3 novas
xml = ['<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n<?OFX OFXHEADER="200" VERSION="202" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>\n'
       '<OFX>\n<SIGNONMSGSRSV1><SONRS><STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS><DTSERVER>20260331</DTSERVER><LANGUAGE>POR</LANGUAGE></SONRS></SIGNONMSGSRSV1>\n'
       '<BANKMSGSRSV1><STMTTRNRS><TRNUID>1</TRNUID><STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>\n<STMTRS><CURDEF>BRL</CURDEF>\n'
       '<BANKACCTFROM><BANKID>0001</BANKID><ACCTID>12345-6</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>\n<BANKTRANLIST><DTSTART>20260315</DTSTART><DTEND>20260331</DTEND>\n']
for day, memo, name, cents, fitid in BASE[-4:] + EXTRA:
    xml.append(f"<STMTTRN><TRNTYPE>{'CREDIT' if cents > 0 else 'DEBIT'}</TRNTYPE><DTPOSTED>202603{day:02d}</DTPOSTED>"
               f"<TRNAMT>{money(cents)}</TRNAMT><FITID>{fitid}</FITID><NAME>{name.replace('&', '&amp;')}</NAME><MEMO>{memo}</MEMO></STMTTRN>\n")
xml.append("</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1>\n</OFX>\n")
write("conta-corrente-2.ofx", "".join(xml), newline="\n")

# 3) fatura de cartão (OFX SGML, UTF-8): compras são negativas, o pagamento da fatura é positivo
cartao = [(4, "COMPRA STREAMING EXEMPLO", "Streaming Exemplo", -3990, "CC0001"), (7, "COMPRA RESTAURANTE EXEMPLO", "Restaurante Exemplo", -13550, "CC0002"),
          (9, "COMPRA SUPERMERCADO EXEMPLO", "Supermercado Exemplo", -24310, "CC0003"), (14, "ANUIDADE DIFERIDA", "Cartão Exemplo", -4500, "CC0004"),
          (16, "PAGAMENTO DE FATURA", "Banco Exemplo", 120000, "CC0005")]
write("fatura-cartao.ofx", ofx_sgml_header(charset="NONE", encoding="UTF-8") + sgml_stmt(cartao, acct="4111-0000", card=True))

# 4) CSV banco A: ponto e vírgula, vírgula decimal, milhar com ponto, dd/MM/yyyy, Windows-1252, coluna Documento = id
a = ["Data;Histórico;Documento;Valor"]
for day, memo, name, cents, fitid in BASE:
    a.append(f"{day:02d}/03/2026;{memo};{fitid};{money(cents, comma=True, thousands=True)}")
write("banco-a.csv", "\n".join(a) + "\n", encoding="cp1252")

# 5) CSV banco B: vírgula, UTF-8 com BOM, datas ISO, débito e crédito em colunas separadas, campos com vírgula entre aspas
b = ["Date,Description,Debit,Credit,Balance"]
bal = 100000
for day, memo, name, cents, fitid in BASE:
    bal += cents
    desc = f'"{name}, {memo.title()}"' if day % 2 == 0 else memo
    b.append(f"2026-03-{day:02d},{desc},{money(-cents) if cents < 0 else ''},{money(cents) if cents > 0 else ''},{money(bal)}")
write("banco-b.csv", "\n".join(b) + "\n", bom=True)

# 6) CSV banco C: tabulação, Windows-1252, 3 linhas de título antes do cabeçalho, tipo D/C com valor positivo, dd/MM/yy
c = ["Extrato de Conta Corrente (exemplo)", "Agência 0001 Conta 12345-6", "Período 01/03/2026 a 31/03/2026", "Data\tLançamento\tTipo\tValor"]
for day, memo, name, cents, fitid in BASE:
    c.append(f"{day:02d}/03/26\t{memo}\t{'C' if cents > 0 else 'D'}\t{money(abs(cents), comma=True)}")
write("banco-c.csv", "\n".join(c) + "\n", encoding="cp1252")

# 7) CSV com linhas ruins no meio: o arquivo é lido e cada linha ruim vira "inválida" com o motivo
bad = ["Data;Histórico;Valor",
       "02/03/2026;COMPRA OK UM;-10,00",
       "31/02/2026;DATA IMPOSSIVEL;-5,00",
       "03/03/2026;TRES CASAS;-1,005",
       "04/03/2026;SEM VALOR;",
       "05/03/2026;VALOR TEXTO;abc",
       "06/03/2026;VALOR ZERO;0,00",
       "01/01/1999;ANTES DE 2000;-1,00",
       "01/01/2099;NO FUTURO;-1,00",
       "07/03/2026;COMPRA OK DOIS;-20,50",
       ";SEM DATA;-3,00"]
write("banco-a-invalido.csv", "\n".join(bad) + "\n", encoding="cp1252")

# 8) OFX recusados inteiros
write("moeda-usd.ofx", ofx_sgml_header(charset="NONE", encoding="UTF-8") + sgml_stmt(BASE[:2], curdef="USD"))
duas = ofx_sgml_header(charset="NONE", encoding="UTF-8") + sgml_stmt(BASE[:2], acct="111-1").replace("</OFX>\n", "") + sgml_stmt(BASE[2:4], acct="222-2").replace("<OFX>\n", "", 1)
write("duas-contas.ofx", duas)
write("sem-transacoes.ofx", ofx_sgml_header(charset="NONE", encoding="UTF-8") + sgml_stmt([]))

print("fixtures geradas em", os.path.normpath(OUT), "->", sorted(os.listdir(OUT)))
