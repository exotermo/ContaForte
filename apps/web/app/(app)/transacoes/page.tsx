import type { Metadata } from "next";
import { TransactionsView } from "@/components/transactions-view";

export const metadata: Metadata = { title: "Transações · ContaForte" };

export default function TransacoesPage() {
  return <TransactionsView />;
}
