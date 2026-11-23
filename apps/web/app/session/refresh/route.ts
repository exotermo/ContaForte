import { NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  REFRESH_MAX_AGE,
  clientIp,
  cookieOptions,
  isSameOrigin,
  readCookie,
} from "@/lib/session";

export const dynamic = "force-dynamic";

type Tokens = { accessToken: string; refreshToken: string; expiresIn: number };

function clear(out: NextResponse, req: Request) {
  out.cookies.set(ACCESS_COOKIE, "", cookieOptions(req, 0));
  out.cookies.set(REFRESH_COOKIE, "", cookieOptions(req, 0));
  return out;
}

export async function POST(req: Request) {
  if (!isSameOrigin(req)) return NextResponse.json({ error: "origem inválida" }, { status: 403 });

  const refreshToken = readCookie(req, REFRESH_COOKIE);
  if (!refreshToken) return NextResponse.json({ error: "sem sessão" }, { status: 401 });

  let res: Response;
  try {
    res = await backendFetch("/api/v1/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      ip: clientIp(req),
    });
  } catch {
    return NextResponse.json({ error: "indisponível" }, { status: 503 });
  }

  // refresh recusado (expirado, revogado ou reusado): encerra a sessão local
  if (res.status === 401) return clear(NextResponse.json({ error: "sessão expirada" }, { status: 401 }), req);
  if (!res.ok) return NextResponse.json({ error: "falha" }, { status: 502 });

  const t = (await res.json()) as Tokens;
  const out = NextResponse.json({ ok: true });
  out.cookies.set(ACCESS_COOKIE, t.accessToken, cookieOptions(req, t.expiresIn));
  out.cookies.set(REFRESH_COOKIE, t.refreshToken, cookieOptions(req, REFRESH_MAX_AGE));
  out.headers.set("Cache-Control", "no-store");
  return out;
}
