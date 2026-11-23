import { describe, expect, it } from "vitest";
import { formatMoney, isNegative, parseMoney } from "./money";

const ok = (input: string, opts = {}) => {
  const r = parseMoney(input, opts);
  if (!r.ok) throw new Error(`esperava sucesso para "${input}": ${r.error}`);
  return r.value;
};
const erro = (input: string, opts = {}) => {
  const r = parseMoney(input, opts);
  if (r.ok) throw new Error(`esperava erro para "${input}", veio ${r.value}`);
  return r.error;
};

describe("parseMoney (formato brasileiro, sem ponto flutuante)", () => {
  it.each([
    ["10", "10.00"],
    ["10,5", "10.50"],
    ["10,50", "10.50"],
    ["1.234,56", "1234.56"],
    ["1234,56", "1234.56"],
    ["1.234.567,89", "1234567.89"],
    ["R$ 1.234,56", "1234.56"],
    ["R$1.234,56", "1234.56"],
    ["  0,01  ", "0.01"],
    [",50", "0.50"],
    ["007,5", "7.50"],
    ["999999999999,99", "999999999999.99"],
  ])("converte %s em %s", (entrada, esperado) => {
    expect(ok(entrada)).toBe(esperado);
  });

  it("aceita ponto decimal no estilo internacional só quando é inequívoco", () => {
    expect(ok("12.5")).toBe("12.50");
    expect(ok("12.50")).toBe("12.50");
    expect(ok("1.500")).toBe("1500.00"); // três dígitos depois do ponto = milhares
    expect(ok("1.234.567")).toBe("1234567.00");
  });

  it("não perde precisão em valores que o ponto flutuante erraria", () => {
    expect(ok("0,10")).toBe("0.10");
    expect(ok("0,30")).toBe("0.30");
    expect(ok("123456789012,34")).toBe("123456789012.34"); // > 2^53 em centavos seria arriscado com Number
  });

  it("recusa mais de 2 casas decimais em vez de arredondar", () => {
    expect(erro("10,999")).toMatch(/2 casas/);
    expect(erro("0,005")).toMatch(/2 casas/);
    expect(erro("1,234")).toMatch(/2 casas/);
  });

  it("recusa formatos inválidos", () => {
    for (const ruim of ["abc", "1,2,3", "1..5", "1.2.3", "12.3456", "1,000.50", "10 reais", "--5", "1e3", "R$"]) {
      expect(parseMoney(ruim).ok, ruim).toBe(false);
    }
    expect(erro("")).toMatch(/Informe/);
    expect(erro("   ")).toMatch(/Informe/);
  });

  it("respeita o limite de 12 dígitos inteiros", () => {
    expect(erro("1.000.000.000.000,00")).toMatch(/limite/);
    expect(erro("1000000000000")).toMatch(/limite/);
  });

  it("zero só é aceito quando permitido", () => {
    expect(erro("0")).toMatch(/maior que zero/);
    expect(erro("0,00")).toMatch(/maior que zero/);
    expect(ok("0", { allowZero: true })).toBe("0.00");
  });

  it("negativo só é aceito quando permitido (saldo de cartão)", () => {
    expect(erro("-10")).toMatch(/negativo/);
    expect(ok("-1.234,50", { allowNegative: true })).toBe("-1234.50");
    expect(ok("-R$ 200,00", { allowNegative: true })).toBe("-200.00");
    expect(ok("-0", { allowNegative: true, allowZero: true })).toBe("0.00"); // sem "-0.00"
  });
});

describe("formatMoney", () => {
  it.each([
    ["0.00", "R$ 0,00"],
    ["5", "R$ 5,00"],
    ["5.5", "R$ 5,50"],
    ["1234.50", "R$ 1.234,50"],
    ["13800.00", "R$ 13.800,00"],
    ["999999999999.99", "R$ 999.999.999.999,99"],
    ["-200.00", "-R$ 200,00"],
    ["-1234567.89", "-R$ 1.234.567,89"],
  ])("formata %s como %s", (entrada, esperado) => {
    expect(formatMoney(entrada)).toBe(esperado);
  });

  it("devolve texto inesperado sem inventar valor", () => {
    expect(formatMoney("abc")).toBe("abc");
    expect(formatMoney("1,5")).toBe("1,5");
  });

  it("ida e volta: formatar e converter de novo dá o mesmo valor", () => {
    for (const v of ["0.01", "1234.56", "999999999999.99", "100.00"]) {
      expect(ok(formatMoney(v))).toBe(v);
    }
  });
});

describe("isNegative", () => {
  it("identifica dívida e ignora -0,00", () => {
    expect(isNegative("-0.01")).toBe(true);
    expect(isNegative("-200.00")).toBe(true);
    expect(isNegative("0.00")).toBe(false);
    expect(isNegative("-0.00")).toBe(false);
    expect(isNegative("10.00")).toBe(false);
  });
});
