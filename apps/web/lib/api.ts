// Cliente das rotas de sessão (/session/*). Os tokens vivem em cookies httpOnly: o JS nunca os lê.

export type Me = { id: string; email: string; role: string; enabled: boolean; createdAt: string };

export class UnauthorizedError extends Error {
  constructor() {
    super("sessão inválida");
  }
}

let refreshing: Promise<boolean> | null = null;

/** Single-flight: chamadas simultâneas compartilham UM refresh (a rotação do backend não tolera reuso). */
export function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = fetch("/session/refresh", { method: "POST" })
      .then((r) => r.ok)
      .catch(() => false)
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

export async function fetchMe(): Promise<Me> {
  let res = await fetch("/session/me", { cache: "no-store" });
  if (res.status === 401 && (await refreshSession())) {
    res = await fetch("/session/me", { cache: "no-store" });
  }
  if (res.status === 401) throw new UnauthorizedError();
  if (!res.ok) throw new Error(`falha ao carregar o perfil (${res.status})`);
  return res.json();
}

export async function logout(): Promise<void> {
  await fetch("/session/logout", { method: "POST" }).catch(() => undefined);
}
