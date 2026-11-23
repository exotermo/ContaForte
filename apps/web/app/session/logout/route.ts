import { NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";
import { ACCESS_COOKIE, REFRESH_COOKIE, clientIp, cookieOptions, isSameOrigin, readCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  if (!isSameOrigin(req)) return NextResponse.json({ error: "origem inválida" }, { status: 403 });

  const refreshToken = readCookie(req, REFRESH_COOKIE);
  if (refreshToken) {
    // melhor esforço: mesmo que o backend falhe, os cookies locais são apagados
    await backendFetch("/api/v1/auth/logout", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      ip: clientIp(req),
    }).catch(() => undefined);
  }
  const out = new NextResponse(null, { status: 204 });
  out.cookies.set(ACCESS_COOKIE, "", cookieOptions(req, 0));
  out.cookies.set(REFRESH_COOKIE, "", cookieOptions(req, 0));
  return out;
}
