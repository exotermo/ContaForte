import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/finance";
import { batch, categories, classif, contexts, item, pageOf, renderWithQuery } from "./test-utils";
import { ImportReview } from "./import-review";

const listImportItems = vi.fn();
const classifyImportItem = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listCategories: () => Promise.resolve(categories),
  listContexts: () => Promise.resolve(contexts),
  listImportItems: (...a: unknown[]) => listImportItems(...a),
  classifyImportItem: (...a: unknown[]) => classifyImportItem(...a),
}));

afterEach(() => {
  listImportItems.mockReset();
  classifyImportItem.mockReset();
});

const auto = item({
  id: "n1", lineNo: 1, description: "COMPRA POSTO ABC",
  classification: classif({ decision: "AUTO", source: "DEFAULT_RULE", confidence: 0.9, categoryId: "c2", categoryDecision: "AUTO" }),
});
const sugerida = item({
  id: "n2", lineNo: 2, description: "PIX ENVIADO Café da Praça",
  classification: classif({ decision: "SUGGEST", source: "RECIPIENT", confidence: 0.66, categoryId: "c2", categoryDecision: "SUGGEST", contextId: "x1", contextDecision: "SUGGEST" }),
});
const nenhuma = item({ id: "n3", lineNo: 3, description: "LOJA QUALQUER", classification: classif() });
const escolhida = item({
  id: "n4", lineNo: 4, description: "PIX RECEBIDO JOANA",
  classification: classif({ chosen: true, chosenCategoryId: "c1", chosenContextId: "x2" }),
});

function setup(items = [auto, sugerida, nenhuma], b = batch({ totalRows: 3, newRows: 3, autoClassified: 1, suggested: 1 })) {
  listImportItems.mockImplementation((_id: string, opts: { status: string }) =>
    Promise.resolve(opts.status === "NEW" ? pageOf(items) : pageOf([item({ id: "d", lineNo: 9, status: "DUPLICATE", selected: false, classification: undefined })])),
  );
  return { ...renderWithQuery(<ImportReview batch={b} onConfirmed={vi.fn()} onDiscarded={vi.fn()} />), user: userEvent.setup() };
}

describe("ImportReview: classificação das linhas", () => {
  it("resume quantas linhas foram categorizadas sozinhas e quantas só sugeridas", async () => {
    setup();
    expect(await screen.findByText(/Categorizadas sozinhas:/)).toHaveTextContent("Categorizadas sozinhas: 1 · Só sugeridas: 1");
  });

  it("não mostra o resumo quando o motor não classificou nada", async () => {
    setup([nenhuma], batch({ totalRows: 1, newRows: 1, autoClassified: 0, suggested: 0 }));
    await screen.findByText("LOJA QUALQUER");
    expect(screen.queryByText(/Categorizadas sozinhas:/)).not.toBeInTheDocument();
  });

  it("linha automática mostra quem decidiu e a confiança, e já vem com a categoria nos seletores", async () => {
    setup();
    const linha = (await screen.findByText("COMPRA POSTO ABC")).closest("tr")!;

    expect(linha).toHaveTextContent("Automática · Regra do sistema · 90%");
    expect(screen.getByLabelText("Categoria da linha 1")).toHaveValue("c2");
    expect(screen.getByLabelText("Contexto da linha 1")).toHaveValue("");
  });

  it("linha sugerida NÃO vem aplicada: seletores vazios, sugestão à parte e botão para aceitar", async () => {
    setup();
    const linha = (await screen.findByText("PIX ENVIADO Café da Praça")).closest("tr")!;

    expect(linha).toHaveTextContent("Sugestão · Favorecido conhecido · 66%");
    expect(linha).toHaveTextContent("Sugerido: Alimentação › Restaurante · Família");
    expect(screen.getByLabelText("Categoria da linha 2")).toHaveValue("");
    expect(screen.getByLabelText("Contexto da linha 2")).toHaveValue("");
  });

  it("aceitar a sugestão grava a escolha com os alvos do motor", async () => {
    classifyImportItem.mockResolvedValue(item());
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Aceitar a sugestão da linha 2" }));

    await waitFor(() => expect(classifyImportItem).toHaveBeenCalledWith("i1", "n2", { categoryId: "c2", contextId: "x1" }));
    await waitFor(() => expect(listImportItems.mock.calls.length).toBeGreaterThan(1));   // a lista recarrega com a escolha
  });

  it("trocar a categoria mantém o contexto atual; trocar o contexto mantém a categoria atual; vazio vira nulo", async () => {
    classifyImportItem.mockResolvedValue(item());
    const { user } = setup();
    await screen.findByText("COMPRA POSTO ABC");

    await user.selectOptions(screen.getByLabelText("Contexto da linha 1"), "x2");
    await waitFor(() => expect(classifyImportItem).toHaveBeenLastCalledWith("i1", "n1", { categoryId: "c2", contextId: "x2" }));

    await user.selectOptions(screen.getByLabelText("Categoria da linha 1"), "");   // "Sem categoria"
    await waitFor(() => expect(classifyImportItem).toHaveBeenLastCalledWith("i1", "n1", { categoryId: null, contextId: null }));

    await user.selectOptions(screen.getByLabelText("Categoria da linha 3"), "c1");
    await waitFor(() => expect(classifyImportItem).toHaveBeenLastCalledWith("i1", "n3", { categoryId: "c1", contextId: null }));
  });

  it("linha sem sugestão diz isso e não oferece 'Aceitar'", async () => {
    setup();
    const linha = (await screen.findByText("LOJA QUALQUER")).closest("tr")!;

    expect(linha).toHaveTextContent("Sem sugestão");
    expect(screen.queryByRole("button", { name: "Aceitar a sugestão da linha 3" })).not.toBeInTheDocument();
  });

  it("linha com escolha do usuário mostra 'Sua escolha' e permite voltar ao que o sistema decidiu", async () => {
    classifyImportItem.mockResolvedValue(item());
    const { user } = setup([escolhida]);
    const linha = (await screen.findByText("PIX RECEBIDO JOANA")).closest("tr")!;

    expect(linha).toHaveTextContent("Sua escolha");
    expect(screen.getByLabelText("Categoria da linha 4")).toHaveValue("c1");
    expect(screen.getByLabelText("Contexto da linha 4")).toHaveValue("x2");
    await user.click(screen.getByRole("button", { name: "Voltar a linha 4 ao que o sistema decidiu" }));

    await waitFor(() => expect(classifyImportItem).toHaveBeenCalledWith("i1", "n4", { reset: true }));
  });

  it("mostra o erro do servidor ao trocar e não perde a linha", async () => {
    classifyImportItem.mockRejectedValue(new ApiError(404, "Categoria não encontrada."));
    const { user } = setup();
    await user.selectOptions(await screen.findByLabelText("Categoria da linha 3"), "c1");

    expect(await screen.findByRole("alert")).toHaveTextContent("Categoria não encontrada.");
    expect(screen.getByText("LOJA QUALQUER")).toBeInTheDocument();
  });

  it("duplicadas não têm coluna de categoria", async () => {
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: /^Duplicadas/ }));

    await screen.findByText("Nenhuma linha nesta aba.").catch(() => undefined);
    expect(screen.queryByRole("columnheader", { name: "Categoria" })).not.toBeInTheDocument();
  });
});
