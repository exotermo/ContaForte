import { expect, test, type Page } from "@playwright/test";

const email = process.env.ADMIN_EMAIL!;
const password = process.env.ADMIN_PASSWORD!;

// Todos os dados criados aqui levam o prefixo "e2e-": o `cf e2e` os remove ao terminar.
const stamp = Date.now().toString(36);
const CORRENTE = `e2e-${stamp} Corrente`;
const VA = `e2e-${stamp} VA`;

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: `Olá, ${email}` })).toBeVisible();
}

/** "R$ 1.279,50" / "-R$ 350,00" / "+R$ 5,00" → centavos (só para comparar nos testes). */
function cents(text: string): number {
  const negative = text.includes("-");
  const digits = text.replace(/[^0-9,]/g, "").replace(",", ".");
  return Math.round(parseFloat(digits) * 100) * (negative ? -1 : 1);
}

type Cards = { available: number; restricted: number; netWorth: number };

/** Lê os cartões do Resumo. Sem contas (estado vazio) os valores são zero. */
async function readCards(page: Page): Promise<Cards> {
  await page.goto("/");
  await expect(page.getByTestId("card-available").or(page.getByText("Cadastre sua primeira conta"))).toBeVisible();
  const read = async (id: string) => {
    const card = page.getByTestId(id);
    return (await card.count()) ? cents(await card.locator("p").first().innerText()) : 0;
  };
  return { available: await read("card-available"), restricted: await read("card-restricted"), netWorth: await read("card-net-worth") };
}

async function newAccount(page: Page, name: string, type: string, opening: string) {
  await page.getByRole("button", { name: "Nova conta" }).click();
  const form = page.getByRole("form", { name: "Nova conta" });
  await form.getByLabel("Nome da conta").fill(name);
  await form.getByLabel("Tipo").selectOption({ label: type });
  await form.getByLabel("Saldo inicial (R$)").fill(opening);
  await form.getByRole("button", { name: "Salvar conta" }).click();
  await expect(page.getByRole("form", { name: "Nova conta" })).toHaveCount(0);
  await expect(page.getByText(name, { exact: true })).toBeVisible();
}

const accountRow = (page: Page, name: string) => page.getByRole("list", { name: "Lista de contas" }).getByRole("listitem").filter({ hasText: name });

