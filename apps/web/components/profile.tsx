"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { UnauthorizedError, fetchMe, logout } from "@/lib/api";

const ROLES: Record<string, string> = { ADMIN: "Administrador", USER: "Usuário", SERVICE: "Serviço", DEVICE: "Dispositivo" };

export function Profile() {
  const router = useRouter();
  const { data, error, isPending } = useQuery({ queryKey: ["me"], queryFn: fetchMe, retry: false });

  // sessão inválida: apaga os cookies antes de ir ao /login (senão o guarda de navegação os veria e voltaria)
  useEffect(() => {
    if (error instanceof UnauthorizedError) {
      logout().finally(() => router.replace("/login"));
    }
  }, [error, router]);

  async function onLogout() {
    await logout();
    router.replace("/login");
    router.refresh();
  }

  if (isPending) return <p className="text-zinc-500">Carregando…</p>;
  if (error && !(error instanceof UnauthorizedError)) {
    return <p role="alert" className="text-red-600 dark:text-red-400">Não foi possível carregar seus dados.</p>;
  }
  if (!data) return null;

  return (
    <section className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Olá, {data.email}</h1>
        <p className="mt-1 text-zinc-600 dark:text-zinc-400">
          Perfil: <span data-testid="role">{ROLES[data.role] ?? data.role}</span>
        </p>
      </div>
      <p className="rounded-lg border border-zinc-200 bg-zinc-50 p-4 text-sm text-zinc-700 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-300">
        Sua sessão está ativa. Contas, transações e o painel financeiro chegam nos próximos marcos do projeto.
      </p>
      <button
        onClick={onLogout}
        className="rounded-lg border border-zinc-300 px-4 py-2 text-sm font-medium transition hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-emerald-600/40 dark:border-zinc-700 dark:hover:bg-zinc-800"
      >
        Sair
      </button>
    </section>
  );
}
