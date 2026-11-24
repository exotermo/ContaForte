import type { Metadata } from "next";
import { RecipientsView } from "@/components/recipients-view";

export const metadata: Metadata = { title: "Favorecidos · ContaForte" };

export default function FavorecidosPage() {
  return <RecipientsView />;
}
