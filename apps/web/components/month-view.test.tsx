import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { currentMonth, shiftMonth } from "@/lib/dashboard";
import type { History, MonthlySummary } from "@/lib/types";
import { renderWithQuery } from "./test-utils";
import { MonthView } from "./month-view";

const getMonthly = vi.fn();
const getHistory = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  getMonthly: (...a: unknown[]) => getMonthly(...a),
  getHistory: (...a: unknown[]) => getHistory(...a),
}));

afterEach(() => {
  getMonthly.mockReset();
  getHistory.mockReset();
});

const ZERO = { income: "0.00", expense: "0.00", refunds: "0.00", netExpense: "0.00", invested: "0.00", net: "0.00" };
const CAT = "11111111-1111-4111-8111-111111111111";
const CTX = "22222222-2222-4222-8222-222222222222";

const month = (over: Partial<MonthlySummary> = {}): MonthlySummary => ({
  month: currentMonth(),
  income: "5000.00",
  expense: "1500.00",
  refunds: "100.00",
  netExpense: "1400.00",
  invested: "800.00",
  net: "3600.00",
  byCategory: [
    {
      categoryId: CAT, name: "Alimentação", code: "FOOD", system: true, total: "1000.00", directTotal: "200.00",
      children: [{ categoryId: "c2", name: "Restaurante", total: "800.00" }],
    },
    { categoryId: null, name: "Sem categoria", code: null, system: true, total: "400.00", directTotal: "400.00", children: [] },
  ],
  byContext: [{ contextId: CTX, name: "Família", total: "1400.00" }],
  ...over,
});

const history = (): History => {
  const now = currentMonth();
  return {
    from: shiftMonth(now, -1),
    to: now,
    months: [
      { month: shiftMonth(now, -1), ...ZERO, income: "4000.00", netExpense: "1600.00", net: "2400.00", invested: "800.00" },
      { month: now, ...ZERO, income: "5000.00", netExpense: "1400.00", net: "3600.00", invested: "800.00" },
    ],
  };
};

describe("MonthView", () => {
  it("mostra receitas, gastos, sobra e investido com a comparação com o mês anterior", async () => {
    getMonthly.mockResolvedValue(month());
    getHistory.mockResolvedValue(history());
    renderWithQuery(<MonthView />);

    const receitas = await screen.findByTestId("stat-income");
    expect(within(receitas).getByText("R$ 5.000,00")).toBeInTheDocument();
    expect(receitas).toHaveTextContent("▲ +R$ 1.000,00 vs mês anterior");
    const gastos = screen.getByTestId("stat-expense");
    expect(within(gastos).getByText("R$ 1.400,00")).toBeInTheDocument();
    expect(gastos).toHaveTextContent("▼ -R$ 200,00 vs mês anterior");   // gastar menos é bom, e vem escrito
    expect(screen.getByTestId("stat-net")).toHaveTextContent("R$ 3.600,00");
    expect(screen.getByTestId("stat-invested")).toHaveTextContent("Igual ao mês anterior");
  });

  it("lista categorias e contextos com valor e porcentagem, e leva às transações filtradas", async () => {
    getMonthly.mockResolvedValue(month());
    getHistory.mockResolvedValue(history());
    renderWithQuery(<MonthView />);

    const cats = await screen.findByTestId("share-category");
    expect(within(cats).getByText("Alimentação")).toBeInTheDocument();
    expect(cats).toHaveTextContent("R$ 1.000,00 · 71%");
    expect(within(cats).getByText("Restaurante")).toBeInTheDocument();   // subcategoria dentro do <details>
    const link = within(cats).getByRole("link", { name: /Ver lançamentos de Alimentação/ });
    const href = new URL(link.getAttribute("href")!, "http://x");
    expect(href.pathname).toBe("/transacoes");
    expect(href.searchParams.get("categoryId")).toBe(CAT);
    expect(href.searchParams.get("type")).toBe("EXPENSE");
    expect(href.searchParams.get("from")).toMatch(/^\d{4}-\d{2}-01$/);
    expect(within(cats).getAllByRole("link")).toHaveLength(1);   // "Sem categoria" não tem para onde levar
    expect(within(screen.getByTestId("share-context")).getByRole("link", { name: /Família/ }).getAttribute("href")).toContain(`contextId=${CTX}`);
  });

  it("o gráfico tem descrição em texto e tabela equivalente", async () => {
    getMonthly.mockResolvedValue(month());
    getHistory.mockResolvedValue(history());
    renderWithQuery(<MonthView />);

    const chart = await screen.findByTestId("chart-monthly");
    const img = within(chart).getByRole("img");
    expect(img.getAttribute("aria-label")).toContain("receitas R$ 5.000,00, gastos R$ 1.400,00");
    expect(within(chart).getByText("Gastos (já sem estornos)")).toBeInTheDocument();   // legenda em texto, não só cor
    expect(within(chart).getByRole("table")).toBeInTheDocument();
  });

  it("navega entre meses e não passa do mês atual", async () => {
    const user = userEvent.setup();
    getMonthly.mockResolvedValue(month());
    getHistory.mockResolvedValue(history());
    renderWithQuery(<MonthView />);
    await screen.findByTestId("stat-income");
    const next = screen.getByRole("button", { name: "Próximo mês" });
    expect(next).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Mês anterior" }));

    await waitFor(() => expect(getMonthly).toHaveBeenLastCalledWith(shiftMonth(currentMonth(), -1)));
    expect(getHistory).toHaveBeenLastCalledWith(shiftMonth(currentMonth(), -1), 6);
    expect(screen.getByRole("button", { name: "Próximo mês" })).toBeEnabled();
  });

  it("mês sem movimento mostra um aviso em vez de gráficos vazios", async () => {
    getMonthly.mockResolvedValue(month({ ...ZERO, byCategory: [], byContext: [] }));
    getHistory.mockResolvedValue({ from: "", to: "", months: [] });
    renderWithQuery(<MonthView />);

    expect(await screen.findByTestId("month-empty")).toHaveTextContent("Nenhuma movimentação em");
    expect(screen.queryByTestId("chart-monthly")).not.toBeInTheDocument();
  });

  it("erro ao carregar vira alerta", async () => {
    getMonthly.mockRejectedValue(new Error("falhou"));
    getHistory.mockResolvedValue(history());
    renderWithQuery(<MonthView />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível carregar a movimentação do mês.");
  });

  it("sobra negativa aparece em vermelho com sinal", async () => {
    getMonthly.mockResolvedValue(month({ net: "-250.00" }));
    getHistory.mockResolvedValue(history());
    renderWithQuery(<MonthView />);

    expect(within(await screen.findByTestId("stat-net")).getByText("-R$ 250,00")).toBeInTheDocument();
  });
});
