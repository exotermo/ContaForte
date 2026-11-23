import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/finance";
import { AccountForm } from "./account-form";
import { renderWithQuery } from "./test-utils";

const createAccount = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({ ...(await orig<typeof import("@/lib/finance")>()), createAccount: (...a: unknown[]) => createAccount(...a) }));

afterEach(() => createAccount.mockReset());

describe("AccountForm", () => {
  it("explica a liquidez do tipo escolhido (VA não é dinheiro para investir)", async () => {
    const user = userEvent.setup();
    renderWithQuery(<AccountForm />);
    expect(screen.getByText(/pode gastar ou investir agora/)).toBeInTheDocument(); // conta corrente

    await user.selectOptions(screen.getByLabelText("Tipo"), "VA");
    expect(screen.getByText(/Não conta como dinheiro para investir/)).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Tipo"), "CREDIT_CARD");
    expect(screen.getByText(/Reduz o patrimônio, mas não o dinheiro disponível/)).toBeInTheDocument();
  });

  it("converte o saldo digitado em decimal canônico e envia", async () => {
    createAccount.mockResolvedValue({});
    const onDone = vi.fn();
    const user = userEvent.setup();
    renderWithQuery(<AccountForm onDone={onDone} />);

    await user.type(screen.getByLabelText("Nome da conta"), "  Itaú  ");
    await user.selectOptions(screen.getByLabelText("Tipo"), "SAVINGS_ACCOUNT");
    await user.type(screen.getByLabelText("Saldo inicial (R$)"), "1.234,50");
    await user.click(screen.getByRole("button", { name: "Salvar conta" }));

    await waitFor(() => expect(createAccount).toHaveBeenCalledTimes(1));
    expect(createAccount.mock.calls[0][0]).toEqual({ name: "Itaú", type: "SAVINGS_ACCOUNT", openingBalance: "1234.50" });
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it("saldo em branco vira zero e saldo negativo é aceito (dívida de cartão)", async () => {
    createAccount.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithQuery(<AccountForm />);

    await user.type(screen.getByLabelText("Nome da conta"), "Carteira");
    await user.click(screen.getByRole("button", { name: "Salvar conta" }));
    await waitFor(() => expect(createAccount).toHaveBeenCalledTimes(1));
    expect(createAccount.mock.calls[0][0].openingBalance).toBe("0.00");

    await user.type(screen.getByLabelText("Nome da conta"), "Cartão");
    await user.type(screen.getByLabelText("Saldo inicial (R$)"), "-300,00");
    await user.click(screen.getByRole("button", { name: "Salvar conta" }));
    await waitFor(() => expect(createAccount).toHaveBeenCalledTimes(2));
    expect(createAccount.mock.calls[1][0].openingBalance).toBe("-300.00");
  });

  it("não envia com nome vazio ou valor inválido e mostra o motivo", async () => {
    const user = userEvent.setup();
    renderWithQuery(<AccountForm />);

    await user.click(screen.getByRole("button", { name: "Salvar conta" }));
    expect(screen.getByText("Informe o nome da conta.")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Nome da conta"), "X");
    await user.type(screen.getByLabelText("Saldo inicial (R$)"), "10,999");
    await user.click(screen.getByRole("button", { name: "Salvar conta" }));
    expect(screen.getByText("Use no máximo 2 casas decimais.")).toBeInTheDocument();
    expect(screen.getByLabelText("Saldo inicial (R$)")).toHaveAttribute("aria-invalid", "true");
    expect(createAccount).not.toHaveBeenCalled();
  });

  it("mostra a mensagem do servidor (ex.: nome duplicado)", async () => {
    createAccount.mockRejectedValue(new ApiError(409, "Já existe uma conta ativa com esse nome."));
    const user = userEvent.setup();
    renderWithQuery(<AccountForm />);

    await user.type(screen.getByLabelText("Nome da conta"), "Nubank");
    await user.click(screen.getByRole("button", { name: "Salvar conta" }));

    expect(await screen.findByText("Já existe uma conta ativa com esse nome.")).toBeInTheDocument();
  });
});
