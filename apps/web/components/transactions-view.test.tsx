import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Page, Transaction } from "@/lib/types";
import { account, categories, contexts, renderWithQuery, tx } from "./test-utils";
import { TransactionsView } from "./transactions-view";

const replace = vi.fn();
let urlParams = new URLSearchParams();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }), useSearchParams: () => urlParams }));

const listAccounts = vi.fn();
const listTransactions = vi.fn();
const deleteTransaction = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listAccounts: (...a: unknown[]) => listAccounts(...a),
  listCategories: () => Promise.resolve(categories),
  listContexts: () => Promise.resolve(contexts),
  listTransactions: (...a: unknown[]) => listTransactions(...a),
  deleteTransaction: (...a: unknown[]) => deleteTransaction(...a),
}));

const page = (items: Transaction[], over: Partial<Page<Transaction>> = {}): Page<Transaction> => ({
  items,
  page: 0,
  size: 20,
  totalElements: items.length,
  totalPages: items.length ? 1 : 0,
  ...over,
});

const contas = [account({ id: "a1", name: "Corrente" }), account({ id: "a2", name: "Poupança", type: "SAVINGS_ACCOUNT" })];

afterEach(() => {
  urlParams = new URLSearchParams();
  replace.mockReset();
  listAccounts.mockReset();
  listTransactions.mockReset();
  deleteTransaction.mockReset();
});

function setup(items: Transaction[], over: Partial<Page<Transaction>> = {}) {
  listAccounts.mockResolvedValue(contas);
  listTransactions.mockResolvedValue(page(items, over));
  return renderWithQuery(<TransactionsView />);
}

