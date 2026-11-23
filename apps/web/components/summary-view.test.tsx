import { screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderWithQuery, summary } from "./test-utils";
import { SummaryView } from "./summary-view";

const getSummary = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({ ...(await orig<typeof import("@/lib/finance")>()), getSummary: () => getSummary() }));

afterEach(() => getSummary.mockReset());

describe("SummaryView", () => {
  it("mostra os quatro cartões em reais, separando o restrito do disponível", async () => {
    getSummary.mockResolvedValue(summary());
    renderWithQuery(<SummaryView />);

    const disponivel = await screen.findByTestId("card-available");
    expect(within(disponivel).getByText("R$ 4.200,00")).toBeInTheDocument();
    expect(within(screen.getByTestId("card-restricted")).getByText("R$ 1.200,00")).toBeInTheDocument();
    expect(within(screen.getByTestId("card-invested")).getByText("R$ 8.400,00")).toBeInTheDocument();
    expect(within(screen.getByTestId("card-net-worth")).getByText("R$ 13.800,00")).toBeInTheDocument();
    // a explicação de cada cartão acompanha o número
    expect(within(screen.getByTestId("card-restricted")).getByText(/Não conta como dinheiro para investir/)).toBeInTheDocument();
  });

  it("lista as contas com tipo, liquidez e saldo", async () => {
    getSummary.mockResolvedValue(summary());
    renderWithQuery(<SummaryView />);

    const item = (await screen.findByText("Vale alimentação")).closest("li")!;
    expect(item).toHaveTextContent("Vale-alimentação (VA) · Restrito");
    expect(item).toHaveTextContent("R$ 700,00");
  });

  it("cartão e outros só aparecem quando há saldo; dívida fica negativa", async () => {
    getSummary.mockResolvedValue(summary({ creditCardBalance: "-350.00", otherBalance: "100000.00", totalNetWorth: "113450.00" }));
    renderWithQuery(<SummaryView />);

    expect(within(await screen.findByTestId("card-credit")).getByText("-R$ 350,00")).toBeInTheDocument();
    expect(within(screen.getByTestId("card-other")).getByText("R$ 100.000,00")).toBeInTheDocument();
  });

  it("sem dívida nem outros, esses cartões não aparecem", async () => {
    getSummary.mockResolvedValue(summary());
    renderWithQuery(<SummaryView />);
    await screen.findByTestId("card-available");
    expect(screen.queryByTestId("card-credit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("card-other")).not.toBeInTheDocument();
  });

  it("sem contas, convida a cadastrar a primeira", async () => {
    getSummary.mockResolvedValue(summary({ accounts: [], availableCash: "0.00", restrictedBalance: "0.00", investedAssets: "0.00", totalNetWorth: "0.00" }));
    renderWithQuery(<SummaryView />);

    expect(await screen.findByText("Cadastre sua primeira conta")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cadastrar conta" })).toHaveAttribute("href", "/contas");
    expect(screen.queryByTestId("card-available")).not.toBeInTheDocument();
  });

  it("falha ao carregar mostra um aviso acessível", async () => {
    getSummary.mockRejectedValue(new Error("falhou"));
    renderWithQuery(<SummaryView />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível carregar o resumo.");
  });
});
