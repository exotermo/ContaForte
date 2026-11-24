import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/finance";
import { batch, item, pageOf, renderWithQuery } from "./test-utils";
import { ImportReview } from "./import-review";

const listImportItems = vi.fn();
const confirmImport = vi.fn();
const discardImport = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listImportItems: (...a: unknown[]) => listImportItems(...a),
  confirmImport: (...a: unknown[]) => confirmImport(...a),
  discardImport: (...a: unknown[]) => discardImport(...a),
}));

afterEach(() => {
  listImportItems.mockReset();
  confirmImport.mockReset();
  discardImport.mockReset();
});

const novas = [item({ id: "n1", lineNo: 1 }), item({ id: "n2", lineNo: 2, amount: "18.50", description: "PIX ENVIADO CAFÉ", merchant: null }), item({ id: "n3", lineNo: 3, type: "INCOME", amount: "6500.00", description: "SALÁRIO" })];

function setup(b = batch({ totalRows: 3, newRows: 3 }), onConfirmed = vi.fn(), onDiscarded = vi.fn()) {
  listImportItems.mockImplementation((_id: string, opts: { status: string }) => {
    if (opts.status === "NEW") return Promise.resolve(pageOf(novas));
    if (opts.status === "POSSIBLE_DUPLICATE") {
      return Promise.resolve(pageOf([
        item({ id: "p1", lineNo: 10, status: "POSSIBLE_DUPLICATE", selected: false, reason: "Mesmo valor em data próxima (até 1 dia), com descrição diferente", amount: "64.99", description: "COMPRA FARMÁCIA", duplicateOf: "x1",
          existing: { id: "x1", occurredAt: "2026-03-18T15:00:00Z", type: "EXPENSE", amount: "64.99", description: "remédio", source: "MANUAL" } }),
        item({ id: "p2", lineNo: 11, status: "POSSIBLE_DUPLICATE", selected: false, amount: "10.00", description: "OUTRA" }),
      ]));
    }
    if (opts.status === "DUPLICATE") {
      return Promise.resolve(pageOf([item({ id: "d1", lineNo: 4, status: "DUPLICATE", selected: false, reason: "Identificador do extrato já importado nesta conta", duplicateOf: "x2",
        existing: { id: "x2", occurredAt: "2026-03-02T15:00:00Z", type: "EXPENSE", amount: "187.45", description: "COMPRA CARTAO MERCADO EXEMPLO", source: "OFX" } })]));
    }
    return Promise.resolve(pageOf([item({ id: "v1", lineNo: 5, status: "INVALID", selected: false, occurredAt: null, type: null, amount: null, description: null, merchant: null, reason: "data inválida" })]));
  });
  const view = renderWithQuery(<ImportReview batch={b} onConfirmed={onConfirmed} onDiscarded={onDiscarded} />);
  return { ...view, onConfirmed, onDiscarded, user: userEvent.setup() };
}

