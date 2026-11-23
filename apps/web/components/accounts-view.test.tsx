import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AccountsView } from "./accounts-view";
import { account, renderWithQuery } from "./test-utils";

const listAccounts = vi.fn();
const updateAccount = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listAccounts: (...a: unknown[]) => listAccounts(...a),
  updateAccount: (...a: unknown[]) => updateAccount(...a),
}));

afterEach(() => {
  listAccounts.mockReset();
  updateAccount.mockReset();
});

describe("AccountsView", () => {
  it("lista contas com tipo, liquidez e saldo; dívida em negativo", async () => {
    listAccounts.mockResolvedValue([
      account({ id: "a1", name: "Nubank", balance: "1234.50" }),
      account({ id: "a2", name: "Cartão", type: "CREDIT_CARD", liquidity: "LIABILITY", balance: "-350.00" }),
    ]);
    renderWithQuery(<AccountsView />);

    const nubank = (await screen.findByText("Nubank")).closest("li")!;
    expect(nubank).toHaveTextContent("Conta corrente · Disponível");
    expect(nubank).toHaveTextContent("R$ 1.234,50");
    expect(screen.getByText("Cartão").closest("li")).toHaveTextContent("-R$ 350,00");
  });

  it("sem contas, mostra o estado vazio", async () => {
    listAccounts.mockResolvedValue([]);
    renderWithQuery(<AccountsView />);
    expect(await screen.findByText(/Você ainda não tem contas/)).toBeInTheDocument();
  });

  it("arquivar conta COM saldo avisa que ela sai do patrimônio e só arquiva ao confirmar", async () => {
    listAccounts.mockResolvedValue([account({ id: "a1", name: "Reserva", balance: "900.00" })]);
    updateAccount.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithQuery(<AccountsView />);

    await user.click(await screen.findByRole("button", { name: "Arquivar Reserva" }));

    const aviso = screen.getByRole("alertdialog");
    expect(aviso).toHaveTextContent("R$ 900,00");
    expect(aviso).toHaveTextContent("não entram no patrimônio nem no dinheiro disponível");
    expect(updateAccount).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Arquivar conta" }));
    await waitFor(() => expect(updateAccount).toHaveBeenCalledWith("a1", { archived: true }));
  });

  it("cancelar o arquivamento não chama a API", async () => {
    listAccounts.mockResolvedValue([account({ id: "a1", name: "Reserva", balance: "0.00" })]);
    const user = userEvent.setup();
    renderWithQuery(<AccountsView />);

    await user.click(await screen.findByRole("button", { name: "Arquivar Reserva" }));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Contas arquivadas somem das listas");
    await user.click(screen.getByRole("button", { name: "Cancelar" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(updateAccount).not.toHaveBeenCalled();
  });

  it("renomeia a conta", async () => {
    listAccounts.mockResolvedValue([account({ id: "a1", name: "Antigo" })]);
    updateAccount.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithQuery(<AccountsView />);

    await user.click(await screen.findByRole("button", { name: "Renomear Antigo" }));
    const campo = screen.getByLabelText("Novo nome da conta Antigo");
    await user.clear(campo);
    await user.type(campo, "Novo nome");
    await user.click(screen.getByRole("button", { name: "Salvar" }));

    await waitFor(() => expect(updateAccount).toHaveBeenCalledWith("a1", { name: "Novo nome" }));
  });

  it("mostrar arquivadas refaz a consulta e permite reativar", async () => {
    listAccounts.mockImplementation((incluir: boolean) =>
      Promise.resolve(incluir ? [account({ id: "a9", name: "Velha", archived: true })] : []),
    );
    updateAccount.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithQuery(<AccountsView />);
    await screen.findByText(/Você ainda não tem contas/);

    await user.click(screen.getByLabelText("Mostrar contas arquivadas"));
    expect(await screen.findByText("Arquivada")).toBeInTheDocument();
    expect(listAccounts).toHaveBeenLastCalledWith(true);

    await user.click(screen.getByRole("button", { name: "Reativar Velha" }));
    await waitFor(() => expect(updateAccount).toHaveBeenCalledWith("a9", { archived: false }));
  });

  it("mostra o erro do servidor ao falhar (ex.: nome já em uso ao reativar)", async () => {
    listAccounts.mockResolvedValue([account({ id: "a9", name: "Velha", archived: true })]);
    updateAccount.mockRejectedValue(new Error("Já existe uma conta ativa com esse nome."));
    const user = userEvent.setup();
    renderWithQuery(<AccountsView />);

    await user.click(await screen.findByLabelText("Mostrar contas arquivadas"));
    await user.click(await screen.findByRole("button", { name: "Reativar Velha" }));
    expect(await screen.findByText("Já existe uma conta ativa com esse nome.")).toBeInTheDocument();
  });

  it("abre e fecha o formulário de nova conta", async () => {
    listAccounts.mockResolvedValue([]);
    const user = userEvent.setup();
    renderWithQuery(<AccountsView />);

    await user.click(screen.getByRole("button", { name: "Nova conta" }));
    expect(screen.getByRole("form", { name: "Nova conta" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.queryByRole("form", { name: "Nova conta" })).not.toBeInTheDocument();
  });
});
