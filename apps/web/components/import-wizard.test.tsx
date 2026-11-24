import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/finance";
import { account, batch, item, pageOf, renderWithQuery, suggestionA } from "./test-utils";
import { ImportWizard } from "./import-wizard";

const listAccounts = vi.fn();
const suggestMapping = vi.fn();
const createImport = vi.fn();
const getImport = vi.fn();
const listImportItems = vi.fn();
const confirmImport = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({
  ...(await orig<typeof import("@/lib/finance")>()),
  listAccounts: (...a: unknown[]) => listAccounts(...a),
  suggestMapping: (...a: unknown[]) => suggestMapping(...a),
  createImport: (...a: unknown[]) => createImport(...a),
  getImport: (...a: unknown[]) => getImport(...a),
  listImportItems: (...a: unknown[]) => listImportItems(...a),
  confirmImport: (...a: unknown[]) => confirmImport(...a),
}));

afterEach(() => [listAccounts, suggestMapping, createImport, getImport, listImportItems, confirmImport].forEach((m) => m.mockReset()));

const OFX = "OFXHEADER:100\nDATA:OFXSGML\n\n<OFX><STMTTRN>";
const contas = [account({ id: "a1", name: "Corrente" }), account({ id: "a2", name: "Poupança", type: "SAVINGS_ACCOUNT" })];

function setup(props: Partial<React.ComponentProps<typeof ImportWizard>> = {}) {
  listAccounts.mockResolvedValue(contas);
  listImportItems.mockResolvedValue(pageOf([item()]));
  const onClose = vi.fn();
  const view = renderWithQuery(<ImportWizard onClose={onClose} {...props} />);
  return { ...view, onClose, user: userEvent.setup() };
}
const arquivo = (conteudo: string, nome: string) => new File([conteudo], nome);

