import { expect, test } from "@playwright/test";

const email = process.env.ADMIN_EMAIL!;
const password = process.env.ADMIN_PASSWORD!;

test.describe("sessão", () => {
  test("visitante é levado para o login", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("button", { name: "Entrar" })).toBeVisible();
  });

  test("senha errada mostra erro genérico e não cria sessão", async ({ page, context }) => {
    await page.goto("/login");
    await page.getByLabel("E-mail").fill(email);
    await page.getByLabel("Senha").fill("senha-errada-e2e");
    await page.getByRole("button", { name: "Entrar" }).click();

    // o Next também tem um role=alert próprio (anunciador de rotas): restringe ao formulário
    await expect(page.locator("form [role=alert]")).toHaveText("E-mail ou senha incorretos.");
    await expect(page).toHaveURL(/\/login$/);
    expect((await context.cookies()).filter((c) => c.name.startsWith("cf_"))).toHaveLength(0);
  });

  test("login, persistência após recarregar e logout", async ({ page, context }) => {
    await page.goto("/login");
    await page.getByLabel("E-mail").fill(email);
    await page.getByLabel("Senha").fill(password);
    await page.getByRole("button", { name: "Entrar" }).click();

    await expect(page.getByRole("heading", { name: `Olá, ${email}` })).toBeVisible();
    await expect(page.getByTestId("role")).toHaveText("Administrador");

    // tokens só em cookies httpOnly: invisíveis ao JavaScript da página
    const cookies = (await context.cookies()).filter((c) => c.name.startsWith("cf_"));
    expect(cookies.map((c) => c.name).sort()).toEqual(["cf_at", "cf_rt"]);
    expect(cookies.every((c) => c.httpOnly && c.sameSite === "Strict")).toBe(true);
    expect(await page.evaluate(() => document.cookie)).toBe("");
    expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toMatch(
      /ey|token/i,
    );

    await page.reload();
    await expect(page.getByRole("heading", { name: `Olá, ${email}` })).toBeVisible();

    await page.getByRole("button", { name: "Sair" }).click();
    await expect(page).toHaveURL(/\/login$/);
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("renova a sessão sozinho quando o access token expira", async ({ page, context }) => {
    await page.goto("/login");
    await page.getByLabel("E-mail").fill(email);
    await page.getByLabel("Senha").fill(password);
    await page.getByRole("button", { name: "Entrar" }).click();
    await expect(page.getByRole("heading", { name: `Olá, ${email}` })).toBeVisible();

    // simula a expiração do access token apagando só o cookie dele
    await context.clearCookies({ name: "cf_at" });
    await page.reload();

    await expect(page.getByRole("heading", { name: `Olá, ${email}` })).toBeVisible();
    expect((await context.cookies()).some((c) => c.name === "cf_at")).toBe(true);
  });

  test("manifest PWA e service worker estão publicados", async ({ request }) => {
    const manifest = await request.get("/manifest.webmanifest");
    expect(manifest.ok()).toBe(true);
    expect((await manifest.json()).name).toContain("ContaForte");
    expect((await request.get("/sw.js")).ok()).toBe(true);
    expect((await request.get("/icons/icon-512.png")).ok()).toBe(true);
  });

  test("o otimizador de imagens está desligado", async ({ request }) => {
    // ele atende sem login e carrega código nativo que o processador do servidor não executa
    const res = await request.get("/_next/image?url=%2Ficons%2Ficon-192.png&w=64&q=75");
    expect(res.status()).toBe(404);
  });

  test("cabeçalhos de segurança estão presentes", async ({ request }) => {
    const res = await request.get("/login");
    expect(res.headers()["content-security-policy"]).toContain("frame-ancestors 'none'");
    expect(res.headers()["x-frame-options"]).toBe("DENY");
  });
});