describe("TransactionsView", () => {
  it("mostra data, descrição, categoria com pai, conta e valor com sinal", async () => {
    setup([
      tx({ id: "t1", type: "EXPENSE", amount: "120.50" }),
      tx({ id: "t2", type: "INCOME", amount: "5000.00", description: "Salário", merchant: null, categoryId: null }),
      tx({ id: "t3", type: "TRANSFER", amount: "300.00", description: "Reserva", counterpartAccountId: "a2", categoryId: null }),
    ]);

    const almoco = (await screen.findByText("Almoço")).closest("tr")!;
    expect(almoco).toHaveTextContent("10/05/2026");
    expect(almoco).toHaveTextContent("Despesa · Cantina");
    expect(almoco).toHaveTextContent("Alimentação › Restaurante");
    expect(almoco).toHaveTextContent("Corrente");
    expect(almoco).toHaveTextContent("-R$ 120,50");
    expect(screen.getByText("Salário").closest("tr")).toHaveTextContent("+R$ 5.000,00");
    const transf = screen.getByText("Reserva").closest("tr")!;
    expect(transf).toHaveTextContent("Corrente → Poupança");
    expect(transf).toHaveTextContent("R$ 300,00");
    expect(transf).not.toHaveTextContent("+R$");
    expect(transf).not.toHaveTextContent("-R$");
  });

  it("estado vazio, com e sem filtros", async () => {
    const user = userEvent.setup();
    setup([]);
    expect(await screen.findByText(/Nenhuma transação ainda/)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Buscar"), "xyz");
    await user.click(screen.getByRole("button", { name: "Filtrar" }));
    expect(await screen.findByText(/Nenhuma transação encontrada com esses filtros/)).toBeInTheDocument();
  });

  it("aplica os filtros, volta à primeira página e Limpar remove tudo", async () => {
    const user = userEvent.setup();
    setup([tx()]);
    await screen.findByText("Almoço");

    await user.type(screen.getByLabelText("Buscar"), "cantina");
    await user.selectOptions(screen.getByLabelText("Conta"), "a1");
    await user.selectOptions(screen.getByLabelText("Tipo"), "EXPENSE");
    await user.selectOptions(screen.getByLabelText("Categoria"), "c1");
    await user.selectOptions(screen.getByLabelText("Contexto"), "x1");
    await user.type(screen.getByLabelText("De"), "2026-05-01");
    await user.type(screen.getByLabelText("Até"), "2026-05-31");
    await user.click(screen.getByRole("button", { name: "Filtrar" }));

    await waitFor(() =>
      expect(listTransactions).toHaveBeenLastCalledWith(
        { q: "cantina", accountId: "a1", type: "EXPENSE", categoryId: "c1", contextId: "x1", from: "2026-05-01", to: "2026-05-31" },
        0,
      ),
    );

    await user.click(screen.getByRole("button", { name: "Limpar" }));
    await waitFor(() => expect(listTransactions).toHaveBeenLastCalledWith({}, 0));
    expect(screen.getByLabelText("Buscar")).toHaveValue("");
  });

  it("pagina: mostra Página x de y e navega com Anterior e Próxima", async () => {
    const user = userEvent.setup();
    setup([tx()], { totalElements: 45, totalPages: 3 });

    expect(await screen.findByText(/Página 1 de 3 · 45 transações/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Anterior" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Próxima" }));
    await waitFor(() => expect(listTransactions).toHaveBeenLastCalledWith({}, 1));
    expect(await screen.findByText(/Página 2 de 3/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Próxima" }));
    await waitFor(() => expect(listTransactions).toHaveBeenLastCalledWith({}, 2));
    expect(screen.getByRole("button", { name: "Próxima" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Anterior" }));
    await waitFor(() => expect(listTransactions).toHaveBeenLastCalledWith({}, 1));
  });

  it("excluir exige confirmação: cancelar não chama a API, confirmar chama", async () => {
    deleteTransaction.mockResolvedValue(undefined);
    const user = userEvent.setup();
    setup([tx({ id: "t1" })]);

    await user.click(await screen.findByRole("button", { name: "Excluir Almoço" }));
    expect(deleteTransaction).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.getByRole("button", { name: "Excluir Almoço" })).toBeInTheDocument();
    expect(deleteTransaction).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Excluir Almoço" }));
    const grupo = screen.getByRole("group", { name: "Confirmar exclusão de Almoço" });
    await user.click(within(grupo).getByRole("button", { name: "Confirmar exclusão" }));
    await waitFor(() => expect(deleteTransaction).toHaveBeenCalledWith("t1"));
  });

  it("falha ao excluir mostra o erro na própria linha", async () => {
    deleteTransaction.mockRejectedValue(new Error("Transação não encontrada."));
    const user = userEvent.setup();
    setup([tx({ id: "t1" })]);

    await user.click(await screen.findByRole("button", { name: "Excluir Almoço" }));
    await user.click(screen.getByRole("button", { name: "Confirmar exclusão" }));
    expect(await screen.findByText("Transação não encontrada.")).toBeInTheDocument();
  });

  it("o formulário só oferece contas ativas", async () => {
    const user = userEvent.setup();
    listAccounts.mockResolvedValue([...contas, account({ id: "a9", name: "Velha", archived: true })]);
    listTransactions.mockResolvedValue(page([]));
    renderWithQuery(<TransactionsView />);

    await user.click(await screen.findByRole("button", { name: "Nova transação" }));
    const form = screen.getByRole("form", { name: "Nova transação" });
    const nomes = Array.from(within(form).getByLabelText("Conta").querySelectorAll("option")).map((o) => o.textContent);
    expect(nomes).toEqual(["Corrente", "Poupança"]);
  });

  it("vindo de uma importação (?importId=), mostra só as dela, avisa e permite ver todas", async () => {
    urlParams = new URLSearchParams("importId=i1");
    const user = userEvent.setup();
    setup([tx({ id: "t1", source: "OFX", importId: "i1" })]);

    expect(await screen.findByText("Mostrando só as transações de uma importação.")).toBeInTheDocument();
    expect(listTransactions).toHaveBeenCalledWith({ importId: "i1" }, 0);

    await user.click(screen.getByRole("button", { name: "Ver todas" }));

    await waitFor(() => expect(listTransactions).toHaveBeenLastCalledWith({}, 0));
    expect(replace).toHaveBeenCalledWith("/transacoes");
    expect(screen.queryByText("Mostrando só as transações de uma importação.")).not.toBeInTheDocument();
  });

  it("transação importada ganha a etiqueta da origem; lançamento manual não", async () => {
    setup([tx({ id: "t1", source: "OFX", description: "Importada" }), tx({ id: "t2", source: "MANUAL", description: "Digitada" })]);

    const importada = (await screen.findByText("Importada")).closest("tr")!;
    expect(importada).toHaveTextContent("OFX");
    expect(screen.getByText("Digitada").closest("tr")).not.toHaveTextContent("Manual");
  });
});
