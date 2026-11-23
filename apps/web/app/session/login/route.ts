import { NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";
import { ACCESS_COOKIE, REFRESH_COOKIE, REFRESH_MAX_AGE, clientIp, cookieOptions, isSameOrigin } from "@/lib/session";

export const dynamic = "force-dynamic";

type Tokens = { accessToken: string; refreshToken: string; expiresIn: number };

export async function POST(req: Request) {
  if (!isSameOrigin(req)) return NextResponse.json({ error: "origem inválida" }, { status: 403 });

  const body = await req.json().catch(() => null);
  const email = typeof body?.email === "string" ? body.email : "";
  const password = typeof body?.password === "string" ? body.password : "";
  if (!email || !password) return NextResponse.json({ error: "dados inválidos" }, { status: 400 });

  let res: Response;
  try {
    res = await backendFetch("/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
      ip: clientIp(req),
    });
  } catch {
    return NextResponse.json({ error: "indisponível" }, { status: 503 });
  }

  if (!res.ok) {
    const out = NextResponse.json({ error: res.status === 401 ? "credenciais" : "falha" }, { status: res.status });
    const retry = res.headers.get("retry-after");
    if (retry) out.headers.set("Retry-After", retry);
    return out;
  }

  const t = (await res.json()) as Tokens;
  const out = NextResponse.json({ ok: true });
  out.cookies.set(ACCESS_COOKIE, t.accessToken, cookieOptions(req, t.expiresIn));
  out.cookies.set(REFRESH_COOKIE, t.refreshToken, cookieOptions(req, REFRESH_MAX_AGE));
  out.headers.set("Cache-Control", "no-store");
  return out;
}
