import type { Metadata } from "next";
import { ImportsView } from "@/components/imports-view";

export const metadata: Metadata = { title: "Importações · ContaForte" };

export default function ImportacoesPage() {
  return <ImportsView />;
}
