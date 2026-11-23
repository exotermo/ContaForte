"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchMe } from "@/lib/api";
import { mutedCls } from "./ui";

const ROLES: Record<string, string> = { ADMIN: "Administrador", USER: "Usuário", SERVICE: "Serviço", DEVICE: "Dispositivo" };

export function Greeting() {
  const { data } = useQuery({ queryKey: ["me"], queryFn: fetchMe, retry: false });
  if (!data) return <div className="h-14" aria-hidden />;
  return (
    <div>
      <h1 className="text-2xl font-semibold">Olá, {data.email}</h1>
      <p className={`mt-1 ${mutedCls}`}>
        Perfil: <span data-testid="role">{ROLES[data.role] ?? data.role}</span>
      </p>
    </div>
  );
}