describe("ImportWizard", () => {
  it("OFX vai direto para a prévia, sem passo de colunas, com a conta escolhida", async () => {
    createImport.mockResolvedValue(batch({ fileName: "extrato.ofx" }));
    const { user } = setup();
    await screen.findByRole("option", { name: "Poupança" });

    await user.selectOptions(screen.getByLabelText("Conta que recebe o extrato"), "a2");
    await user.upload(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)"), arquivo(OFX, "extrato.ofx"));
    await user.click(screen.getByRole("button", { name: "Continuar" }));

    await waitFor(() => expect(createImport).toHaveBeenCalledTimes(1));
    const args = createImport.mock.calls[0][0];
    expect(args.accountId).toBe("a2");
    expect(args.file.name).toBe("extrato.ofx");
    expect(args.mapping).toBeUndefined();
    expect(suggestMapping).not.toHaveBeenCalled();
    expect(await screen.findByText("Revise antes de importar")).toBeInTheDocument();
    expect(screen.getByText(/Nada foi criado ainda/)).toBeInTheDocument();
  });

  it("CSV passa pelo mapeamento de colunas e envia o mapeamento escolhido", async () => {
    suggestMapping.mockResolvedValue(suggestionA());
    createImport.mockResolvedValue(batch({ format: "CSV", fileName: "banco-a.csv" }));
    const { user } = setup();
    await screen.findByRole("option", { name: "Corrente" });

    await user.upload(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)"), arquivo("Data;Histórico;Valor\n02/03/2026;x;-1,00\n", "banco-a.csv"));
    await user.click(screen.getByRole("button", { name: "Continuar" }));

    expect(await screen.findByText("Como ler este CSV")).toBeInTheDocument();
    expect(createImport).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    await waitFor(() => expect(createImport).toHaveBeenCalledTimes(1));
    expect(createImport.mock.calls[0][0].mapping).toMatchObject({ dateColumn: "#1", amountColumn: "#4", delimiter: ";" });
    expect(await screen.findByText("Revise antes de importar")).toBeInTheDocument();
  });

  it("Voltar do passo de colunas retorna à escolha do arquivo", async () => {
    suggestMapping.mockResolvedValue(suggestionA());
    const { user } = setup();
    await screen.findByRole("option", { name: "Corrente" });
    await user.upload(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)"), arquivo("a;b\n1;2\n", "x.csv"));
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await screen.findByText("Como ler este CSV");

    await user.click(screen.getByRole("button", { name: "Voltar" }));

    expect(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)")).toBeInTheDocument();
  });

  it("recusa no cliente arquivo acima de 5 MB ou vazio, sem chamar o servidor", async () => {
    const { user } = setup();
    await screen.findByRole("option", { name: "Corrente" });
    const campo = screen.getByLabelText("Arquivo do extrato (OFX ou CSV)");

    const grande = new File(["x"], "grande.ofx");
    Object.defineProperty(grande, "size", { value: 5 * 1024 * 1024 + 1 });
    await user.upload(campo, grande);
    expect(screen.getByRole("alert")).toHaveTextContent("passa de 5 MB");
    expect(screen.getByRole("button", { name: "Continuar" })).toBeDisabled();

    await user.upload(campo, new File([], "vazio.ofx"));
    expect(screen.getByRole("alert")).toHaveTextContent("vazio");
    expect(createImport).not.toHaveBeenCalled();
    expect(suggestMapping).not.toHaveBeenCalled();
  });

  it("sem arquivo escolhido o botão fica desabilitado", async () => {
    setup();
    await screen.findByRole("option", { name: "Corrente" });
    expect(screen.getByRole("button", { name: "Continuar" })).toBeDisabled();
  });

  it("mostra a mensagem do servidor quando o extrato é recusado e deixa tentar outro", async () => {
    createImport.mockRejectedValue(new ApiError(422, "Só extratos em reais (BRL) são aceitos."));
    const { user } = setup();
    await screen.findByRole("option", { name: "Corrente" });
    await user.upload(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)"), arquivo(OFX, "usd.ofx"));
    await user.click(screen.getByRole("button", { name: "Continuar" }));

    expect(await screen.findByText("Só extratos em reais (BRL) são aceitos.")).toBeInTheDocument();
    expect(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)")).toBeInTheDocument();   // continua no passo do arquivo
    expect(screen.getByRole("button", { name: "Continuar" })).toBeEnabled();
  });

  it("mostra o erro do limite de envios por hora", async () => {
    createImport.mockRejectedValue(new ApiError(429, "Muitos envios em pouco tempo. Tente de novo em 25 min."));
    const { user } = setup();
    await screen.findByRole("option", { name: "Corrente" });
    await user.upload(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)"), arquivo(OFX, "a.ofx"));
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByText(/Tente de novo em 25 min/)).toBeInTheDocument();
  });

  it("sem contas ativas orienta a cadastrar uma conta", async () => {
    listAccounts.mockResolvedValue([]);
    renderWithQuery(<ImportWizard onClose={vi.fn()} />);
    expect(await screen.findByText(/Cadastre uma conta antes de importar/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ir para Contas" })).toHaveAttribute("href", "/contas");
  });

  it("do arquivo à conclusão: confirma e oferece ver as transações da importação", async () => {
    createImport.mockResolvedValue(batch({ id: "i9" }));
    confirmImport.mockResolvedValue(batch({ id: "i9", status: "CONFIRMED", importedRows: 12, skippedByConflict: 2 }));
    const { user } = setup();
    await screen.findByRole("option", { name: "Corrente" });
    await user.upload(screen.getByLabelText("Arquivo do extrato (OFX ou CSV)"), arquivo(OFX, "a.ofx"));
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await user.click(await screen.findByRole("button", { name: "Importar 12 transações" }));

    expect(await screen.findByRole("heading", { name: "Importação concluída" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("12 transações importadas. 2 já existiam e foram puladas.");
    expect(screen.getByRole("link", { name: "Ver as transações" })).toHaveAttribute("href", "/transacoes?importId=i9");
  });

  it("reabre uma prévia pendente direto na revisão", async () => {
    getImport.mockResolvedValue(batch({ id: "i7", fileName: "pendente.ofx", totalRows: 3, newRows: 3 }));
    setup({ resumeId: "i7" });

    expect(await screen.findByText("Revise antes de importar")).toBeInTheDocument();
    expect(screen.getByText("pendente.ofx")).toBeInTheDocument();
    expect(getImport).toHaveBeenCalledWith("i7");
    expect(screen.queryByLabelText("Arquivo do extrato (OFX ou CSV)")).not.toBeInTheDocument();
  });

  it("prévia que venceu mostra aviso em vez de ficar carregando", async () => {
    getImport.mockRejectedValue(new ApiError(404, "Importação não encontrada."));
    setup({ resumeId: "i7" });
    expect(await screen.findByText(/Ela pode ter vencido/)).toBeInTheDocument();
  });

  it("Cancelar fecha o assistente", async () => {
    const { user, onClose } = setup();
    await screen.findByRole("option", { name: "Corrente" });
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("marca a etapa atual na trilha de passos", async () => {
    setup();
    await screen.findByRole("option", { name: "Corrente" });
    expect(screen.getByText("1. Arquivo")).toHaveAttribute("aria-current", "step");
    expect(screen.getByText("3. Revisão")).not.toHaveAttribute("aria-current");
  });
});
