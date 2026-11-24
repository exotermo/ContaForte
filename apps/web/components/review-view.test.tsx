import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/finance";
import type { Page, Transaction } from "@/lib/types";
import { categories, contexts, renderWithQuery, tx } from "./test-utils";
import { ReviewView } from "./review-view";

const reviewQueue = vi.fn();
const reviewCount = vi.fn();
const decideClassification = vi.fn();
const reclassifyPreview = vi.fn();
const reclassify = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listCategories: () => Promise.resolve(categories),
  listContexts: () => Promise.resolve(contexts),
  reviewQueue: (...a: unknown[]) => reviewQueue(...a),
  reviewCount: (...a: unknown[]) => reviewCount(...a),
  decideClassification: (...a: unknown[]) => decideClassification(...a),
  reclassifyPreview: (...a: unknown[]) => reclassifyPreview(...a),
  reclassify: (...a: unknown[]) => reclassify(...a),
}));

afterEach(() => [reviewQueue, reviewCount, decideClassification, reclassifyPreview, reclassify].forEach((m) => m.mockReset()));

const page = (items: Transaction[], over: Partial<Page<Transaction>> = {}): Page<Transaction> => ({
  items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0, ...over,
});

const sugerida = tx({ id: "t1", description: "PIX ENVIADO Café da Praça", amount: "8.00", categoryId: null, classificationStatus: "SUGGESTED", classificationSource: "RECIPIENT", classificationConfidence: 0.66, suggestedCategoryId: "c2", suggestedContextId: "x1" });
const semCategoria = tx({ id: "t2", description: "LOJA QUALQUER", amount: "99.00", categoryId: null });

function setup(items = [sugerida, semCategoria]) {
  reviewQueue.mockResolvedValue(page(items));
  reviewCount.mockResolvedValue({ suggested: 1, uncategorized: 1 });
  return { ...renderWithQuery(<ReviewView />), user: userEvent.setup() };
}