describe("ImportReview", () => {
  it("abre na aba das novas, com contagens, sinais e a promessa de que nada foi criado", async () => {
    setup();

    expect(screen.getByText(/Nada foi criado ainda/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Novas (3)" })).toHaveAttribute("aria-pressed", "true");
    const linha = (await screen.findByText("COMPRA CARTAO MERCADO EXEMPLO")).closest("tr")!;
    expect(linha).toHaveTextContent("02/03/2026");
    expect(linha).toHaveTextContent("-R$ 187,45");
    expect(screen.getByText("SALÁRIO").closest("tr")).toHaveTextContent("+R$ 6.500,00");
    expect(screen.getByText("Serão importadas 3 transações.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Importar 3 transações" })).toBeEnabled();
  });

  it("começa na primeira aba que tem linhas", async () => {
    setup(batch({ totalRows: 2, newRows: 0, possibleRows: 2 }));
    await screen.findByText("COMPRA FARMÁCIA");
    expect(screen.getByRole("button", { name: "Possíveis duplicatas (2)" })).toHaveAttribute("aria-pressed", "true");
  });

  it("desmarcar linhas novas baixa o total e vira a lista 'exclude' ao confirmar", async () => {
    confirmImport.mockResolvedValue(batch({ status: "CONFIRMED", importedRows: 2 }));
    const { user, onConfirmed } = setup();
    await screen.findByText("SALÁRIO");

    await user.click(screen.getByLabelText("Importar a linha 2"));

    expect(screen.getByText("Serão importadas 2 transações.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Importar 2 transações" }));
    await waitFor(() => expect(confirmImport).toHaveBeenCalledWith("i1", { includePossible: [], exclude: ["n2"] }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalled());
  });

  it("a caixa do cabeçalho marca ou desmarca a página inteira", async () => {
    const { user } = setup();
    await screen.findByText("SALÁRIO");

    await user.click(screen.getByLabelText("Marcar todas as linhas desta página"));   // estava tudo marcado: desmarca
    expect(screen.getByText("Nada selecionado para importar.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Importar 0 transações$/ })).toBeDisabled();

    await user.click(screen.getByLabelText("Marcar todas as linhas desta página"));
    expect(screen.getByText("Serão importadas 3 transações.")).toBeInTheDocument();
  });

  it("possíveis duplicatas vêm desmarcadas, mostram o lançamento existente e só entram se aceitas", async () => {
    confirmImport.mockResolvedValue(batch({ status: "CONFIRMED", importedRows: 4 }));
    const { user } = setup(batch({ totalRows: 5, newRows: 3, possibleRows: 2 }));
    await user.click(screen.getByRole("button", { name: "Possíveis duplicatas (2)" }));

    const linha = (await screen.findByText("COMPRA FARMÁCIA")).closest("tr")!;
    expect(linha).toHaveTextContent("Mesmo valor em data próxima");
    expect(linha).toHaveTextContent("18/03/2026 · R$ 64,99");   // o lançamento com o qual foi comparada
    expect(linha).toHaveTextContent("remédio · Manual");
    expect(within(linha).getByLabelText("Importar a linha 10")).not.toBeChecked();
    expect(screen.getByText("Serão importadas 3 transações.")).toBeInTheDocument();

    await user.click(within(linha).getByLabelText("Importar a linha 10"));
    expect(screen.getByText("Serão importadas 4 transações.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Importar 4 transações" }));
    await waitFor(() => expect(confirmImport).toHaveBeenCalledWith("i1", { includePossible: ["p1"], exclude: [] }));
  });

  it("marcar e desmarcar de novo uma possível duplicata não deixa resíduo na lista", async () => {
    confirmImport.mockResolvedValue(batch());
    const { user } = setup(batch({ totalRows: 5, newRows: 3, possibleRows: 2 }));
    await user.click(screen.getByRole("button", { name: "Possíveis duplicatas (2)" }));
    const caixa = await screen.findByLabelText("Importar a linha 10");
    await user.click(caixa);
    await user.click(caixa);

    await user.click(screen.getByRole("button", { name: "Importar 3 transações" }));
    await waitFor(() => expect(confirmImport).toHaveBeenCalledWith("i1", { includePossible: [], exclude: [] }));
  });

  it("duplicadas e inválidas não têm caixa de seleção e mostram o motivo", async () => {
    const { user } = setup(batch({ totalRows: 5, newRows: 3, duplicateRows: 1, invalidRows: 1 }));
    await user.click(screen.getByRole("button", { name: "Duplicadas (1)" }));
    const dup = (await screen.findByText("COMPRA CARTAO MERCADO EXEMPLO")).closest("tr")!;
    expect(dup).toHaveTextContent("Identificador do extrato já importado");
    expect(dup).toHaveTextContent("OFX");
    expect(screen.queryByLabelText(/Importar a linha/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Inválidas (1)" }));
    const inv = (await screen.findByText("data inválida")).closest("tr")!;
    expect(inv).toHaveTextContent("—");
    expect(screen.queryByLabelText(/Importar a linha/)).not.toBeInTheDocument();
  });

  it("avisa quando o arquivo já foi importado e repassa os avisos do servidor", async () => {
    setup(batch({ fileAlreadyImported: true, warnings: ["O arquivo não informa a moeda (CURDEF): assumindo reais."] }));
    expect(await screen.findByRole("status")).toHaveTextContent("Este arquivo já foi importado nesta conta antes");
    expect(screen.getByText(/assumindo reais/)).toBeInTheDocument();
  });

  it("pagina as linhas quando passam de 50", async () => {
    const { user } = setup(batch({ totalRows: 120, newRows: 120 }));
    listImportItems.mockImplementation((_id: string, opts: { page: number }) =>
      Promise.resolve(pageOf([item({ id: `x${opts.page}`, lineNo: opts.page * 50 + 1 })], { totalElements: 120, totalPages: 3, page: opts.page })));
    await user.click(screen.getByRole("button", { name: "Inválidas (0)" }));
    await user.click(screen.getByRole("button", { name: "Novas (120)" }));

    expect(await screen.findByText(/Página 1 de 3 · 120 linhas/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Próxima" }));
    await waitFor(() => expect(listImportItems).toHaveBeenLastCalledWith("i1", { status: "NEW", page: 1, size: 50 }));
  });

  it("descartar pede confirmação, só então apaga e avisa quem chamou", async () => {
    discardImport.mockResolvedValue(undefined);
    const { user, onDiscarded } = setup();
    await user.click(screen.getByRole("button", { name: "Descartar" }));
    expect(discardImport).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.getByRole("button", { name: "Descartar" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Descartar" }));
    await user.click(within(screen.getByRole("group", { name: "Confirmar descarte da prévia" })).getByRole("button", { name: "Descartar prévia" }));
    await waitFor(() => expect(discardImport).toHaveBeenCalledWith("i1"));
    await waitFor(() => expect(onDiscarded).toHaveBeenCalled());
  });

  it("mostra o erro do servidor ao confirmar (ex.: prévia vencida)", async () => {
    confirmImport.mockRejectedValue(new ApiError(410, "A prévia expirou. Envie o arquivo de novo."));
    const { user } = setup();
    await screen.findByText("SALÁRIO");
    await user.click(screen.getByRole("button", { name: "Importar 3 transações" }));
    expect(await screen.findByText("A prévia expirou. Envie o arquivo de novo.")).toBeInTheDocument();
  });

  it("falha ao carregar as linhas mostra aviso acessível", async () => {
    setup();
    listImportItems.mockRejectedValue(new Error("falhou"));
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Inválidas (0)" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível carregar as linhas.");
  });
});
