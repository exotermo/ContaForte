import { describe, expect, it } from "vitest";
import { clientIp, cookieOptions, isSameOrigin, readCookie } from "./session";

const req = (url: string, headers: Record<string, string> = {}) => new Request(url, { headers });

describe("isSameOrigin", () => {
  it("aceita requisição sem Origin (curl, testes)", () => {
    expect(isSameOrigin(req("http://app.local/session/login"))).toBe(true);
  });
  it("aceita Origin igual ao Host", () => {
    expect(isSameOrigin(req("http://app.local/x", { origin: "http://app.local", host: "app.local" }))).toBe(true);
  });
  it("rejeita Origin de outro site", () => {
    expect(isSameOrigin(req("http://app.local/x", { origin: "http://evil.test", host: "app.local" }))).toBe(false);
  });
  it("rejeita Origin malformado", () => {
    expect(isSameOrigin(req("http://app.local/x", { origin: "lixo", host: "app.local" }))).toBe(false);
  });
  it("usa X-Forwarded-Host quando presente", () => {
    const r = req("http://web:3000/x", { origin: "https://app.exemplo.com", "x-forwarded-host": "app.exemplo.com" });
    expect(isSameOrigin(r)).toBe(true);
  });
});

describe("cookieOptions", () => {
  it("é httpOnly e SameSite=Strict", () => {
    const o = cookieOptions(req("http://x/"), 900);
    expect(o).toMatchObject({ httpOnly: true, sameSite: "strict", path: "/", maxAge: 900 });
  });
  it("só marca Secure quando o esquema original é https", () => {
    expect(cookieOptions(req("http://x/"), 1).secure).toBe(false);
    expect(cookieOptions(req("http://x/", { "x-forwarded-proto": "https" }), 1).secure).toBe(true);
  });
});

describe("clientIp / readCookie", () => {
  it("usa o primeiro IP do X-Forwarded-For", () => {
    expect(clientIp(req("http://x/", { "x-forwarded-for": "203.0.113.9, 10.0.0.1" }))).toBe("203.0.113.9");
    expect(clientIp(req("http://x/"))).toBe("0.0.0.0");
  });
  it("lê cookies por nome", () => {
    const r = req("http://x/", { cookie: "a=1; cf_at=abc%3D; b=2" });
    expect(readCookie(r, "cf_at")).toBe("abc=");
    expect(readCookie(r, "nada")).toBeUndefined();
  });
});
