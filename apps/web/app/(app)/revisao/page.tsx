import type { Metadata } from "next";
import { ReviewView } from "@/components/review-view";

export const metadata: Metadata = { title: "Revisão · ContaForte" };

export default function RevisaoPage() {
  return <ReviewView />;
}
