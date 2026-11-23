"use client";

import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { UnauthorizedError, logout } from "@/lib/api";
import { ApiError } from "@/lib/finance";

let leaving = false;

/** Sessão inválida em qualquer chamada: apaga os cookies e volta ao login (uma vez só, mesmo com várias falhas juntas). */
function onAuthError(error: unknown) {
  if (error instanceof UnauthorizedError && !leaving) {
    leaving = true;
    logout().finally(() => window.location.replace("/login"));
  }
}

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        queryCache: new QueryCache({ onError: onAuthError }),
        mutationCache: new MutationCache({ onError: onAuthError }),
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            // erros de negócio (4xx) não melhoram tentando de novo; só falhas de rede/servidor
            retry: (count, error) =>
              !(error instanceof UnauthorizedError) && !(error instanceof ApiError && error.status < 500) && count < 2,
          },
        },
      }),
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
