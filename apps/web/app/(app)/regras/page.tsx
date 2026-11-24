import type { Metadata } from "next";
import { RulesView } from "@/components/rules-view";

export const metadata: Metadata = { title: "Regras · ContaForte" };

export default function RegrasPage() {
  return <RulesView />;
}
