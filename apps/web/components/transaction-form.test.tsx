import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/finance";
import { account, categories, contexts, renderWithQuery } from "./test-utils";
import { TransactionForm } from "./transaction-form";

const createTransaction = vi.fn();
const testRule = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  createTransaction: (...a: unknown[]) => createTransaction(...a),
  testRule: (...a: unknown[]) => testRule(...a),
}));

afterEach(() => {
  createTransaction.mockReset();
  testRule.mockReset();
});

const contas = [
  account({ id: "a1", name: "Corrente" }),
  account({ id: "a2", name: "Poupança", type: "SAVINGS_ACCOUNT" }),
  account({ id: "a3", name: "Corretora", type: "INVESTMENT_ACCOUNT", liquidity: "INVESTED" }),
];

const form = () => renderWithQuery(<TransactionForm accounts={contas} categories={categories} contexts={contexts} />);

describe("TransactionForm", () => {
  it("despesa não pede conta de destino; transferência e investimento pedem", async () => {
    const user = userEvent.setup();
    form();
    expect(screen.queryByLabelText("Conta de destino")).not.toBeInTheDocument();

    await user.click(screen.getByLabelText("Transferência"));
    expect(screen.getByLabelText("Conta de destino")).toBeInTheDocument();
    expect(screen.getByLabelText("Conta de origem")).toBeInTheDocument();

    await user.click(screen.getByLabelText("Receita"));
    expect(screen.queryByLabelText("Conta de destino")).not.toBeInTheDocument();
  });

  it("o destino nunca inclui a conta de origem e investimento só lista contas de investimento", async () => {
    const user = userEvent.setup();
    form();
    await user.click(screen.getByLabelText("Transferência"));
    const opcoes = () => screen.getAllByRole("option", { name: /Corrente|Poupança|Corretora/ }).map((o) => o.textContent);
    const destino = screen.getByLabelText("Conta de destino");
    const nomesDestino = Array.from(destino.querySelectorAll("option")).map((o) => o.textContent);
    expect(nomesDestino).toEqual(["Selecione…", "Poupança", "Corretora"]); // sem "Corrente", a origem
    expect(opcoes().length).toBeGreaterThan(0);

    await user.click(screen.getByLabelText("Investimento"));
    const soInvest = Array.from(screen.getByLabelText("Conta de destino").querySelectorAll("option")).map((o) => o.textContent);
    expect(soInvest).toEqual(["Selecione…", "Corretora"]);
  });

  it("envia o valor em decimal canônico, a data em ISO e omite os campos vazios", async () => {
    createTransaction.mockResolvedValue({});
    const user = userEvent.setup();
    form();

    await user.type(screen.getByLabelText("Valor (R$)"), "R$ 1.120,50");
    await user.clear(screen.getByLabelText("Data e hora"));
    await user.type(screen.getByLabelText("Data e hora"), "2026-05-10T15:30");
    await user.click(screen.getByRole("button", { name: "Registrar" }));

    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(1));
    expect(createTransaction.mock.calls[0][0]).toEqual({
      type: "EXPENSE",
      accountId: "a1",
      amount: "1120.50",
      occurredAt: new Date("2026-05-10T15:30").toISOString(),
    });
    expect(await screen.findByRole("status")).toHaveTextContent("Transação registrada.");
    expect(screen.getByLabelText("Valor (R$)")).toHaveValue(""); // limpa para o próximo lançamento
  });

  it("envia destino, descrição, estabelecimento, categoria e contexto quando preenchidos", async () => {
    createTransaction.mockResolvedValue({});
    const user = userEvent.setup();
    form();

    await user.click(screen.getByLabelText("Transferência"));
    await user.selectOptions(screen.getByLabelText("Conta de destino"), "a2");
    await user.type(screen.getByLabelText("Valor (R$)"), "300");
    await user.type(screen.getByLabelText("Descrição"), "  Reserva  ");
    await user.type(screen.getByLabelText("Estabelecimento"), "Eu mesmo");
    await user.selectOptions(screen.getByLabelText("Categoria"), "c2");
    await user.selectOptions(screen.getByLabelText("Contexto"), "x1");
    await user.click(screen.getByRole("button", { name: "Registrar" }));

    await waitFor(() => expect(createTransaction).toHaveBeenCalledTimes(1));
    expect(createTransaction.mock.calls[0][0]).toMatchObject({
      type: "TRANSFER",
      accountId: "a1",
      counterpartAccountId: "a2",
      amount: "300.00",
      description: "Reserva",
      merchant: "Eu mesmo",
      categoryId: "c2",
      contextId: "x1",
    });
  });

  it("valida no cliente: valor, destino e data, sem chamar a API", async () => {
    const user = userEvent.setup();
    form();

    await user.click(screen.getByLabelText("Transferência"));
    await user.type(screen.getByLabelText("Valor (R$)"), "0,005");
    await user.clear(screen.getByLabelText("Data e hora"));
    await user.click(screen.getByRole("button", { name: "Registrar" }));

    expect(screen.getByText("Escolha a conta de destino.")).toBeInTheDocument();
    expect(screen.getByText("Use no máximo 2 casas decimais.")).toBeInTheDocument();
    expect(screen.getByText("Informe a data e a hora.")).toBeInTheDocument();
    expect(createTransaction).not.toHaveBeenCalled();
  });

  it("valor zero e negativo são recusados", async () => {
    const user = userEvent.setup();
    form();
    await user.type(screen.getByLabelText("Valor (R$)"), "0");
    await user.click(screen.getByRole("button", { name: "Registrar" }));
    expect(screen.getByText("O valor deve ser maior que zero.")).toBeInTheDocument();

    await user.clear(screen.getByLabelText("Valor (R$)"));
    await user.type(screen.getByLabelText("Valor (R$)"), "-5");
    await user.click(screen.getByRole("button", { name: "Registrar" }));
    expect(screen.getByText("O valor não pode ser negativo.")).toBeInTheDocument();
    expect(createTransaction).not.toHaveBeenCalled();
  });

  it("investimento sem conta de investimentos explica o que falta", async () => {
    const user = userEvent.setup();
    renderWithQuery(<TransactionForm accounts={contas.slice(0, 2)} categories={categories} contexts={contexts} />);
    await user.click(screen.getByLabelText("Investimento"));
    await user.type(screen.getByLabelText("Valor (R$)"), "10");
    await user.click(screen.getByRole("button", { name: "Registrar" }));
    expect(screen.getByText("Cadastre uma conta de investimentos para receber o aporte.")).toBeInTheDocument();
  });

  it("mostra o erro do servidor", async () => {
    createTransaction.mockRejectedValue(new ApiError(422, "A data não pode estar no futuro."));
    const user = userEvent.setup();
    form();
    await user.type(screen.getByLabelText("Valor (R$)"), "10");
    await user.click(screen.getByRole("button", { name: "Registrar" }));
    expect(await screen.findByText("A data não pode estar no futuro.")).toBeInTheDocument();
  });

  it("sem contas ativas, orienta a cadastrar uma conta", () => {
    renderWithQuery(<TransactionForm accounts={[]} categories={categories} contexts={contexts} />);
    expect(screen.getByText(/Cadastre uma conta antes/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ir para Contas" })).toHaveAttribute("href", "/contas");
  });

  // ---------------------------------------------------------------- sugestão de categoria ao digitar

  const sugestao = { category: { id: "c2", confidence: 0.9, source: "DEFAULT_RULE", decision: "AUTO" }, context: { id: "x1", confidence: 0.66, source: "RECIPIENT", decision: "SUGGEST" }, decision: "SUGGEST" };

  it("sugere a categoria depois de um instante de digitação e 'Usar' preenche categoria e contexto", async () => {
    testRule.mockResolvedValue(sugestao);
    const user = userEvent.setup();
    form();
    await user.type(screen.getByLabelText("Descrição"), "COMPRA POSTO ABC");

    const dica = await screen.findByText(/Sugestão de categoria/, {}, { timeout: 3000 });
    expect(dica).toHaveTextContent("Sugestão de categoria: Restaurante (Regra do sistema · 90%)");
    expect(testRule).toHaveBeenCalledTimes(1);   // espera parar de digitar, não pergunta a cada letra
    expect(testRule).toHaveBeenCalledWith({ description: "COMPRA POSTO ABC", type: "EXPENSE" });
    await user.click(screen.getByRole("button", { name: "Usar" }));

    expect(screen.getByLabelText("Categoria")).toHaveValue("c2");
    expect(screen.getByLabelText("Contexto")).toHaveValue("x1");
    expect(screen.queryByText(/Sugestão de categoria/)).not.toBeInTheDocument();   // já escolhida: some
  });

  it("não sugere quando o motor não conhece o texto, nem quando a categoria já foi escolhida", async () => {
    testRule.mockResolvedValue({ category: { id: null, confidence: null, source: null, decision: "NONE" }, context: { id: null, confidence: null, source: null, decision: "NONE" }, decision: "NONE" });
    const user = userEvent.setup();
    form();
    await user.type(screen.getByLabelText("Descrição"), "LOJA QUALQUER");
    await waitFor(() => expect(testRule).toHaveBeenCalled(), { timeout: 3000 });
    expect(screen.queryByText(/Sugestão de categoria/)).not.toBeInTheDocument();

    testRule.mockClear();
    await user.selectOptions(screen.getByLabelText("Categoria"), "c2");
    await user.type(screen.getByLabelText("Descrição"), " 2");
    await new Promise((r) => setTimeout(r, 800));
    expect(testRule).not.toHaveBeenCalled();   // com categoria escolhida, não pergunta
  });

  it("não consulta com descrição curta nem para transferência", async () => {
    const user = userEvent.setup();
    form();
    await user.type(screen.getByLabelText("Descrição"), "ab");
    await new Promise((r) => setTimeout(r, 800));
    expect(testRule).not.toHaveBeenCalled();

    await user.click(screen.getByLabelText("Transferência"));
    await user.type(screen.getByLabelText("Descrição"), "cdefgh");
    await new Promise((r) => setTimeout(r, 800));
    expect(testRule).not.toHaveBeenCalled();
  });

  it("se a consulta de sugestão falhar, o formulário segue normal", async () => {
    testRule.mockRejectedValue(new Error("falhou"));
    const user = userEvent.setup();
    form();
    await user.type(screen.getByLabelText("Descrição"), "COMPRA POSTO ABC");
    await waitFor(() => expect(testRule).toHaveBeenCalled(), { timeout: 3000 });

    expect(screen.queryByText(/Sugestão de categoria/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar" })).toBeEnabled();
  });
});