describe("ReviewView", () => {
  it("mostra os contadores, a sugestão com quem decidiu e a confiança, e o que está sem categoria", async () => {
    setup();

    expect(await screen.findByText(/sugestão aguarda/)).toBeInTheDocument();
    const linha = screen.getByText("PIX ENVIADO Café da Praça").closest("li")!;
    expect(linha).toHaveTextContent("-R$ 8,00");
    expect(linha).toHaveTextContent("Sugestão · Favorecido conhecido · 66%");
    expect(linha).toHaveTextContent("Restaurante · Família");
    expect(screen.getByText("LOJA QUALQUER").closest("li")).toHaveTextContent("Sem categoria e sem sugestão.");
  });

  it("confirmar a sugestão chama a API com CONFIRM e recarrega a fila", async () => {
    decideClassification.mockResolvedValue(tx());
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Confirmar a sugestão de PIX ENVIADO Café da Praça" }));

    await waitFor(() => expect(decideClassification).toHaveBeenCalledWith("t1", { action: "CONFIRM" }));
    await waitFor(() => expect(reviewQueue.mock.calls.length).toBeGreaterThan(1));   // fila recarregada
  });

  it("só quem tem sugestão ganha 'Confirmar'; escolher abre o editor já com a sugestão", async () => {
    const { user } = setup();
    await screen.findByText("LOJA QUALQUER");

    expect(screen.queryByRole("button", { name: /Confirmar a sugestão de LOJA/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Escolher a categoria de PIX ENVIADO Café da Praça" }));
    expect(screen.getByLabelText("Categoria de PIX ENVIADO Café da Praça")).toHaveValue("c2");
    expect(screen.getByLabelText("Contexto de PIX ENVIADO Café da Praça")).toHaveValue("x1");
  });

  it("corrigir envia categoria e contexto; com 'aplicar sempre' envia também a regra", async () => {
    decideClassification.mockResolvedValue(tx());
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Escolher a categoria de LOJA QUALQUER" }));
    const salvar = screen.getByRole("button", { name: "Salvar escolha" });
    expect(salvar).toBeDisabled();                                  // sem categoria nem contexto

    await user.selectOptions(screen.getByLabelText("Categoria de LOJA QUALQUER"), "c2");
    await user.click(screen.getByLabelText("Aplicar sempre que a descrição tiver este trecho"));
    const trecho = screen.getByLabelText("Trecho da descrição");
    expect(trecho).toHaveValue("LOJA QUALQUER");
    await user.clear(trecho);
    await user.type(trecho, "loja qualquer");
    await user.click(screen.getByRole("button", { name: "Salvar escolha" }));

    await waitFor(() =>
      expect(decideClassification).toHaveBeenCalledWith("t2", { action: "CORRECT", categoryId: "c2", createRule: { matchType: "CONTAINS", pattern: "loja qualquer" } }),
    );
  });

  it("sem marcar 'aplicar sempre' não envia regra", async () => {
    decideClassification.mockResolvedValue(tx());
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Escolher a categoria de LOJA QUALQUER" }));
    await user.selectOptions(screen.getByLabelText("Contexto de LOJA QUALQUER"), "x2");
    await user.click(screen.getByRole("button", { name: "Salvar escolha" }));

    await waitFor(() => expect(decideClassification).toHaveBeenCalledWith("t2", { action: "CORRECT", contextId: "x2" }));
  });

  it("mostra o erro do servidor (ex.: regra que não casa) sem fechar o editor", async () => {
    decideClassification.mockRejectedValue(new ApiError(422, "O padrão da regra não casa com esta transação."));
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Escolher a categoria de LOJA QUALQUER" }));
    await user.selectOptions(screen.getByLabelText("Categoria de LOJA QUALQUER"), "c2");
    await user.click(screen.getByRole("button", { name: "Salvar escolha" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("O padrão da regra não casa com esta transação.");
    expect(screen.getByRole("button", { name: "Salvar escolha" })).toBeInTheDocument();
  });

  it("fila vazia mostra que está tudo classificado", async () => {
    setup([]);
    expect(await screen.findByText(/Nada para revisar/)).toBeInTheDocument();
  });

  // ---------------------------------------------------------------- reclassificar em duas fases

  it("mostra a prévia da reclassificação e só então aplica o que foi visto", async () => {
    reclassifyPreview.mockResolvedValue({ eligible: 4, auto: 2, suggest: 1, none: 1, truncated: false, sample: [] });
    reclassify.mockResolvedValue({ auto: 2, suggest: 1, unchanged: 1 });
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Ver o que seria categorizado" }));

    expect(await screen.findByText(/seriam categorizadas sozinhas/)).toHaveTextContent("Entre 4 transações: 2 seriam categorizadas sozinhas, 1 ficariam como sugestão e 1 continuariam sem sugestão.");
    expect(reclassify).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Aplicar" }));

    await waitFor(() => expect(reclassify).toHaveBeenCalledWith({ expectedAuto: 2, expectedSuggest: 1 }));
    expect(await screen.findByRole("status")).toHaveTextContent("2 categorizadas sozinhas e 1 sugeridas");
  });

  it("sem nada a aplicar o botão fica desabilitado; cancelar volta ao início", async () => {
    reclassifyPreview.mockResolvedValue({ eligible: 1, auto: 0, suggest: 0, none: 1, truncated: true, sample: [] });
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Ver o que seria categorizado" }));

    expect(await screen.findByText(/Há mais transações do que cabe/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Aplicar" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.getByRole("button", { name: "Ver o que seria categorizado" })).toBeInTheDocument();
  });

  it("409 ao aplicar (resultado mudou) mostra a mensagem e deixa tentar de novo", async () => {
    reclassifyPreview.mockResolvedValue({ eligible: 1, auto: 1, suggest: 0, none: 0, truncated: false, sample: [] });
    reclassify.mockRejectedValue(new ApiError(409, "O resultado mudou desde a prévia. Confira a prévia de novo antes de aplicar."));
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Ver o que seria categorizado" }));
    await user.click(await screen.findByRole("button", { name: "Aplicar" }));

    expect(await screen.findByText(/mudou desde a prévia/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Aplicar" })).toBeEnabled();
  });

  it("pagina a fila", async () => {
    reviewQueue.mockImplementation((p: number) => Promise.resolve(page([tx({ id: `p${p}`, description: `Item ${p}`, categoryId: null })], { totalElements: 45, totalPages: 3, page: p })));
    reviewCount.mockResolvedValue({ suggested: 0, uncategorized: 45 });
    const user = userEvent.setup();
    renderWithQuery(<ReviewView />);

    expect(await screen.findByText("Item 0")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Próxima" }));
    expect(await screen.findByText("Item 1")).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "Paginação da fila" })).getByText(/Página 2 de 3/)).toBeInTheDocument();
  });
});
