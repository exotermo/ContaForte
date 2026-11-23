// @vitest-environment node
import { afterEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, PATCH, POST } from "./route";

const ctx = (path: string[]) => ({ params: Promise.resolve({ path }) });
const req = (method: string, url: string, init: { cookie?: string | null; headers?: Record<string, string>; body?: string } = {}) =>
  new Request(`http://app.local${url}`, {
    method,
    headers: {
      host: "app.local",
      ...(init.cookie === null ? {} : { cookie: init.cookie ?? "cf_at=AT123" }),
      ...(init.body ? { "Content-Type": "application/json" } : {}),
      ...init.headers,
    },
    body: init.body,
  });

const backend = (status = 200, body: unknown = {}) => {
  const fetchMock = vi.fn(() => Promise.resolve(new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

afterEach(() => vi.restoreAllMocks());

describe("proxy de dados /bff", () => {
  it("injeta o token do cookie e repassa o caminho e a consulta ao backend", async () => {
    const fetchMock = backend(200, [{ id: "1" }]);

    const res = await GET(req("GET", "/bff/transactions?page=1&size=20"), ctx(["transactions"]));

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([{ id: "1" }]);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://backend:8080/api/v1/transactions?page=1&size=20");
    expect(new Headers(init.headers).get("authorization")).toBe("Bearer AT123");
    expect(res.headers.get("cache-control")).toBe("no-store");
  });

  it("sem cookie de sessão responde 401 e não chama o backend", async () => {
    const fetchMock = backend();
    const res = await GET(req("GET", "/bff/accounts", { cookie: null }), ctx(["accounts"]));
    expect(res.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("só alcança as rotas financeiras: nada de auth, admin, openapi ou system", async () => {
    const fetchMock = backend();
    for (const path of [["auth", "login"], ["admin", "users"], ["users", "me"], ["openapi"], ["system", "info"], []]) {
      const res = await GET(req("GET", "/bff/x"), ctx(path));
      expect(res.status, path.join("/")).toBe(404);
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("recusa travessia de caminho e segmentos estranhos", async () => {
    const fetchMock = backend();
    for (const path of [["accounts", ".."], ["accounts", "..", "auth"], ["accounts", "a/b"], ["accounts", "a%2Fb"], ["accounts", "x y"], ["accounts", "é"], ["accounts", "a".repeat(65)], ["accounts", ""]]) {
      const res = await GET(req("GET", "/bff/x"), ctx(path));
      expect(res.status, JSON.stringify(path)).toBe(404);
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("aceita UUID de recurso", async () => {
    const fetchMock = backend();
    await GET(req("GET", "/bff/accounts/3ec464ea-9404-4f04-8e1b-b274c061e289"), ctx(["accounts", "3ec464ea-9404-4f04-8e1b-b274c061e289"]));
    expect((fetchMock.mock.calls[0] as unknown as [string])[0]).toContain("/api/v1/accounts/3ec464ea-9404-4f04-8e1b-b274c061e289");
  });

  it("repassa o corpo do POST e o IP do cliente", async () => {
    const fetchMock = backend(201, { id: "9" });

    const res = await POST(
      req("POST", "/bff/accounts", { body: '{"name":"Nubank"}', headers: { "x-forwarded-for": "198.51.100.7" } }),
      ctx(["accounts"]),
    );

    expect(res.status).toBe(201);
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(init.body).toBe('{"name":"Nubank"}');
    expect(new Headers(init.headers).get("x-forwarded-for")).toBe("198.51.100.7");
  });

  it("recusa Origin de outro site em POST, PATCH e DELETE (CSRF) sem chamar o backend", async () => {
    const fetchMock = backend();
    const evil = { origin: "http://evil.example" };
    expect((await POST(req("POST", "/bff/accounts", { body: "{}", headers: evil }), ctx(["accounts"]))).status).toBe(403);
    expect((await PATCH(req("PATCH", "/bff/accounts/a1", { body: "{}", headers: evil }), ctx(["accounts", "a1"]))).status).toBe(403);
    expect((await DELETE(req("DELETE", "/bff/transactions/t1", { headers: evil }), ctx(["transactions", "t1"]))).status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("aceita o Origin do próprio site", async () => {
    backend(201, {});
    const res = await POST(req("POST", "/bff/accounts", { body: "{}", headers: { origin: "http://app.local" } }), ctx(["accounts"]));
    expect(res.status).toBe(201);
  });

  it("DELETE devolve 204 sem corpo", async () => {
    backend(204);
    const res = await DELETE(req("DELETE", "/bff/transactions/t1"), ctx(["transactions", "t1"]));
    expect(res.status).toBe(204);
    expect(await res.text()).toBe("");
  });

  it("repassa 401, 404, 409 e 422 do backend com o corpo de erro", async () => {
    for (const status of [401, 404, 409, 422]) {
      backend(status, { detail: `erro ${status}` });
      const res = await GET(req("GET", "/bff/accounts/a1"), ctx(["accounts", "a1"]));
      expect(res.status).toBe(status);
      expect((await res.json()).detail).toBe(`erro ${status}`);
    }
  });

  it("repassa Retry-After do 429", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("{}", { status: 429, headers: { "Retry-After": "30" } }))));
    const res = await GET(req("GET", "/bff/accounts"), ctx(["accounts"]));
    expect(res.status).toBe(429);
    expect(res.headers.get("retry-after")).toBe("30");
  });

  it("backend fora do ar vira 503", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("ECONNREFUSED"))));
    expect((await GET(req("GET", "/bff/accounts"), ctx(["accounts"]))).status).toBe(503);
  });

  it("limita o tamanho do corpo e da consulta", async () => {
    const fetchMock = backend();
    const big = await POST(req("POST", "/bff/accounts", { body: "x".repeat(65 * 1024) }), ctx(["accounts"]));
    expect(big.status).toBe(413);
    const longQuery = await GET(req("GET", `/bff/transactions?q=${"a".repeat(2100)}`), ctx(["transactions"]));
    expect(longQuery.status).toBe(414);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
