// Constantes e utilitários da sessão via cookies httpOnly (padrão BFF: o JS do navegador nunca vê os tokens).

export const ACCESS_COOKIE = "cf_at";
export const REFRESH_COOKIE = "cf_rt";
export const REFRESH_MAX_AGE = 14 * 24 * 60 * 60; // igual ao refreshTtl padrão do backend (14 dias)

export type CookieOptions = {
  httpOnly: true;
  secure: boolean;
  sameSite: "strict";
  path: string;
  maxAge: number;
};

export function cookieOptions(req: Request, maxAge: number): CookieOptions {
  // O TLS termina no Cloudflare/gateway: o esquema original chega em X-Forwarded-Proto.
  const secure = req.headers.get("x-forwarded-proto") === "https" || new URL(req.url).protocol === "https:";
  return { httpOnly: true, secure, sameSite: "strict", path: "/", maxAge };
}

/** Defesa extra contra CSRF: se o navegador enviou Origin, ele precisa ser o mesmo host da requisição. */
export function isSameOrigin(req: Request): boolean {
  const origin = req.headers.get("origin");
  if (!origin) return true; // clientes não-navegador (curl, testes) não enviam Origin
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

/** IP do cliente conforme o gateway (que sobrescreve X-Forwarded-For). Repassado ao backend para o rate limit. */
export function clientIp(req: Request): string {
  const xff = req.headers.get("x-forwarded-for");
  return xff?.split(",")[0]?.trim() || "0.0.0.0";
}

export function readCookie(req: Request, name: string): string | undefined {
  const header = req.headers.get("cookie");
  if (!header) return undefined;
  for (const part of header.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return decodeURIComponent(v.join("="));
  }
  return undefined;
}
