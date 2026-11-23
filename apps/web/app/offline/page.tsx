export const metadata = { title: "Sem conexão · ContaForte" };

export default function Offline() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-sm flex-col justify-center px-4 text-center">
      <h1 className="text-2xl font-semibold">Sem conexão</h1>
      <p className="mt-2 text-zinc-600 dark:text-zinc-400">Verifique sua internet e tente novamente.</p>
    </main>
  );
}
