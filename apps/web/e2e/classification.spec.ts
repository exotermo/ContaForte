import path from "node:path";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const adminEmail = process.env.ADMIN_EMAIL!;
const adminPassword = process.env.ADMIN_PASSWORD!;
const FIXTURES = process.env.FIXTURES_DIR ?? path.resolve(__dirname, "../../../datasets/synthetic/imports");
const fixture = (name: string) => path.join(FIXTURES, name);

// Cada teste roda com um USUÁRIO DESCARTÁVEL ("e2e-..."): regras, favorecidos e feedback aprendidos não podem sobrar na conta
// real nem contaminar a próxima execução. O `cf e2e` apaga os usuários "e2e-%" ao terminar (cascata leva tudo).
const stamp = Date.now().toString(36);
let seq = 0;

async function novoUsuario(request: APIRequestContext): Promise<{ email: string; password: string }> {
  const login = await request.post("/api/v1/auth/login", { data: { email: adminEmail, password: adminPassword } });
  expect(login.status()).toBe(200);
  const token = (await login.json()).accessToken as string;
  const email = `e2e-${stamp}-${++seq}@contaforte.local`;
  const password = `E2e-${stamp}-${Math.random().toString(36).slice(2)}-Aa1`;
  const created = await request.post("/api/v1/admin/users", { headers: { Authorization: `Bearer ${token}` }, data: { email, password } });
  expect(created.status()).toBe(201);
  return { email, password };
}

async function entrar(page: Page, request: APIRequestContext) {
  const u = await novoUsuario(request);
  await page.goto("/login");
  await page.getByLabel("E-mail").fill(u.email);
  await page.getByLabel("Senha").fill(u.password);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: `Olá, ${u.email}` })).toBeVisible();
  return u;
}

async function novaConta(page: Page, nome: string): Promise<string> {
  const r = await page.request.post("/bff/accounts", { data: { name: nome, type: "CHECKING_ACCOUNT" } });
  expect(r.status()).toBe(201);
  return (await r.json()).id;
}

/** Id de uma categoria padrão pelo código estável. */
async function categoria(page: Page, code: string): Promise<string> {
  const tree = (await (await page.request.get("/bff/categories")).json()) as { id: string; code: string | null; children: { id: string; code: string | null }[] }[];
  for (const c of tree) {
    if (c.code === code) return c.id;
    for (const f of c.children) if (f.code === code) return f.id;
  }
  throw new Error(`categoria ${code} não encontrada`);
}

async function enviarPelaTela(page: Page, conta: string, arquivo: string) {
  await page.getByRole("button", { name: "Nova importação" }).click();
  await page.getByLabel("Conta que recebe o extrato").selectOption({ label: conta });
  await page.getByLabel("Arquivo do extrato (OFX ou CSV)").setInputFiles(arquivo);
  await page.getByRole("button", { name: "Continuar" }).click();
  await expect(page.getByRole("heading", { name: "Revise antes de importar" })).toBeVisible();
}

