import { NextResponse, type NextRequest } from "next/server";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "@/lib/session";

/**
 * Guarda de navegação: sem cookie de sessão, qualquer página protegida redireciona para /login.
 * A validade real do token é conferida pelo backend; aqui é só experiência de navegação.
 */
export function proxy(req: NextRequest) {
  const hasSession = req.cookies.has(ACCESS_COOKIE) || req.cookies.has(REFRESH_COOKIE);
  const { pathname } = req.nextUrl;

  if (pathname === "/login") {
    return hasSession ? NextResponse.redirect(new URL("/", req.url)) : NextResponse.next();
  }
  if (!hasSession) return NextResponse.redirect(new URL("/login", req.url));
  return NextResponse.next();
}

export const config = {
  // exclui assets, PWA e as rotas de sessão/saúde (que fazem a própria checagem)
  matcher: ["/((?!_next/static|_next/image|icon.png|icons|sw.js|manifest.webmanifest|session|bff|up|offline).*)"],
};
