import { describe, expect, it } from "vitest";
import { currentMonth, diff, fromCents, monthLabel, monthLong, monthRange, share, shiftMonth, toCents } from "./dashboard";

describe("dashboard (funções puras)", () => {
  it("rotula meses", () => {
    expect(monthLabel("2026-05")).toBe("mai/2026");
    expect(monthLabel("lixo")).toBe("lixo");
    expect(monthLong("2026-05")).toBe("maio de 2026");
  });

  it("anda de mês em mês atravessando o ano", () => {
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2025-12", 1)).toBe("2026-01");
    expect(shiftMonth("2026-05", -5)).toBe("2025-12");
  });

  it("o mês corrente usa o fuso de São Paulo", () => {
    // 01/06 01:00 UTC ainda é 31/05 22:00 em São Paulo
    expect(currentMonth(new Date("2026-06-01T01:00:00Z"))).toBe("2026-05");
    expect(currentMonth(new Date("2026-06-01T03:00:00Z"))).toBe("2026-06");
  });

  it("intervalo do mês respeita fevereiro e ano bissexto", () => {
    expect(monthRange("2026-02")).toEqual({ from: "2026-02-01", to: "2026-02-28" });
    expect(monthRange("2028-02")).toEqual({ from: "2028-02-01", to: "2028-02-29" });
    expect(monthRange("2026-12")).toEqual({ from: "2026-12-01", to: "2026-12-31" });
  });

  it("conta em centavos sem erro de ponto flutuante", () => {
    expect(toCents("0.07")).toBe(7);
    expect(toCents("-1234.50")).toBe(-123450);
    expect(toCents("12")).toBe(1200);
    expect(fromCents(-5)).toBe("-0.05");
    expect(diff("0.30", "0.10")).toBe("0.20");   // com double: 0.19999999999999998
    expect(diff("100.00", "250.50")).toBe("-150.50");
    expect(diff("5.00", "5.00")).toBe("0.00");
  });

  it("porcentagem do total, limitada de 0 a 100 e sem dividir por zero", () => {
    expect(share("25.00", "100.00")).toBe(25);
    expect(share("1.00", "3.00")).toBe(33);
    expect(share("10.00", "0.00")).toBe(0);
    expect(share("-5.00", "100.00")).toBe(0);
    expect(share("500.00", "100.00")).toBe(100);
  });
});
