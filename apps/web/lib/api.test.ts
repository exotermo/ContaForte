import { afterEach, describe, expect, it, vi } from "vitest";
import { UnauthorizedError, fetchMe } from "./api";

const json = (status: number, body: unknown = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));

afterEach(() => vi.restoreAllMocks());

describe("fetchMe", () => {
  it("devolve o perfil quando a sessão é válida", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(200, { email: "a@b.c" })));
    await expect(fetchMe()).resolves.toMatchObject({ email: "a@b.c" });
  });

  it("renova a sessão uma vez e repete a chamada ao receber 401", async () => {
    let meCalls = 0;
    const fetchMock = vi.fn((url: string) => {
      if (url === "/session/refresh") return json(200);
      meCalls += 1;
      return meCalls === 1 ? json(401) : json(200, { email: "a@b.c" });
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchMe()).resolves.toMatchObject({ email: "a@b.c" });
    expect(fetchMock.mock.calls.filter(([u]) => u === "/session/refresh")).toHaveLength(1);
  });

  it("chamadas simultâneas compartilham um único refresh (rotação do backend não tolera reuso)", async () => {
    let meCalls = 0;
    const fetchMock = vi.fn((url: string) => {
      if (url === "/session/refresh") return new Promise<Response>((r) => setTimeout(() => r(new Response("{}", { status: 200 })), 20));
      meCalls += 1;
      return meCalls <= 3 ? json(401) : json(200, { email: "a@b.c" });
    });
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([fetchMe(), fetchMe(), fetchMe()]);
    expect(fetchMock.mock.calls.filter(([u]) => u === "/session/refresh")).toHaveLength(1);
  });

  it("lança UnauthorizedError se o refresh também falha", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(401)));
    await expect(fetchMe()).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it("não confunde erro de servidor com sessão inválida", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(502)));
    await expect(fetchMe()).rejects.not.toBeInstanceOf(UnauthorizedError);
  });
});
