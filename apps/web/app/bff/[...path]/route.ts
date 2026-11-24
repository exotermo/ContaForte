import { NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";
import { ACCESS_COOKIE, clientIp, isSameOrigin, readCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

/**
 * Proxy de dados (BFF): injeta o access token do cookie httpOnly e repassa ao backend. O navegador nunca vê o token.
 * Só as rotas financeiras e de importação são alcançáveis (nada de /auth ou /admin) e cada segmento do caminho é
 * validado, então o proxy não serve para chegar em outros endereços do backend.
 */
const ALLOWED_ROOTS = new Set(["accounts", "categories", "contexts", "transactions", "summary", "imports"]);
const SEGMENT = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_JSON = 64 * 1024;
/** A confirmação de uma importação leva listas de ids de linhas (até milhares): limite maior que o dos demais corpos JSON. */
const MAX_CONFIRM = 1024 * 1024;
/** Extrato de até 5 MB (o backend impõe o limite exato) mais o envelope do multipart. */
const MAX_UPLOAD = 5.5 * 1024 * 1024;
const MAX_QUERY = 2000;
const NO_STORE = { "Cache-Control": "no-store" };

type Ctx = { params: Promise<{ path: string[] }> };

const isUpload = (method: string, path: string[]) =>
  method === "POST" && path[0] === "imports" && (path.length === 1 || (path.length === 2 && path[1] === "suggest-mapping"));
const isConfirm = (method: string, path: string[]) => method === "POST" && path[0] === "imports" && path.length === 3 && path[2] === "confirm";

const tooBig = () => NextResponse.json({ error: "corpo grande demais" }, { status: 413, headers: NO_STORE });

/**
 * Lê o corpo em pedaços e para ao passar do limite, sem nunca guardar mais que `max` bytes. O Content-Length declarado
 * só serve de recusa antecipada: quem manda chunked ou mente no cabeçalho também é cortado aqui.
 */
async function readLimited(req: Request, max: number): Promise<Uint8Array | null> {
  const declared = Number(req.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > max) return null;
  if (!req.body) return new Uint8Array(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.byteLength;
  }
  return out;
}

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

  let body: Uint8Array | string | undefined;
  let contentType = "application/json";
  if (isUpload(req.method, path)) {
    // upload de extrato: só multipart, repassado com o Content-Type original (que traz o boundary)
    const ct = req.headers.get("content-type") ?? "";
    if (!ct.toLowerCase().startsWith("multipart/form-data")) {
      return NextResponse.json({ error: "esperado multipart/form-data" }, { status: 415, headers: NO_STORE });
    }
    const bytes = await readLimited(req, MAX_UPLOAD);
    if (!bytes) return tooBig();
    body = bytes;
    contentType = ct;
  } else if (req.method === "POST" || req.method === "PATCH") {
    const bytes = await readLimited(req, isConfirm(req.method, path) ? MAX_CONFIRM : MAX_JSON);
    if (!bytes) return tooBig();
    body = bytes.byteLength === 0 ? undefined : new TextDecoder().decode(bytes);
  }

  let res: Response;
  try {
    res = await backendFetch(`/api/v1/${path.join("/")}${search}`, {
      method: req.method,
      token,
      ip: clientIp(req),
      headers: body === undefined ? undefined : { "Content-Type": contentType },
      body: body as BodyInit | undefined,
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
