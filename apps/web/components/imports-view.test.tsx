import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { account, batch, item, pageOf, renderWithQuery } from "./test-utils";
import { ImportsView } from "./imports-view";

const listImports = vi.fn();
const listAccounts = vi.fn();
const rollbackImport = vi.fn();
const discardImport = vi.fn();
const getImport = vi.fn();
const listImportItems = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listImports: (...a: unknown[]) => listImports(...a),
  listAccounts: (...a: unknown[]) => listAccounts(...a),
  rollbackImport: (...a: unknown[]) => rollbackImport(...a),
  discardImport: (...a: unknown[]) => discardImport(...a),
  getImport: (...a: unknown[]) => getImport(...a),
  listImportItems: (...a: unknown[]) => listImportItems(...a),
}));

afterEach(() => [listImports, listAccounts, rollbackImport, discardImport, getImport, listImportItems].forEach((m) => m.mockReset()));

const lote = (over = {}) => batch({ id: "i1", fileName: "conta-corrente-1.ofx", status: "CONFIRMED", importedRows: 12, confirmedAt: "2026-11-24T12:00:00Z", ...over });

function setup(rows = [lote()]) {
  listAccounts.mockResolvedValue([account({ id: "a1", name: "Corrente" })]);
  listImports.mockResolvedValue(pageOf(rows));
  listImportItems.mockResolvedValue(pageOf([item()]));
  return { ...renderWithQuery(<ImportsView />), user: userEvent.setup() };
}

describe("ImportsView", () => {
  it("lista o histórico com arquivo, data, formato, conta, status e contagens", async () => {
    setup([lote(), batch({ id: "i2", fileName: "pendente.csv", format: "CSV", status: "PREVIEW", newRows: 5, duplicateRows: 2, possibleRows: 1, invalidRows: 1, totalRows: 9 }), lote({ id: "i3", fileName: "antiga.ofx", status: "ROLLED_BACK" })]);

    const confirmada = (await screen.findByText("conta-corrente-1.ofx")).closest("li")!;
    expect(confirmada).toHaveTextContent("24/11/2026 · OFX · Corrente");
    expect(confirmada).toHaveTextContent("Importada");
    expect(confirmada).toHaveTextContent("12 transações · 12 linhas no arquivo");
    const previa = screen.getByText("pendente.csv").closest("li")!;
    expect(previa).toHaveTextContent("Aguardando confirmação");
    expect(previa).toHaveTextContent("5 novas · 2 duplicadas · 1 possíveis · 1 inválidas");
    expect(screen.getByText("antiga.ofx").closest("li")).toHaveTextContent("Desfeita");
  });

  it("estado vazio convida a enviar o primeiro extrato", async () => {
    setup([]);
    expect(await screen.findByText(/Nenhuma importação ainda/)).toBeInTheDocument();
  });

  it("importação confirmada tem link para as transações dela", async () => {
    setup();
    await screen.findByText("conta-corrente-1.ofx");
    expect(screen.getByRole("link", { name: "Ver transações" })).toHaveAttribute("href", "/transacoes?importId=i1");
  });

  it("desfazer explica o efeito e só chama a API depois de confirmar", async () => {
    rollbackImport.mockResolvedValue(lote({ status: "ROLLED_BACK" }));
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Desfazer conta-corrente-1.ofx" }));

    const aviso = screen.getByRole("alertdialog");
    expect(aviso).toHaveTextContent("As 12 transações desta importação saem dos saldos e do resumo");
    expect(rollbackImport).not.toHaveBeenCalled();

    await user.click(within(aviso).getByRole("button", { name: "Desfazer importação" }));
    await waitFor(() => expect(rollbackImport).toHaveBeenCalledWith("i1"));
  });

  it("cancelar o desfazer não chama a API", async () => {
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Desfazer conta-corrente-1.ofx" }));
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(rollbackImport).not.toHaveBeenCalled();
  });

  it("descartar uma prévia confirma antes e diz que nada muda nos saldos", async () => {
    discardImport.mockResolvedValue(undefined);
    const { user } = setup([batch({ id: "i2", fileName: "pendente.ofx" })]);
    await user.click(await screen.findByRole("button", { name: "Descartar pendente.ofx" }));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Nenhuma transação foi criada");
    expect(discardImport).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Descartar prévia" }));
    await waitFor(() => expect(discardImport).toHaveBeenCalledWith("i2"));
  });

  it("mostra o erro do servidor ao desfazer", async () => {
    rollbackImport.mockRejectedValue(new Error("Só uma importação confirmada pode ser desfeita."));
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Desfazer conta-corrente-1.ofx" }));
    await user.click(screen.getByRole("button", { name: "Desfazer importação" }));
    expect(await screen.findByText("Só uma importação confirmada pode ser desfeita.")).toBeInTheDocument();
  });

  it("revisar uma prévia pendente abre o assistente direto na revisão", async () => {
    getImport.mockResolvedValue(batch({ id: "i2", fileName: "pendente.ofx", totalRows: 1, newRows: 1 }));
    const { user } = setup([batch({ id: "i2", fileName: "pendente.ofx" })]);
    await user.click(await screen.findByRole("button", { name: "Revisar pendente.ofx" }));

    expect(await screen.findByText("Revise antes de importar")).toBeInTheDocument();
    expect(getImport).toHaveBeenCalledWith("i2");
    expect(screen.queryByRole("button", { name: "Nova importação" })).not.toBeInTheDocument();   // o assistente ocupa a tela
  });

  it("Nova importação abre o assistente e Cancelar volta ao histórico", async () => {
    const { user } = setup();
    await user.click(await screen.findByRole("button", { name: "Nova importação" }));
    expect(await screen.findByRole("form", { name: "Escolher extrato" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(await screen.findByText("conta-corrente-1.ofx")).toBeInTheDocument();
  });

  it("pagina o histórico", async () => {
    listAccounts.mockResolvedValue([]);
    listImports.mockImplementation((page: number) => Promise.resolve(pageOf([lote({ id: `i${page}`, fileName: `arq${page}.ofx` })], { totalElements: 25, totalPages: 2, page })));
    const user = userEvent.setup();
    renderWithQuery(<ImportsView />);

    expect(await screen.findByText("arq0.ofx")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Próxima" }));
    expect(await screen.findByText("arq1.ofx")).toBeInTheDocument();
    expect(screen.getByText(/Página 2 de 2/)).toBeInTheDocument();
  });
});
