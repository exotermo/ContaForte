import { afterEach, describe, expect, it, vi } from "vitest";
import { UnauthorizedError } from "./api";
import { ApiError, createAccount, deleteTransaction, listAccounts, listTransactions } from "./finance";

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
});
