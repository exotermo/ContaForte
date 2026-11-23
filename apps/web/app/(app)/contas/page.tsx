import type { Metadata } from "next";
import { AccountsView } from "@/components/accounts-view";

export const metadata: Metadata = { title: "Contas · ContaForte" };

export default function ContasPage() {
  return <AccountsView />;
}
