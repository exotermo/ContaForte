import { afterEach, describe, expect, it, vi } from "vitest";
import { UnauthorizedError } from "./api";
import {
  ApiError,
  classifyImportItem,
  confirmImport,
  createAccount,
  createImport,
  createRule,
  decideClassification,
  deleteRule,
  deleteTransaction,
  discardImport,
  forgetRecipient,
  listAccounts,
  listImportItems,
  listRecipients,
  listRules,
  listTransactions,
  reclassify,
  reclassifyPreview,
  reviewCount,
  reviewQueue,
  rollbackImport,
  suggestMapping,
  testRule,
  updateRule,
} from "./finance";

const json = (status: number, body: unknown = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));

afterEach(() => vi.restoreAllMocks());

describe("cliente de dados (/bff)", () => {
  it("chama o proxy BFF e devolve o JSON", async () => {
    const fetchMock = vi.fn(() => json(200, [{ id: "1" }]));
    vi.stubGlobal("fetch", fetchMock);

    await expect(listAccounts()).resolves.toEqual([{ id: "1" }]);
    expect((fetchMock.mock.calls[0] as unknown as [string])[0]).toBe("/bff/accounts");
  });

  it("monta a consulta só com os filtros preenchidos", async () => {
    const fetchMock = vi.fn(() => json(200, { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    vi.stubGlobal("fetch", fetchMock);

    await listTransactions({ q: "mercado", type: "", accountId: undefined, from: "2026-05-01" }, 2, 10);

    const url = (fetchMock.mock.calls[0] as unknown as [string])[0];
    expect(url).toBe("/bff/transactions?q=mercado&from=2026-05-01&page=2&size=10");
  });

  it("envia JSON no POST e nunca coloca token nem Authorization no navegador", async () => {
    const fetchMock = vi.fn(() => json(201, { id: "9" }));
    vi.stubGlobal("fetch", fetchMock);

    await createAccount({ name: "Nubank", type: "CHECKING_ACCOUNT", openingBalance: "10.00" });

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ name: "Nubank", type: "CHECKING_ACCOUNT", openingBalance: "10.00" });
    expect(Object.keys(init.headers as object)).toEqual(["Content-Type"]);
  });

  it("renova a sessão uma vez e repete a chamada ao receber 401", async () => {
    let calls = 0;
    const fetchMock = vi.fn((url: string) => {
      if (url === "/session/refresh") return json(200);
      calls += 1;
      return calls === 1 ? json(401) : json(200, []);
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(listAccounts()).resolves.toEqual([]);
    expect(fetchMock.mock.calls.filter(([u]) => u === "/session/refresh")).toHaveLength(1);
  });

  it("lança UnauthorizedError se a renovação falhar", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(401)));
    await expect(listAccounts()).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it("usa a mensagem de negócio do backend (já em português)", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(409, { detail: "Já existe uma conta ativa com esse nome." })));
    await expect(createAccount({ name: "X", type: "CASH", openingBalance: "0.00" })).rejects.toMatchObject({
      status: 409,
      message: "Já existe uma conta ativa com esse nome.",
    });
  });

  it("troca mensagens do framework (inglês) e erros de servidor por texto em português", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(400, { detail: "Invalid request content." })));
    await expect(listAccounts()).rejects.toMatchObject({ message: "Confira os campos informados." });

    vi.stubGlobal("fetch", vi.fn(() => json(503, { error: "indisponível" })));
    await expect(listAccounts()).rejects.toMatchObject({ message: expect.stringContaining("indisponível") });

    vi.stubGlobal("fetch", vi.fn(() => json(403, {})));
    await expect(listAccounts()).rejects.toBeInstanceOf(ApiError);
  });

  it("DELETE com 204 não tenta ler corpo", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))));
    await expect(deleteTransaction("abc")).resolves.toBeUndefined();
  });

  // ---- importação de extratos

  it("envia o extrato como multipart com conta e mapeamento em JSON, sem Content-Type manual (o navegador põe o boundary)", async () => {
    const fetchMock = vi.fn(() => json(201, { id: "i1" }));
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["conteúdo"], "extrato.csv", { type: "text/csv" });

    await createImport({ file, accountId: "a1", mapping: { dateColumn: "#1", amountColumn: "#3", skipLines: "3" } });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/bff/imports");
    expect(init.method).toBe("POST");
    expect(init.headers).toBeUndefined();
    const form = init.body as FormData;
    expect(form).toBeInstanceOf(FormData);
    expect((form.get("file") as File).name).toBe("extrato.csv");
    expect(form.get("accountId")).toBe("a1");
    expect(JSON.parse(form.get("mapping") as string)).toEqual({ dateColumn: "#1", amountColumn: "#3", skipLines: "3" });
  });

  it("OFX vai sem mapeamento; a sugestão de colunas usa a rota própria", async () => {
    const fetchMock = vi.fn(() => json(200, {}));
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["x"], "a.ofx");

    await createImport({ file, accountId: "a1" });
    await suggestMapping(file);

    expect(((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body as FormData).has("mapping")).toBe(false);
    expect((fetchMock.mock.calls[1] as unknown as [string])[0]).toBe("/bff/imports/suggest-mapping");
  });

  it("monta as consultas e os corpos de linhas, confirmação, desfazer e descarte", async () => {
    const fetchMock = vi.fn(() => json(200, {}));
    vi.stubGlobal("fetch", fetchMock);

    await listImportItems("i1", { status: "POSSIBLE_DUPLICATE", page: 2 });
    await confirmImport("i1", { includePossible: ["p1"], exclude: ["n1", "n2"] });
    await rollbackImport("i1");
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))));
    await discardImport("i1");

    expect((fetchMock.mock.calls[0] as unknown as [string])[0]).toBe("/bff/imports/i1/items?status=POSSIBLE_DUPLICATE&page=2&size=50");
    const [curl, cinit] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(curl).toBe("/bff/imports/i1/confirm");
    expect(JSON.parse(cinit.body as string)).toEqual({ includePossible: ["p1"], exclude: ["n1", "n2"] });
    expect((fetchMock.mock.calls[2] as unknown as [string, RequestInit])[1].method).toBe("POST");
  });

  it("traduz arquivo grande (413) e limite de envios (429, com minutos) para português", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("<html>413</html>", { status: 413 }))));
    await expect(createImport({ file: new File(["x"], "a.ofx"), accountId: "a" })).rejects.toMatchObject({ status: 413, message: expect.stringContaining("5 MB") });

    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("{}", { status: 429, headers: { "Retry-After": "1500" } }))));
    await expect(createImport({ file: new File(["x"], "a.ofx"), accountId: "a" })).rejects.toMatchObject({ message: "Muitos envios em pouco tempo. Tente de novo em 25 min." });
  });

  it("usa a mensagem do backend para extrato recusado (422)", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(422, { detail: "Só extratos em reais (BRL) são aceitos." })));
    await expect(createImport({ file: new File(["x"], "a.ofx"), accountId: "a" })).rejects.toMatchObject({ status: 422, message: "Só extratos em reais (BRL) são aceitos." });
  });
});

