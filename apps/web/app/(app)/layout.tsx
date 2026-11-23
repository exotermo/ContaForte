import { AppShell } from "@/components/app-shell";

// Todas as páginas autenticadas dividem o mesmo cabeçalho. O proxy.ts redireciona para /login sem sessão.
export default function AppLayout({ children }: { children: React.ReactNode }) {
  return <AppShell>{children}</AppShell>;
}
