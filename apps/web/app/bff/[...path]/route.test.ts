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

  // ---- importação de extratos (upload multipart)

  const multipart = (bytes: Uint8Array, headers: Record<string, string> = {}) =>
    new Request("http://app.local/bff/imports", {
      method: "POST",
      headers: { host: "app.local", cookie: "cf_at=AT123", "content-type": "multipart/form-data; boundary=XYZ", ...headers },
      body: bytes as unknown as BodyInit,
    });

  it("repassa o upload de extrato com o Content-Type original (boundary) e os mesmos bytes", async () => {
    const fetchMock = backend(201, { id: "i1" });
    const payload = new TextEncoder().encode("--XYZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.ofx\"\r\n\r\nconteúdo\r\n--XYZ--\r\n");

    const res = await POST(multipart(payload), ctx(["imports"]));

    expect(res.status).toBe(201);
    expect((fetchMock.mock.calls[0] as unknown as [string])[0]).toBe("http://backend:8080/api/v1/users/me");
    const [url, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(url).toBe("http://backend:8080/api/v1/imports");
    expect(new Headers(init.headers).get("content-type")).toBe("multipart/form-data; boundary=XYZ");
    expect(new Headers(init.headers).get("authorization")).toBe("Bearer AT123");
    expect(init.body).toEqual(payload);
  });

  it("a sugestão de mapeamento também é upload", async () => {
    const fetchMock = backend(200, {});
    await POST(multipart(new Uint8Array([1, 2, 3])), ctx(["imports", "suggest-mapping"]));
    expect((fetchMock.mock.calls[1] as unknown as [string])[0]).toBe("http://backend:8080/api/v1/imports/suggest-mapping");
  });

  it("recusa upload que não é multipart (415) sem chamar o backend", async () => {
    const fetchMock = backend();
    const res = await POST(multipart(new Uint8Array([1]), { "content-type": "application/json" }), ctx(["imports"]));
    expect(res.status).toBe(415);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("recusa upload acima de 5,5 MB pelo Content-Length declarado, sem ler o corpo", async () => {
    const fetchMock = backend();
    const res = await POST(multipart(new Uint8Array(10), { "content-length": String(6 * 1024 * 1024) }), ctx(["imports"]));
    expect(res.status).toBe(413);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("corta o upload que passa do limite mesmo sem Content-Length (chunked ou mentira no cabeçalho)", async () => {
    const fetchMock = backend();
    const chunk = new Uint8Array(1024 * 1024);
    let sent = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent >= 8) return controller.close();
        sent += 1;
        controller.enqueue(chunk);
      },
    });
    const req = new Request("http://app.local/bff/imports", {
      method: "POST",
      headers: { host: "app.local", cookie: "cf_at=AT123", "content-type": "multipart/form-data; boundary=XYZ" },
      body: stream,
      duplex: "half",
    } as RequestInit);

    const res = await POST(req, ctx(["imports"]));

    expect(res.status).toBe(413);
    expect(sent).toBeLessThan(8);   // parou de ler ao passar do limite, não consumiu os 8 MB
    expect(fetchMock.mock.calls.map((c) => (c as unknown as [string])[0])).toEqual(["http://backend:8080/api/v1/users/me"]);
  });

  it("upload exige sessão e a mesma origem", async () => {
    const fetchMock = backend();
    expect((await POST(multipart(new Uint8Array([1]), { cookie: "" }), ctx(["imports"]))).status).toBe(401);
    expect((await POST(multipart(new Uint8Array([1]), { origin: "http://evil.example" }), ctx(["imports"]))).status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("com token recusado pelo backend, o arquivo nem chega a ser lido nem é repassado", async () => {
    const fetchMock = backend(401, {});
    const upload = multipart(new Uint8Array(1024), { cookie: "cf_at=inventado" });

    const res = await POST(upload, ctx(["imports"]));

    expect(res.status).toBe(401);
    expect(upload.bodyUsed).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("sem conseguir conferir o token, o upload é recusado com 503 sem ler o arquivo", async () => {
    for (const failure of [() => Promise.reject(new Error("ECONNREFUSED")), () => Promise.resolve(new Response("{}", { status: 500 }))]) {
      vi.stubGlobal("fetch", vi.fn(failure));
      const upload = multipart(new Uint8Array(16));
      expect((await POST(upload, ctx(["imports"]))).status).toBe(503);
      expect(upload.bodyUsed).toBe(false);
    }
  });

  it("uploads em paralelo são lidos e enviados um de cada vez", async () => {
    let inside = 0;
    let peak = 0;
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (url.endsWith("/users/me")) return new Response("{}", { status: 200 });
      peak = Math.max(peak, ++inside);
      await new Promise((r) => setTimeout(r, 5));
      inside -= 1;
      return new Response("{}", { status: 201 });
    }));

    const all = await Promise.all(Array.from({ length: 6 }, () => POST(multipart(new Uint8Array(32)), ctx(["imports"]))));

    expect(all.map((r) => r.status)).toEqual([201, 201, 201, 201, 201, 201]);
    expect(peak).toBe(1);
  });

  it("a confirmação aceita corpo JSON grande (listas de ids), os demais corpos continuam em 64 KB", async () => {
    const fetchMock = backend(200, {});
    const ids = JSON.stringify({ exclude: Array.from({ length: 5000 }, (_, i) => `00000000-0000-0000-0000-${String(i).padStart(12, "0")}`) });
    expect(ids.length).toBeGreaterThan(64 * 1024);

    const ok = await POST(req("POST", "/bff/imports/abc/confirm", { body: ids }), ctx(["imports", "abc", "confirm"]));
    expect(ok.status).toBe(200);
    expect((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body).toBe(ids);

    const big = await POST(req("POST", "/bff/imports/abc/confirm", { body: "x".repeat(1024 * 1024 + 1) }), ctx(["imports", "abc", "confirm"]));
    expect(big.status).toBe(413);
    const other = await POST(req("POST", "/bff/imports/abc/rollback", { body: "x".repeat(65 * 1024) }), ctx(["imports", "abc", "rollback"]));
    expect(other.status).toBe(413);
  });

  it("rotas de leitura, rollback e descarte de importação passam; outras raízes continuam bloqueadas", async () => {
    const fetchMock = backend(200, {});
    await GET(req("GET", "/bff/imports?page=0&size=20"), ctx(["imports"]));
    await GET(req("GET", "/bff/imports/abc/items?status=NEW&page=1"), ctx(["imports", "abc", "items"]));
    await POST(req("POST", "/bff/imports/abc/rollback"), ctx(["imports", "abc", "rollback"]));
    backend(204);
    expect((await DELETE(req("DELETE", "/bff/imports/abc"), ctx(["imports", "abc"]))).status).toBe(204);
    expect((fetchMock.mock.calls[0] as unknown as [string])[0]).toBe("http://backend:8080/api/v1/imports?page=0&size=20");
    expect((fetchMock.mock.calls[1] as unknown as [string])[0]).toBe("http://backend:8080/api/v1/imports/abc/items?status=NEW&page=1");
    expect((await GET(req("GET", "/bff/x"), ctx(["admin", "imports"]))).status).toBe(404);
    expect((await GET(req("GET", "/bff/x"), ctx(["imports", ".."]))).status).toBe(404);
  });

  it("alcança as rotas de classificação (regras, favorecidos, revisão) e repassa PATCH e DELETE", async () => {
    const fetchMock = backend(200, {});

    await GET(req("GET", "/bff/categorization-rules"), ctx(["categorization-rules"]));
    await POST(req("POST", "/bff/categorization-rules/test", { body: '{"description":"x","type":"EXPENSE"}' }), ctx(["categorization-rules", "test"]));
    await GET(req("GET", "/bff/pix-recipients?q=joa"), ctx(["pix-recipients"]));
    await DELETE(req("DELETE", "/bff/pix-recipients/f1"), ctx(["pix-recipients", "f1"]));
    await GET(req("GET", "/bff/classification/review/count"), ctx(["classification", "review", "count"]));
    await POST(req("POST", "/bff/transactions/t1/classification", { body: '{"action":"CONFIRM"}' }), ctx(["transactions", "t1", "classification"]));
    await PATCH(req("PATCH", "/bff/imports/i1/items/n1", { body: '{"categoryId":null}' }), ctx(["imports", "i1", "items", "n1"]));

    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
    expect(calls.map((c) => c[0].replace("http://backend:8080/api/v1", ""))).toEqual([
      "/categorization-rules",
      "/categorization-rules/test",
      "/pix-recipients?q=joa",
      "/pix-recipients/f1",
      "/classification/review/count",
      "/transactions/t1/classification",
      "/imports/i1/items/n1",
    ]);
    expect(calls[3][1].method).toBe("DELETE");
    expect(calls[6][1].method).toBe("PATCH");
    expect(calls[6][1].body).toBe('{"categoryId":null}');
  });

  it("as rotas de classificação também exigem sessão e origem confiável nas escritas", async () => {
    const fetchMock = backend();

    expect((await GET(req("GET", "/bff/pix-recipients", { cookie: null }), ctx(["pix-recipients"]))).status).toBe(401);
    expect((await POST(req("POST", "/bff/classification/reclassify", { body: "{}", headers: { origin: "http://evil.example" } }), ctx(["classification", "reclassify"]))).status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
