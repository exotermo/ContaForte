import { LoginForm } from "@/components/login-form";

export const metadata = { title: "Entrar · ContaForte" };

export default function LoginPage() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-sm flex-col justify-center px-4 py-10">
      <h1 className="text-center text-3xl font-semibold tracking-tight text-emerald-700 dark:text-emerald-500">
        ContaForte
      </h1>
      <p className="mb-8 mt-2 text-center text-sm text-zinc-600 dark:text-zinc-400">Entre para ver suas finanças.</p>
      <LoginForm />
    </main>
  );
}
