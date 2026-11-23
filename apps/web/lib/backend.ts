// Endereço do backend na rede interna do Docker (nunca exposto ao navegador).
const BACKEND_URL = process.env.BACKEND_URL ?? "http://backend:8080";

export function backendFetch(path: string, init: RequestInit & { ip?: string; token?: string } = {}) {
  const { ip, token, headers, ...rest } = init;
  const h = new Headers(headers);
  if (ip) h.set("X-Forwarded-For", ip);
  if (token) h.set("Authorization", `Bearer ${token}`);
  return fetch(`${BACKEND_URL}${path}`, { ...rest, headers: h, cache: "no-store" });
}
