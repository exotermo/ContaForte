"use client";

import { useEffect } from "react";

/** Registra o service worker mínimo (só em produção). Ele não faz cache de API nem de sessão. */
export function SwRegister() {
  useEffect(() => {
    if (process.env.NODE_ENV === "production" && "serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js").catch(() => undefined);
    }
  }, []);
  return null;
}
