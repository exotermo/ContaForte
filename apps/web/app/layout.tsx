import type { Metadata, Viewport } from "next";
import { Providers } from "@/components/providers";
import { SwRegister } from "@/components/sw-register";
import "./globals.css";

export const metadata: Metadata = {
  title: "ContaForte",
  description: "Copiloto financeiro pessoal: entenda, preveja e controle seus gastos.",
  applicationName: "ContaForte",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#047857" },
    { media: "(prefers-color-scheme: dark)", color: "#09090b" },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR">
      <body>
        <Providers>{children}</Providers>
        <SwRegister />
      </body>
    </html>
  );
}
