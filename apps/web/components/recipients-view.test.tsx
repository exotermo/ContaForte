import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Page, Recipient } from "@/lib/types";
import { categories, contexts, recipient, renderWithQuery } from "./test-utils";
import { RecipientsView } from "./recipients-view";

const listRecipients = vi.fn();
const forgetRecipient = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listCategories: () => Promise.resolve(categories),
  listContexts: () => Promise.resolve(contexts),
  listRecipients: (...a: unknown[]) => listRecipients(...a),
  forgetRecipient: (...a: unknown[]) => forgetRecipient(...a),
}));

afterEach(() => [listRecipients, forgetRecipient].forEach((m) => m.mockReset()));

const page = (items: Recipient[], over: Partial<Page<Recipient>> = {}): Page<Recipient> => ({
  items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0, ...over,
});

function setup(items = [recipient()]) {
  listRecipients.mockResolvedValue(page(items));
  return { ...renderWithQuery(<RecipientsView />), user: userEvent.setup() };
}

describe("RecipientsView", () => {
  it("mostra o que foi aprendido de cada direção, com votos, confiança e o que aconteceria", async () => {
    setup([
      recipient({
        income: { category: { id: "c1", votes: 1, totalVotes: 1, confidence: 0.5, decision: "SUGGEST" }, context: { id: "x1", votes: 2, totalVotes: 5, confidence: 0.33, decision: "NONE" } },
      }),
    ]);

    const f = (await screen.findByText("Café da Praça")).closest("li")!;
    expect(f).toHaveTextContent("6 decisões suas · média R$ 8,00 · última em 20/11/2026");
    expect(f).toHaveTextContent("Você paga: categoria: Alimentação › Restaurante (6 de 6 decisões · 85% · aplica sozinho)");
    expect(f).toHaveTextContent("Ele te paga: categoria: Alimentação (1 de 1 decisão · 50% · só sugere) · contexto: Família (2 de 5 decisões · 33% · ainda não sugere)");
  });

  it("explica o estado vazio e o de busca sem resultado", async () => {
    const { user } = setup([]);
    expect(await screen.findByText(/Nenhum favorecido ainda/)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Buscar por nome"), "zzz");
    await user.click(screen.getByRole("button", { name: "Buscar" }));
    expect(await screen.findByText("Nenhum favorecido com esse nome.")).toBeInTheDocument();
  });

  it("busca por nome e volta para a primeira página", async () => {
    const { user } = setup();
    await screen.findByText("Café da Praça");

    await user.type(screen.getByLabelText("Buscar por nome"), "  cafe ");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() => expect(listRecipients).toHaveBeenLastCalledWith("cafe", 0, 20));
  });

  it("esquecer pede confirmação, explica o efeito e só então apaga", async () => {
    forgetRecipient.mockResolvedValue(undefined);
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Esquecer Café da Praça" }));
    expect(screen.getByText(/As transações continuam como estão/)).toBeInTheDocument();
    expect(forgetRecipient).not.toHaveBeenCalled();

    await user.click(within(screen.getByRole("group", { name: "Confirmar esquecer Café da Praça" })).getByRole("button", { name: "Esquecer favorecido" }));
    await waitFor(() => expect(forgetRecipient).toHaveBeenCalledWith("f1"));
  });

  it("cancelar não apaga nada", async () => {
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Esquecer Café da Praça" }));
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(forgetRecipient).not.toHaveBeenCalled();
  });

  it("pagina a lista", async () => {
    listRecipients.mockImplementation((_q: string, p: number) => Promise.resolve(page([recipient({ id: `f${p}`, displayName: `Pessoa ${p}` })], { totalElements: 30, totalPages: 2, page: p })));
    const user = userEvent.setup();
    renderWithQuery(<RecipientsView />);

    expect(await screen.findByText("Pessoa 0")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Próxima" }));
    expect(await screen.findByText("Pessoa 1")).toBeInTheDocument();
  });
});
