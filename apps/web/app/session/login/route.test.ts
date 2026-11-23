// @vitest-environment node
import { afterEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";

const call = (body: unknown, headers: Record<string, string> = {}) =>
  POST(
    new Request("http://app.local/session/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", host: "app.local", ...headers },
      body: JSON.stringify(body),
    }),
  );

afterEach(() => vi.restoreAllMocks());

describe("POST /session/login", () => {
  it("grava os tokens em cookies httpOnly e não os devolve no corpo", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ accessToken: "AT123", refreshToken: "RT456", expiresIn: 900 }), { status: 200 }),
        ),
      ),
    );

    const res = await call({ email: "a@b.c", password: "x" });

    expect(res.status).toBe(200);
    expect(await res.text()).not.toContain("AT123");
    const cookies = res.headers.getSetCookie().join("\n");
    expect(cookies).toMatch(/cf_at=AT123.*HttpOnly/i);
    expect(cookies).toMatch(/cf_rt=RT456.*HttpOnly/i);
    expect(cookies).toMatch(/SameSite=strict/i);
  });

  it("repassa o IP do cliente ao backend (rate limit por IP)", async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response("{}", { status: 401 })));
    vi.stubGlobal("fetch", fetchMock);

    await call({ email: "a@b.c", password: "x" }, { "x-forwarded-for": "198.51.100.7" });

    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(new Headers(init.headers).get("x-forwarded-for")).toBe("198.51.100.7");
  });

  it("repassa 401 e 429 (com Retry-After) sem criar cookies", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("{}", { status: 429, headers: { "Retry-After": "300" } }))),
    );

    const res = await call({ email: "a@b.c", password: "x" });

    expect(res.status).toBe(429);
    expect(res.headers.get("retry-after")).toBe("300");
    expect(res.headers.getSetCookie()).toHaveLength(0);
  });

  it("recusa Origin de outro site (CSRF)", async () => {
    const res = await call({ email: "a@b.c", password: "x" }, { origin: "http://evil.test" });
    expect(res.status).toBe(403);
  });

  it("recusa corpo inválido", async () => {
    expect((await call({ email: "a@b.c" })).status).toBe(400);
  });

  it("devolve 503 se o backend estiver fora do ar", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("ECONNREFUSED"))));
    expect((await call({ email: "a@b.c", password: "x" })).status).toBe(503);
  });
});
