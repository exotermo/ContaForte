"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export function messageFor(status: number, retryAfter: string | null): string {
  if (status === 401) return "E-mail ou senha incorretos.";
  if (status === 429) {
    const min = Math.max(1, Math.ceil(Number(retryAfter ?? "900") / 60));
    return `Muitas tentativas. Tente novamente em ${min} min.`;
  }
  return "Não foi possível entrar agora. Tente novamente em instantes.";
}

export function LoginForm() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/session/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: data.get("email"), password: data.get("password") }),
      });
      if (res.ok) {
        router.replace("/");
        router.refresh();
        return;
      }
      setError(messageFor(res.status, res.headers.get("retry-after")));
    } catch {
      setError(messageFor(503, null));
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-5" noValidate>
      <div>
        <label htmlFor="email" className="block text-sm font-medium">
          E-mail
        </label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          required
          className="mt-1 w-full rounded-lg border border-zinc-300 bg-white px-3 py-2 text-zinc-900 shadow-sm focus:border-emerald-600 focus:outline-none focus:ring-2 focus:ring-emerald-600/30 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
        />
      </div>
      <div>
        <label htmlFor="password" className="block text-sm font-medium">
          Senha
        </label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
          className="mt-1 w-full rounded-lg border border-zinc-300 bg-white px-3 py-2 text-zinc-900 shadow-sm focus:border-emerald-600 focus:outline-none focus:ring-2 focus:ring-emerald-600/30 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
        />
      </div>
      <p role="alert" aria-live="polite" className="min-h-5 text-sm text-red-600 dark:text-red-400">
        {error}
      </p>
      <button
        type="submit"
        disabled={loading}
        className="w-full rounded-lg bg-emerald-700 px-4 py-2.5 font-medium text-white transition hover:bg-emerald-800 focus:outline-none focus:ring-2 focus:ring-emerald-600/50 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {loading ? "Entrando…" : "Entrar"}
      </button>
    </form>
  );
}
