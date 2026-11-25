import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const adminEmail = process.env.ADMIN_EMAIL!;
const adminPassword = process.env.ADMIN_PASSWORD!;

// Usuário descartável ("e2e-..."): o `cf e2e` apaga os "e2e-%" ao terminar.
const stamp = Date.now().toString(36);
let seq = 0;

async function entrar(page: Page, request: APIRequestContext) {
  const login = await request.post("/api/v1/auth/login", { data: { email: adminEmail, password: adminPassword } });
  const token = (await login.json()).accessToken as string;
  const email = `e2e-${stamp}-dash${++seq}@contaforte.local`;
  const password = `E2e-${stamp}-${Math.random().toString(36).slice(2)}-Aa1`;
  const created = await request.post("/api/v1/admin/users", { headers: { Authorization: `Bearer ${token}` }, data: { email, password } });
  expect(created.status()).toBe(201);
  await page.goto("/login");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: `Olá, ${email}` })).toBeVisible();
}

async function categoria(page: Page, code: string): Promise<string> {
  const tree = (await (await page.request.get("/bff/categories")).json()) as { id: string; code: string | null; children: { id: string; code: string | null }[] }[];
  for (const c of tree) {
    if (c.code === code) return c.id;
    for (const f of c.children) if (f.code === code) return f.id;
  }
  throw new Error(`categoria ${code} não encontrada`);
}

test.describe("dashboard (M5)", () => {
  test("mostra o mês, compara com o anterior, abre as categorias e leva às transações filtradas", async ({ page, request }) => {
    await entrar(page, request);
    const conta = await (await page.request.post("/bff/accounts", { data: { name: `e2e-${stamp} Corrente`, type: "CHECKING_ACCOUNT" } })).json();
    const lanches = await categoria(page, "FOOD_SNACKS");
    const combustivel = await categoria(page, "TRANSPORT_FUEL");

    const agora = new Date();
    const mesAnterior = new Date(Date.UTC(agora.getUTCFullYear(), agora.getUTCMonth() - 1, 15, 15, 0, 0)).toISOString();
    const lanca = async (type: string, amount: string, description: string, occurredAt: string, categoryId?: string) => {
      const r = await page.request.post("/bff/transactions", { data: { type, accountId: conta.id, amount, description, occurredAt, categoryId } });
      expect(r.status(), description).toBe(201);
    };
    await lanca("INCOME", "4000.00", "Salário anterior", mesAnterior);
    await lanca("EXPENSE", "1000.00", "Gasto anterior", mesAnterior, lanches);
    await lanca("INCOME", "5000.00", "Salário E2E", agora.toISOString());
    await lanca("EXPENSE", "300.00", "Padaria E2E", agora.toISOString(), lanches);
    await lanca("EXPENSE", "100.00", "Combustível E2E", agora.toISOString(), combustivel);
    await lanca("REFUND", "50.00", "Estorno E2E", agora.toISOString(), lanches);

    await page.goto("/");

    // números do mês e comparação (gastar menos que no mês anterior vem escrito, não só colorido)
    const receitas = page.getByTestId("stat-income");
    await expect(receitas).toContainText("R$ 5.000,00");
    await expect(receitas).toContainText("▲ +R$ 1.000,00 vs mês anterior");
    await expect(page.getByTestId("stat-expense")).toContainText("R$ 350,00");
    await expect(page.getByTestId("stat-expense")).toContainText("▼ -R$ 650,00 vs mês anterior");
    await expect(page.getByTestId("stat-net")).toContainText("R$ 4.650,00");
    await expect(page.getByTestId("chart-monthly").getByRole("img")).toHaveAttribute("aria-label", /receitas R\$ 5\.000,00, gastos R\$ 350,00/);

    // distribuição: Alimentação (300 - 50 de estorno) e Transporte
    const cats = page.getByTestId("share-category");
    await expect(cats).toContainText("R$ 250,00 · 71%");
    await expect(cats).toContainText("R$ 100,00 · 29%");

    // do gráfico para a lista: abre a categoria e segue o link
    await cats.getByText("Alimentação", { exact: true }).click();
    await cats.getByRole("link", { name: /Ver lançamentos de Alimentação/ }).click();
    await expect(page).toHaveURL(/\/transacoes\?/);
    await expect(page.getByRole("heading", { name: "Transações" })).toBeVisible();
    await expect(page.getByText("Padaria E2E")).toBeVisible();
    await expect(page.getByText("Combustível E2E")).toHaveCount(0);   // outra categoria
    await expect(page.getByText("Salário E2E")).toHaveCount(0);       // receita fora do filtro de gastos

    // mês anterior
    await page.goto("/");
    await page.getByRole("button", { name: "Mês anterior" }).click();
    await expect(page.getByTestId("stat-income")).toContainText("R$ 4.000,00");
    await expect(page.getByRole("button", { name: "Próximo mês" })).toBeEnabled();
    await page.getByRole("button", { name: "Próximo mês" }).click();
    await expect(page.getByTestId("stat-income")).toContainText("R$ 5.000,00");
    await expect(page.getByRole("button", { name: "Próximo mês" })).toBeDisabled();
  });

  test("mês sem movimento explica em vez de mostrar gráficos vazios", async ({ page, request }) => {
    await entrar(page, request);
    await page.request.post("/bff/accounts", { data: { name: `e2e-${stamp} Vazia`, type: "CHECKING_ACCOUNT" } });

    await page.goto("/");

    await expect(page.getByTestId("month-empty")).toContainText("Nenhuma movimentação em");
    await expect(page.getByTestId("chart-monthly")).toHaveCount(0);
  });
});