describe("cliente da classificação (M4)", () => {
  it("troca a classificação de uma linha da prévia (PATCH) com nulo = nenhuma e reset", async () => {
    const fetchMock = vi.fn(() => json(200, {}));
    vi.stubGlobal("fetch", fetchMock);

    await classifyImportItem("i1", "n1", { categoryId: "c2" });
    await classifyImportItem("i1", "n1", { reset: true });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/bff/imports/i1/items/n1");
    expect(init.method).toBe("PATCH");
    expect(JSON.parse(init.body as string)).toEqual({ categoryId: "c2", contextId: null, reset: false });
    expect(JSON.parse((fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1].body as string)).toEqual({ categoryId: null, contextId: null, reset: true });
  });

  it("regras: lista, cria, edita com nulo que limpa, apaga e testa um texto", async () => {
    const fetchMock = vi.fn(() => json(200, {}));
    vi.stubGlobal("fetch", fetchMock);

    await listRules();
    await createRule({ matchType: "CONTAINS", pattern: "posto", categoryId: "c2" });
    await updateRule("r1", { transactionType: null, enabled: false });
    await testRule({ description: "PIX ENVIADO X", type: "EXPENSE" });
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))));
    await deleteRule("r1");

    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
    expect(calls[0][0]).toBe("/bff/categorization-rules");
    expect(calls[1][1].method).toBe("POST");
    expect(calls[2][0]).toBe("/bff/categorization-rules/r1");
    expect(JSON.parse(calls[2][1].body as string)).toEqual({ transactionType: null, enabled: false });   // nulo explícito vai no corpo
    expect(calls[3][0]).toBe("/bff/categorization-rules/test");
  });

  it("favorecidos: busca com q e paginação, e esquecer", async () => {
    const fetchMock = vi.fn(() => json(200, {}));
    vi.stubGlobal("fetch", fetchMock);
    await listRecipients("joa", 1);
    expect((fetchMock.mock.calls[0] as unknown as [string])[0]).toBe("/bff/pix-recipients?q=joa&page=1&size=20");
    await listRecipients("");
    expect((fetchMock.mock.calls[1] as unknown as [string])[0]).toBe("/bff/pix-recipients?page=0&size=20");   // q vazio não vai
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))));
    await expect(forgetRecipient("f1")).resolves.toBeUndefined();
  });

  it("revisão e reclassificação: fila, contadores, decisão e as duas fases", async () => {
    const fetchMock = vi.fn(() => json(200, {}));
    vi.stubGlobal("fetch", fetchMock);

    await reviewQueue(2);
    await reviewCount();
    await decideClassification("t1", { action: "CORRECT", categoryId: "c2", createRule: { matchType: "CONTAINS", pattern: "cafe" } });
    await reclassifyPreview();
    await reclassify({ expectedAuto: 2, expectedSuggest: 1 });

    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
    expect(calls[0][0]).toBe("/bff/classification/review?page=2&size=20");
    expect(calls[1][0]).toBe("/bff/classification/review/count");
    expect(calls[2][0]).toBe("/bff/transactions/t1/classification");
    expect(JSON.parse(calls[2][1].body as string)).toEqual({ action: "CORRECT", categoryId: "c2", createRule: { matchType: "CONTAINS", pattern: "cafe" } });
    expect(calls[3][0]).toBe("/bff/classification/reclassify/preview");
    expect(calls[3][1].method).toBe("POST");
    expect(JSON.parse(calls[4][1].body as string)).toEqual({ expectedAuto: 2, expectedSuggest: 1 });
  });

  it("409 da reclassificação traz a mensagem do backend", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(409, { detail: "O resultado mudou desde a prévia. Confira a prévia de novo antes de aplicar." })));
    await expect(reclassify({ expectedAuto: 1, expectedSuggest: 0 })).rejects.toMatchObject({ status: 409, message: expect.stringContaining("mudou desde a prévia") });
  });
});
