"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { fetchMe, logout } from "@/lib/api";
import { btnSecondary } from "./ui";

const LINKS = [
  { href: "/", label: "Resumo" },
  { href: "/contas", label: "Contas" },
  { href: "/transacoes", label: "Transações" },
  { href: "/importacoes", label: "Importações" },
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { data: me } = useQuery({ queryKey: ["me"], queryFn: fetchMe, retry: false });

  async function onLogout() {
    await logout();
    router.replace("/login");
    router.refresh();
  }

  return (
    <>
      <a
        href="#conteudo"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-10 focus:rounded focus:bg-white focus:px-3 focus:py-2 dark:focus:bg-zinc-900"
      >
        Pular para o conteúdo
      </a>
      <header className="border-b border-zinc-200 dark:border-zinc-800">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 sm:px-6">
          <Link href="/" className="text-lg font-semibold tracking-tight text-emerald-700 dark:text-emerald-500">
            ContaForte
          </Link>
          <nav aria-label="Principal" className="flex flex-1 gap-1">
            {LINKS.map((l) => {
              const active = l.href === "/" ? pathname === "/" : pathname.startsWith(l.href);
              return (
                <Link
                  key={l.href}
                  href={l.href}
                  aria-current={active ? "page" : undefined}
                  className={`rounded-lg px-3 py-1.5 text-sm font-medium focus:outline-none focus:ring-2 focus:ring-emerald-600/40 ${
                    active
                      ? "bg-emerald-50 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
                      : "text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800"
                  }`}
                >
                  {l.label}
                </Link>
              );
            })}
          </nav>
          <span className="hidden text-sm text-zinc-600 sm:inline dark:text-zinc-400">{me?.email}</span>
          <button onClick={onLogout} className={btnSecondary}>
            Sair
          </button>
        </div>
      </header>
      <main id="conteudo" className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
        {children}
      </main>
    </>
  );
}
