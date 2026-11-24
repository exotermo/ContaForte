import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/finance";
import { categories, contexts, renderWithQuery, rule } from "./test-utils";
import { RulesView } from "./rules-view";

const listRules = vi.fn();
const createRule = vi.fn();
const updateRule = vi.fn();
const deleteRule = vi.fn();
const testRule = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listCategories: () => Promise.resolve(categories),
  listContexts: () => Promise.resolve(contexts),
  listRules: (...a: unknown[]) => listRules(...a),
  createRule: (...a: unknown[]) => createRule(...a),
  updateRule: (...a: unknown[]) => updateRule(...a),
  deleteRule: (...a: unknown[]) => deleteRule(...a),
  testRule: (...a: unknown[]) => testRule(...a),
}));

afterEach(() => [listRules, createRule, updateRule, deleteRule, testRule].forEach((m) => m.mockReset()));

function setup(rules = [rule()]) {
  listRules.mockResolvedValue(rules);
  return { ...renderWithQuery(<RulesView />), user: userEvent.setup() };
}

describe("RulesView", () => {
  it("lista as regras com comparação, tipo, destino e prioridade", async () => {
    setup([rule(), rule({ id: "r2", matchType: "STARTS_WITH", pattern: "academia", transactionType: null, categoryId: null, contextId: "x2", priority: 5, enabled: false })]);

    const a = (await screen.findByText(/Contém/)).closest("li")!;
    expect(a).toHaveTextContent("Contém “mercadinho do ze”");
    expect(a).toHaveTextContent("Só despesas → Alimentação › Restaurante · prioridade 100");
    const b = screen.getByText(/Começa com/).closest("li")!;
    expect(b).toHaveTextContent("Receita ou despesa → Pet · prioridade 5");
    expect(within(b).getByRole("checkbox", { name: "Regra academia ativa" })).not.toBeChecked();
  });

  it("estado vazio explica como criar", async () => {
    setup([]);
    expect(await screen.findByText(/Nenhuma regra sua ainda/)).toBeInTheDocument();
  });

  it("cria uma regra com os campos escolhidos", async () => {
    createRule.mockResolvedValue(rule());
    const { user } = setup([]);
    await user.click(await screen.findByRole("button", { name: "Nova regra" }));

    await user.selectOptions(screen.getByLabelText("Comparação"), "STARTS_WITH");
    await user.type(screen.getByLabelText("Trecho da descrição"), "  Academia Força ");
    await user.selectOptions(screen.getByLabelText("Vale para"), "EXPENSE");
    await user.selectOptions(screen.getByLabelText("Categoria"), "c2");
    await user.selectOptions(screen.getByLabelText("Contexto"), "x2");
    await user.click(screen.getByRole("button", { name: "Criar regra" }));

    await waitFor(() =>
      expect(createRule).toHaveBeenCalledWith({ matchType: "STARTS_WITH", pattern: "Academia Força", transactionType: "EXPENSE", categoryId: "c2", contextId: "x2", priority: 100 }),
    );
    await waitFor(() => expect(screen.queryByRole("form", { name: "Nova regra" })).not.toBeInTheDocument());   // fecha ao salvar
  });

  it("valida no cliente: trecho curto, sem alvo e prioridade fora da faixa", async () => {
    const { user } = setup([]);
    await user.click(await screen.findByRole("button", { name: "Nova regra" }));

    await user.type(screen.getByLabelText("Trecho da descrição"), "a!");
    await user.click(screen.getByRole("button", { name: "Criar regra" }));
    expect(screen.getByRole("alert")).toHaveTextContent("ao menos 3 letras ou números");

    await user.clear(screen.getByLabelText("Trecho da descrição"));
    await user.type(screen.getByLabelText("Trecho da descrição"), "mercado");
    await user.click(screen.getByRole("button", { name: "Criar regra" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Escolha uma categoria ou um contexto.");

    await user.selectOptions(screen.getByLabelText("Categoria"), "c2");
    await user.clear(screen.getByLabelText("Prioridade"));
    await user.type(screen.getByLabelText("Prioridade"), "20000");
    await user.click(screen.getByRole("button", { name: "Criar regra" }));
    expect(screen.getByRole("alert")).toHaveTextContent("A prioridade vai de 0 a 10000");
    expect(createRule).not.toHaveBeenCalled();
  });

  it("mostra o erro do servidor (condição repetida) e mantém o formulário", async () => {
    createRule.mockRejectedValue(new ApiError(409, "Já existe uma regra com esta condição."));
    const { user } = setup([]);
    await user.click(await screen.findByRole("button", { name: "Nova regra" }));
    await user.type(screen.getByLabelText("Trecho da descrição"), "mercado");
    await user.selectOptions(screen.getByLabelText("Categoria"), "c2");
    await user.click(screen.getByRole("button", { name: "Criar regra" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Já existe uma regra com esta condição.");
    expect(screen.getByRole("form", { name: "Nova regra" })).toBeInTheDocument();
  });

  it("editar preenche o formulário e limpa com nulo o que foi esvaziado", async () => {
    updateRule.mockResolvedValue(rule());
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Editar a regra mercadinho do ze" }));

    expect(screen.getByLabelText("Trecho da descrição")).toHaveValue("mercadinho do ze");
    expect(screen.getByLabelText("Vale para")).toHaveValue("EXPENSE");
    await user.selectOptions(screen.getByLabelText("Vale para"), "");           // volta a "receita ou despesa"
    await user.selectOptions(screen.getByLabelText("Contexto"), "x1");
    await user.click(screen.getByRole("button", { name: "Salvar regra" }));

    await waitFor(() =>
      expect(updateRule).toHaveBeenCalledWith("r1", { matchType: "CONTAINS", pattern: "mercadinho do ze", transactionType: null, categoryId: "c2", contextId: "x1", priority: 100 }),
    );
  });

  it("ativar e desativar chama a API com o novo valor", async () => {
    updateRule.mockResolvedValue(rule({ enabled: false }));
    const { user } = setup();
    await user.click(await screen.findByRole("checkbox", { name: "Regra mercadinho do ze ativa" }));

    await waitFor(() => expect(updateRule).toHaveBeenCalledWith("r1", { enabled: false }));
  });

  it("apagar pede confirmação e explica que as transações não mudam", async () => {
    deleteRule.mockResolvedValue(undefined);
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Apagar a regra mercadinho do ze" }));
    expect(screen.getByText(/não altera transações já classificadas/)).toBeInTheDocument();
    expect(deleteRule).not.toHaveBeenCalled();

    await user.click(within(screen.getByRole("group", { name: "Confirmar exclusão da regra mercadinho do ze" })).getByRole("button", { name: "Apagar regra" }));
    await waitFor(() => expect(deleteRule).toHaveBeenCalledWith("r1"));
  });

  it("cancelar a exclusão não chama a API", async () => {
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Apagar a regra mercadinho do ze" }));
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(deleteRule).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Apagar a regra mercadinho do ze" })).toBeInTheDocument();
  });

  it("testar um texto mostra categoria e contexto com origem, confiança e o que aconteceria", async () => {
    testRule.mockResolvedValue({
      category: { id: "c2", confidence: 1, source: "RULE", decision: "AUTO" },
      context: { id: "x1", confidence: 0.66, source: "RECIPIENT", decision: "SUGGEST" },
      decision: "SUGGEST",
    });
    const { user } = setup();
    await user.type(await screen.findByLabelText("Descrição"), "PIX ENVIADO Café da Praça");
    await user.selectOptions(screen.getByLabelText("Tipo"), "EXPENSE");
    await user.click(screen.getByRole("button", { name: "Testar" }));

    await waitFor(() => expect(testRule).toHaveBeenCalledWith({ description: "PIX ENVIADO Café da Praça", type: "EXPENSE" }));
    const resultado = await screen.findByRole("status");
    expect(resultado).toHaveTextContent("Categoria: Alimentação › Restaurante (Sua regra · 100% · aplicaria sozinha)");
    expect(resultado).toHaveTextContent("Contexto: Família (Favorecido conhecido · 66% · só sugeriria)");
  });

  it("testar um texto que ninguém decide diz que ficaria sem categoria", async () => {
    testRule.mockResolvedValue({ category: { id: null, confidence: null, source: null, decision: "NONE" }, context: { id: null, confidence: null, source: null, decision: "NONE" }, decision: "NONE" });
    const { user } = setup();
    await user.type(await screen.findByLabelText("Descrição"), "LOJA QUALQUER");
    await user.click(screen.getByRole("button", { name: "Testar" }));

    expect(await screen.findByRole("status")).toHaveTextContent("ficaria sem categoria");
  });

  it("o interruptor muda na hora e volta ao estado do servidor se a API falhar", async () => {
    let rejeitar: (e: Error) => void = () => undefined;
    updateRule.mockReturnValue(new Promise((_, rej) => { rejeitar = rej; }));
    const { user } = setup();
    const caixa = await screen.findByRole("checkbox", { name: "Regra mercadinho do ze ativa" });
    expect(caixa).toBeChecked();

    await user.click(caixa);
    expect(caixa).not.toBeChecked();                 // mudou antes de a API responder

    rejeitar(new ApiError(500, "Serviço indisponível no momento. Tente novamente em instantes."));
    await waitFor(() => expect(caixa).toBeChecked()); // voltou
    expect(await screen.findByRole("alert")).toHaveTextContent("Serviço indisponível");
  });
});
