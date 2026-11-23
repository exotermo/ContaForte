import { Profile } from "@/components/profile";

// Página protegida: o proxy.ts redireciona para /login se não houver sessão.
export default function Home() {
  return (
    <>
      <header className="border-b border-zinc-200 dark:border-zinc-800">
        <div className="mx-auto flex max-w-3xl items-center px-4 py-4 sm:px-6">
          <span className="text-lg font-semibold tracking-tight text-emerald-700 dark:text-emerald-500">ContaForte</span>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
        <Profile />
      </main>
    </>
  );
}
