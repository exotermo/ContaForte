import type { Metadata } from "next";
import { Suspense } from "react";
import { TransactionsView } from "@/components/transactions-view";

export const metadata: Metadata = { title: "Transações · ContaForte" };

export default function TransacoesPage() {
  // useSearchParams (filtro vindo da URL) exige um limite de Suspense no App Router
  return (
    <Suspense fallback={<p className="text-zinc-500">Carregando…</p>}>
      <TransactionsView />
    </Suspense>
  );
}