test.describe("finanças", () => {
  test("conta → receita, despesa e transferência → saldos e resumo conferem", async ({ page }) => {
    await login(page);
    const before = await readCards(page);

    // --- contas
    await page.getByRole("link", { name: "Contas" }).click();
    await newAccount(page, CORRENTE, "Conta corrente", "1.000,00");
    await newAccount(page, VA, "Vale-alimentação (VA)", "700,00");
    await expect(accountRow(page, CORRENTE)).toContainText("R$ 1.000,00");
    await expect(accountRow(page, VA)).toContainText("Restrito");

    // --- resumo: VA entra em "Restrito", nunca em "Disponível"
    let cards = await readCards(page);
    expect(cards.available - before.available).toBe(100000);
    expect(cards.restricted - before.restricted).toBe(70000);
    expect(cards.netWorth - before.netWorth).toBe(170000);

    // --- transações
    await page.getByRole("link", { name: "Transações" }).click();
    await page.getByRole("button", { name: "Nova transação" }).click();
    const form = page.getByRole("form", { name: "Nova transação" });

    await form.locator("label", { hasText: "Receita" }).click();
    await form.getByLabel("Conta", { exact: true }).selectOption({ label: CORRENTE });
    await form.getByLabel("Valor (R$)").fill("500,00");
    await form.getByLabel("Descrição").fill("e2e salário");
    await form.getByRole("button", { name: "Registrar" }).click();
    await expect(form.getByRole("status")).toHaveText("Transação registrada.");

    await form.locator("label", { hasText: "Despesa" }).click();
    await form.getByLabel("Valor (R$)").fill("120,50");
    await form.getByLabel("Descrição").fill("e2e almoço");
    await form.getByLabel("Categoria").selectOption({ label: "Restaurante" });
    await form.getByLabel("Contexto").selectOption({ label: "Relacionamento" });
    await form.getByRole("button", { name: "Registrar" }).click();
    await expect(form.getByRole("status")).toHaveText("Transação registrada.");

    await form.locator("label", { hasText: "Transferência" }).click();
    await form.getByLabel("Conta de origem").selectOption({ label: CORRENTE });
    await form.getByLabel("Conta de destino").selectOption({ label: VA });
    await form.getByLabel("Valor (R$)").fill("100,00");
    await form.getByLabel("Descrição").fill("e2e recarga do VA");
    await form.getByRole("button", { name: "Registrar" }).click();
    await expect(form.getByRole("status")).toHaveText("Transação registrada.");
    await form.getByRole("button", { name: "Fechar" }).click();

    // --- a lista mostra tudo com sinal, categoria e destino
    const salario = page.getByRole("row").filter({ hasText: "e2e salário" });
    const almoco = page.getByRole("row").filter({ hasText: "e2e almoço" });
    const recarga = page.getByRole("row").filter({ hasText: "e2e recarga do VA" });
    await expect(salario).toContainText("+R$ 500,00");
    await expect(almoco).toContainText("-R$ 120,50");
    await expect(almoco).toContainText("Alimentação › Restaurante");
    await expect(recarga).toContainText(`${CORRENTE} → ${VA}`);

    // --- saldos: 1000 + 500 - 120,50 - 100 = 1279,50 | VA 700 + 100 = 800
    await page.getByRole("link", { name: "Contas" }).click();
    await expect(accountRow(page, CORRENTE)).toContainText("R$ 1.279,50");
    await expect(accountRow(page, VA)).toContainText("R$ 800,00");

    cards = await readCards(page);
    expect(cards.available - before.available).toBe(127950);
    expect(cards.restricted - before.restricted).toBe(80000);
    expect(cards.netWorth - before.netWorth).toBe(207950); // a transferência não muda o patrimônio

    // --- filtro por texto e exclusão com confirmação
    await page.getByRole("link", { name: "Transações" }).click();
    await page.getByLabel("Buscar").fill("e2e almoço");
    await page.getByRole("button", { name: "Filtrar" }).click();
    await expect(page.getByRole("row").filter({ hasText: "e2e salário" })).toHaveCount(0);
    await expect(almoco).toHaveCount(1);

    await page.getByRole("button", { name: "Excluir e2e almoço" }).click();
    await page.getByRole("button", { name: "Confirmar exclusão" }).click();
    await expect(page.getByText(/Nenhuma transação encontrada com esses filtros/)).toBeVisible();

    await page.getByRole("link", { name: "Contas" }).click();
    await expect(accountRow(page, CORRENTE)).toContainText("R$ 1.400,00"); // 1000 + 500 - 100
  });

  test("validações do formulário aparecem sem chamar a API", async ({ page }) => {
    await login(page);
    await page.goto("/transacoes");
    // precisa de conta ativa para o formulário aparecer; se o ambiente estiver vazio, o aviso é o esperado
    await page.getByRole("button", { name: "Nova transação" }).click();
    const form = page.getByRole("form", { name: "Nova transação" });
    if (await form.count()) {
      await form.getByLabel("Valor (R$)").fill("0,005");
      await form.getByRole("button", { name: "Registrar" }).click();
      await expect(form.getByText("Use no máximo 2 casas decimais.")).toBeVisible();
      await form.getByLabel("Valor (R$)").fill("abc");
      await form.getByRole("button", { name: "Registrar" }).click();
      await expect(form.getByText("Use apenas números, vírgula e ponto.")).toBeVisible();
    } else {
      await expect(page.getByText(/Cadastre uma conta antes/)).toBeVisible();
    }
  });

  test("o proxy de dados exige sessão e só expõe as rotas financeiras", async ({ page, request }) => {
    // sem cookies: 401
    expect((await request.get("/bff/accounts")).status()).toBe(401);
    expect((await request.get("/bff/summary")).status()).toBe(401);

    await login(page);
    expect((await page.request.get("/bff/accounts")).status()).toBe(200);
    expect((await page.request.get("/bff/summary")).status()).toBe(200);
    // rotas fora da lista não existem pelo BFF, mesmo logado
    for (const path of ["/bff/auth/login", "/bff/admin/users", "/bff/users/me", "/bff/openapi", "/bff/accounts/..%2Fauth"]) {
      expect((await page.request.get(path)).status(), path).toBe(404);
    }
    // CSRF: requisição mutável com Origin de outro site é recusada
    const csrf = await page.request.post("/bff/accounts", {
      headers: { Origin: "http://evil.example", "Content-Type": "application/json" },
      data: { name: "e2e-csrf", type: "CASH" },
    });
    expect(csrf.status()).toBe(403);
  });

  test("navegação mostra a página atual e funciona sem sessão → login", async ({ page, context }) => {
    await login(page);
    await page.getByRole("link", { name: "Contas" }).click();
    await expect(page.getByRole("link", { name: "Contas" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: "Contas" })).toBeVisible();

    await context.clearCookies();
    await page.goto("/transacoes");
    await expect(page).toHaveURL(/\/login$/);
  });
});
