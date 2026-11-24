import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  timeout: 30_000,
  retries: 0,
  // em série: todas as specs usam o mesmo usuário (admin) e algumas medem a variação do RESUMO dele, que outro teste
  // rodando ao mesmo tempo também mexe. Em paralelo isso gera falhas intermitentes.
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.BASE_URL ?? "http://127.0.0.1:8080",
    locale: "pt-BR",
    trace: "off",
  },
});
