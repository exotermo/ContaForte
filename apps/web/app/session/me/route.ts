import { NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";
import { ACCESS_COOKIE, readCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const token = readCookie(req, ACCESS_COOKIE);
  if (!token) return NextResponse.json({ error: "sem sessão" }, { status: 401, headers: { "Cache-Control": "no-store" } });

  let res: Response;
  try {
    res = await backendFetch("/api/v1/users/me", { token });
  } catch {
    return NextResponse.json({ error: "indisponível" }, { status: 503 });
  }
  if (!res.ok) return NextResponse.json({ error: "não autorizado" }, { status: res.status === 401 ? 401 : 502 });

  const me = await res.json();
  return NextResponse.json(me, { headers: { "Cache-Control": "no-store" } });
}
