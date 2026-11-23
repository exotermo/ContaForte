import { NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";
import { ACCESS_COOKIE, clientIp, isSameOrigin, readCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

/**
 * Proxy de dados (BFF): injeta o access token do cookie httpOnly e repassa ao backend. O navegador nunca vê o token.
 * Só as rotas financeiras são alcançáveis (nada de /auth ou /admin) e cada segmento do caminho é validado, então o
 * proxy não serve para chegar em outros endereços do backend.
 */
const ALLOWED_ROOTS = new Set(["accounts", "categories", "contexts", "transactions", "summary"]);
const SEGMENT = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_BODY = 64 * 1024;
const MAX_QUERY = 2000;
const NO_STORE = { "Cache-Control": "no-store" };

type Ctx = { params: Promise<{ path: string[] }> };

async function handle(req: Request, ctx: Ctx): Promise<Response> {
  const { path } = await ctx.params;
  if (!path?.length || !ALLOWED_ROOTS.has(path[0]) || !path.every((s) => SEGMENT.test(s))) {
    return NextResponse.json({ error: "não encontrado" }, { status: 404, headers: NO_STORE });
  }
  const mutating = req.method !== "GET";
  if (mutating && !isSameOrigin(req)) {
    return NextResponse.json({ error: "origem inválida" }, { status: 403, headers: NO_STORE });
  }
  const token = readCookie(req, ACCESS_COOKIE);
  if (!token) return NextResponse.json({ error: "sem sessão" }, { status: 401, headers: NO_STORE });

  const search = new URL(req.url).search;
  if (search.length > MAX_QUERY) return NextResponse.json({ error: "consulta longa demais" }, { status: 414, headers: NO_STORE });

  let body: string | undefined;
  if (req.method === "POST" || req.method === "PATCH") {
    body = await req.text();
    if (body.length > MAX_BODY) return NextResponse.json({ error: "corpo grande demais" }, { status: 413, headers: NO_STORE });
  }

  let res: Response;
  try {
    res = await backendFetch(`/api/v1/${path.join("/")}${search}`, {
      method: req.method,
      token,
      ip: clientIp(req),
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body,
    });
  } catch {
    return NextResponse.json({ error: "indisponível" }, { status: 503, headers: NO_STORE });
  }

  const headers = new Headers(NO_STORE);
  const retryAfter = res.headers.get("retry-after");
  if (retryAfter) headers.set("Retry-After", retryAfter);
  if (res.status === 204) return new NextResponse(null, { status: 204, headers });
  headers.set("Content-Type", res.headers.get("content-type") ?? "application/json");
  return new NextResponse(await res.text(), { status: res.status, headers });
}

export const GET = handle;
export const POST = handle;
export const PATCH = handle;
export const DELETE = handle;