test.describe("classificação (M4)", () => {
  test("importar, ensinar uma vez e a próxima importação já sugere; desfazer desfaz o aprendizado", async ({ page, request }) => {
    await entrar(page, request);
    const contaA = `e2e-${stamp} Corrente A`;
    const contaB = `e2e-${stamp} Corrente B`;
    await novaConta(page, contaA);
    await novaConta(page, contaB);
    const combustivel = await categoria(page, "TRANSPORT_FUEL");
    const lanches = await categoria(page, "FOOD_SNACKS");

    // ---- 1ª importação: as regras do sistema categorizam o que reconhecem; o resto o usuário decide
    await page.goto("/importacoes");
    await enviarPelaTela(page, contaA, fixture("conta-corrente-1.ofx"));
    await expect(page.getByText(/Categorizadas sozinhas:/)).toContainText("Categorizadas sozinhas: 9 · Só sugeridas: 0");
    const posto = page.getByRole("row").filter({ hasText: "POSTO EXEMPLO" });
    await expect(posto).toContainText("Automática · Regra do sistema · 90%");
    await expect(posto.getByLabel(/^Categoria da linha/)).toHaveValue(combustivel);
    const cafe = page.getByRole("row").filter({ hasText: "CAFÉ DA PRAÇA" });
    await expect(cafe).toContainText("Sem sugestão");
    await cafe.getByLabel(/^Categoria da linha/).selectOption(lanches);
    await expect(cafe).toContainText("Sua escolha");
    await page.getByRole("button", { name: "Importar 12 transações" }).click();
    await expect(page.getByRole("heading", { name: "Importação concluída" })).toBeVisible();

    // ---- a transação automática ganha o selo; a escolhida não
    await page.getByRole("link", { name: "Ver as transações" }).click();
    await expect(page.getByRole("row").filter({ hasText: "POSTO EXEMPLO" })).toContainText("Automática");
    await expect(page.getByRole("row").filter({ hasText: "CAFÉ DA PRAÇA" })).not.toContainText("Automática");

    // ---- o favorecido foi aprendido com UMA decisão: confiança 50%, só sugere
    await page.goto("/favorecidos");
    const favorecido = page.getByRole("listitem").filter({ hasText: "CAFÉ DA PRAÇA" });
    await expect(favorecido).toContainText("1 decisão sua");
    await expect(favorecido).toContainText("1 de 1 decisão · 50% · só sugere");

    // ---- 2ª importação (outra conta): o café já vem SUGERIDO, e um clique aceita
    await page.goto("/importacoes");
    await enviarPelaTela(page, contaB, fixture("conta-corrente-1.ofx"));
    const cafeB = page.getByRole("row").filter({ hasText: "CAFÉ DA PRAÇA" });
    await expect(cafeB).toContainText("Sugestão · Favorecido conhecido · 50%");
    await expect(cafeB.getByLabel(/^Categoria da linha/)).toHaveValue("");   // sugestão não é aplicação
    await cafeB.getByRole("button", { name: /^Aceitar a sugestão da linha/ }).click();
    await expect(cafeB).toContainText("Sua escolha");
    await expect(cafeB.getByLabel(/^Categoria da linha/)).toHaveValue(lanches);
    await page.getByRole("button", { name: "Importar 12 transações" }).click();
    await expect(page.getByRole("heading", { name: "Importação concluída" })).toBeVisible();

    await page.goto("/favorecidos");
    await expect(page.getByRole("listitem").filter({ hasText: "CAFÉ DA PRAÇA" })).toContainText("2 de 2 decisões · 66%");

    // ---- desfazer a 2ª importação desfaz o que ela ensinou
    await page.goto("/importacoes");
    await page.getByRole("listitem").filter({ hasText: contaB }).getByRole("button", { name: /^Desfazer/ }).click();
    await page.getByRole("button", { name: "Desfazer importação" }).click();
    await expect(page.getByRole("listitem").filter({ hasText: contaB })).toContainText("Desfeita");
    await page.goto("/favorecidos");
    await expect(page.getByRole("listitem").filter({ hasText: "CAFÉ DA PRAÇA" })).toContainText("1 de 1 decisão · 50%");
  });

  test("revisão: reclassificar em duas fases, confirmar a sugestão e criar uma regra 'sempre assim'", async ({ page, request }) => {
    await entrar(page, request);
    const conta = await novaConta(page, `e2e-${stamp} Revisão`);
    const lanches = await categoria(page, "FOOD_SNACKS");
    const lancar = (description: string, categoryId?: string) =>
      page.request.post("/bff/transactions", { data: { type: "EXPENSE", accountId: conta, amount: "8.00", description, ...(categoryId ? { categoryId } : {}) } });
    for (let i = 0; i < 2; i++) expect((await lancar("PIX ENVIADO Café da Praça", lanches)).status()).toBe(201);   // 2 decisões: só sugere
    expect((await lancar("PIX ENVIADO Café da Praça")).status()).toBe(201);
    expect((await lancar("COMPRA LOJA E2E QUALQUER")).status()).toBe(201);

    await page.goto("/revisao");
    await expect(page.getByText(/transações estão sem categoria/)).toContainText("2 transações estão sem categoria");

    // fase 1: prévia, nada muda
    await page.getByRole("button", { name: "Ver o que seria categorizado" }).click();
    await expect(page.getByText(/seriam categorizadas sozinhas/)).toContainText("Entre 2 transações: 0 seriam categorizadas sozinhas, 1 ficariam como sugestão e 1 continuariam sem sugestão.");
    expect((await (await page.request.get("/bff/classification/review/count")).json()).suggested).toBe(0);
    // fase 2: aplicar o que foi visto
    await page.getByRole("button", { name: "Aplicar" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Pronto:" })).toContainText("0 categorizadas sozinhas e 1 sugeridas");

    // o menu avisa que há sugestão; confirmar com um clique tira da fila
    await expect(page.getByRole("link", { name: /Revisão\s*,?\s*1 sugestão aguardando/ })).toBeVisible();
    const sugerida = page.getByRole("listitem").filter({ hasText: "PIX ENVIADO Café da Praça" });
    await expect(sugerida).toContainText("Sugestão · Favorecido conhecido · 66%");
    await sugerida.getByRole("button", { name: /^Confirmar a sugestão/ }).click();
    await expect(page.getByRole("listitem").filter({ hasText: "PIX ENVIADO Café da Praça" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: /^Revisão$/ })).toBeVisible();   // contador some

    // a sem categoria vira regra "sempre assim"
    const loja = page.getByRole("listitem").filter({ hasText: "COMPRA LOJA E2E QUALQUER" });
    await loja.getByRole("button", { name: /^Escolher a categoria/ }).click();
    await loja.getByLabel(/^Categoria de/).selectOption(lanches);
    await loja.getByLabel("Aplicar sempre que a descrição tiver este trecho").check();
    await loja.getByLabel("Trecho da descrição").fill("loja e2e qualquer");
    await loja.getByRole("button", { name: "Salvar escolha" }).click();
    await expect(page.getByText("Nada para revisar. Tudo classificado.")).toBeVisible();

    // a regra aparece e vale: o teste de texto mostra "Sua regra · 100%"
    await page.goto("/regras");
    await expect(page.getByRole("listitem").filter({ hasText: "loja e2e qualquer" })).toContainText("Contém");
    await page.getByLabel("Descrição").fill("COMPRA LOJA E2E QUALQUER 12/03");
    await page.getByRole("button", { name: "Testar" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Categoria:" })).toContainText("Sua regra · 100% · aplicaria sozinha");
  });

  test("regras: criar, desativar e apagar; favorecido: esquecer", async ({ page, request }) => {
    await entrar(page, request);
    const conta = await novaConta(page, `e2e-${stamp} Regras`);
    const lanches = await categoria(page, "FOOD_SNACKS");
    await page.request.post("/bff/transactions", { data: { type: "EXPENSE", accountId: conta, amount: "5.00", description: "PIX ENVIADO Joana Souza", categoryId: lanches } });

    await page.goto("/regras");
    await page.getByRole("button", { name: "Nova regra" }).click();
    await page.getByRole("button", { name: "Criar regra" }).click();
    await expect(page.getByRole("form", { name: "Nova regra" }).getByRole("alert")).toContainText("ao menos 3 letras ou números");
    await page.getByLabel("Trecho da descrição").fill("Padaria E2E");
    await page.getByRole("form", { name: "Nova regra" }).getByLabel("Categoria", { exact: true }).selectOption(lanches);
    await page.getByRole("button", { name: "Criar regra" }).click();
    const regra = page.getByRole("listitem").filter({ hasText: "padaria e2e" });
    await expect(regra).toContainText("Contém “padaria e2e”");

    await regra.getByRole("checkbox", { name: /ativa$/ }).uncheck();
    await expect(regra.getByRole("checkbox", { name: /ativa$/ })).not.toBeChecked();
    await regra.getByRole("button", { name: /^Apagar a regra/ }).click();
    await page.getByRole("button", { name: "Apagar regra" }).click();
    await expect(page.getByRole("listitem").filter({ hasText: "padaria e2e" })).toHaveCount(0);

    await page.goto("/favorecidos");
    await page.getByLabel("Buscar por nome").fill("joana");
    await page.getByRole("button", { name: "Buscar" }).click();
    const f = page.getByRole("listitem").filter({ hasText: "Joana Souza" });
    await expect(f).toContainText("1 decisão sua");
    await f.getByRole("button", { name: /^Esquecer/ }).click();
    await page.getByRole("button", { name: "Esquecer favorecido" }).click();
    await expect(page.getByText("Nenhum favorecido com esse nome.")).toBeVisible();
  });

  test("o formulário de transação sugere a categoria enquanto a descrição é digitada", async ({ page, request }) => {
    await entrar(page, request);
    await novaConta(page, `e2e-${stamp} Formulário`);
    const combustivel = await categoria(page, "TRANSPORT_FUEL");

    await page.goto("/transacoes");
    await page.getByRole("button", { name: "Nova transação" }).click();
    await page.getByLabel("Descrição").fill("COMPRA POSTO ABC COMBUSTIVEL");
    await expect(page.getByText(/Sugestão de categoria:/)).toContainText("Sugestão de categoria: Combustível (Regra do sistema · 90%)");
    await page.getByRole("button", { name: "Usar" }).click();
    await expect(page.getByRole("form", { name: "Nova transação" }).getByLabel("Categoria", { exact: true })).toHaveValue(combustivel);
  });

  test("o proxy das rotas de classificação exige sessão e bloqueia CSRF", async ({ page, request, playwright }) => {
    const anonimo = await playwright.request.newContext({ baseURL: process.env.BASE_URL });
    for (const rota of ["/bff/categorization-rules", "/bff/pix-recipients", "/bff/classification/review/count"]) {
      expect((await anonimo.get(rota)).status(), rota).toBe(401);
    }
    await anonimo.dispose();

    await entrar(page, request);
    expect((await page.request.get("/bff/categorization-rules")).status()).toBe(200);
    const csrf = await page.request.post("/bff/classification/reclassify", { headers: { Origin: "http://evil.example" }, data: { expectedAuto: 0, expectedSuggest: 0 } });
    expect(csrf.status()).toBe(403);
    expect((await page.request.get("/bff/admin/users")).status()).toBe(404);   // continua fora da lista de rotas
  });
});
