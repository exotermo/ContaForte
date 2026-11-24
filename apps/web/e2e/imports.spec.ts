import path from "node:path";
import { expect, test, type Page } from "@playwright/test";

const email = process.env.ADMIN_EMAIL!;
const password = process.env.ADMIN_PASSWORD!;
// no container do `cf e2e` as fixtures sintéticas ficam montadas em /datasets/synthetic/imports
const FIXTURES = process.env.FIXTURES_DIR ?? path.resolve(__dirname, "../../../datasets/synthetic/imports");
const fixture = (name: string) => path.join(FIXTURES, name);

// Tudo que este arquivo cria leva o prefixo "e2e-": o `cf e2e` remove contas, transações e importações ao terminar.
const stamp = Date.now().toString(36);

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: `Olá, ${email}` })).toBeVisible();
}

async function novaConta(page: Page, nome: string): Promise<string> {
  const r = await page.request.post("/bff/accounts", { data: { name: nome, type: "CHECKING_ACCOUNT" } });
  expect(r.status()).toBe(201);
  return (await r.json()).id;
}

const saldo = async (page: Page, id: string): Promise<string> => (await (await page.request.get(`/bff/accounts/${id}`)).json()).balance;

/** Prévia + confirmação pela API, para montar o cenário sem passar pela tela. */
async function importarPorApi(page: Page, accountId: string, arquivo: string) {
  const fs = await import("node:fs");
  const up = await page.request.post("/bff/imports", { multipart: { file: { name: path.basename(arquivo), mimeType: "application/octet-stream", buffer: fs.readFileSync(arquivo) }, accountId } });
  expect(up.status()).toBe(201);
  const confirm = await page.request.post(`/bff/imports/${(await up.json()).id}/confirm`);
  expect(confirm.status()).toBe(200);
}

async function enviarPelaTela(page: Page, conta: string, arquivo: string) {
  await page.getByRole("button", { name: "Nova importação" }).click();
  await page.getByLabel("Conta que recebe o extrato").selectOption({ label: conta });
  await page.getByLabel("Arquivo do extrato (OFX ou CSV)").setInputFiles(arquivo);
  await page.getByRole("button", { name: "Continuar" }).click();
}

test.describe("importação de extratos", () => {
  test("OFX: prévia sem criar nada, confirmar, sobreposição com duplicadas, desfazer pelo histórico", async ({ page }) => {
    await login(page);
    const conta = `e2e-${stamp} Corrente OFX`;
    const id = await novaConta(page, conta);

    // ---- 1º arquivo: prévia → nada foi criado → confirmar
    await page.goto("/importacoes");
    await enviarPelaTela(page, conta, fixture("conta-corrente-1.ofx"));
    await expect(page.getByRole("heading", { name: "Revise antes de importar" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Novas (12)" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByRole("row")).toHaveCount(13); // cabeçalho + 12 linhas
    await expect(page.getByText("Serão importadas 12 transações.")).toBeVisible();
    expect((await (await page.request.get(`/bff/transactions?accountId=${id}`)).json()).totalElements).toBe(0);
    expect(await saldo(page, id)).toBe("0.00");

    await page.getByRole("button", { name: "Importar 12 transações" }).click();
    await expect(page.getByRole("heading", { name: "Importação concluída" })).toBeVisible();
    await expect(page.getByRole("status")).toContainText("12 transações importadas");
    expect(await saldo(page, id)).toBe("4179.53");

    // ---- as transações da importação, com a etiqueta da origem
    await page.getByRole("link", { name: "Ver as transações" }).click();
    await expect(page.getByText("Mostrando só as transações de uma importação.")).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: "COMPRA CARTAO MERCADO EXEMPLO" })).toContainText("OFX");
    await expect(page.getByRole("row")).toHaveCount(13);

    // ---- 2º arquivo, sobreposto: 4 duplicadas e 3 novas
    await page.goto("/importacoes");
    await enviarPelaTela(page, conta, fixture("conta-corrente-2.ofx"));
    await expect(page.getByRole("button", { name: "Duplicadas (4)" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Novas (3)" })).toBeVisible();
    await page.getByRole("button", { name: "Duplicadas (4)" }).click();
    const dup = page.getByRole("row").filter({ hasText: "DÉBITO AUTOMÁTICO ENERGIA EXEMPLO" });
    await expect(dup).toContainText("Identificador do extrato já importado nesta conta");
    await expect(dup).toContainText("OFX"); // com o que foi comparada
    await expect(page.getByLabel(/Importar a linha/)).toHaveCount(0); // duplicada não tem caixa
    await page.getByRole("button", { name: "Importar 3 transações" }).click();
    await expect(page.getByRole("status")).toContainText("3 transações importadas");
    expect(await saldo(page, id)).toBe("3954.62"); // 4179,53 - 200,00 - 89,90 + 64,99

    // ---- histórico e desfazer
    await page.getByRole("button", { name: "Voltar ao histórico" }).click();
    await expect(page.getByRole("list", { name: "Histórico de importações" }).getByRole("listitem")).toHaveCount(2);
    await page.getByRole("button", { name: "Desfazer conta-corrente-2.ofx" }).click();
    await expect(page.getByRole("alertdialog")).toContainText("saem dos saldos e do resumo");
    await page.getByRole("button", { name: "Desfazer importação" }).click();
    await expect(page.getByRole("listitem").filter({ hasText: "conta-corrente-2.ofx" })).toContainText("Desfeita");
    expect(await saldo(page, id)).toBe("4179.53");

    await page.getByRole("button", { name: "Desfazer conta-corrente-1.ofx" }).click();
    await page.getByRole("button", { name: "Desfazer importação" }).click();
    await expect(page.getByRole("listitem").filter({ hasText: "conta-corrente-1.ofx" })).toContainText("Desfeita");
    expect(await saldo(page, id)).toBe("0.00");
  });

  test("CSV: mapeamento de colunas e decisão sobre possíveis duplicatas", async ({ page }) => {
    await login(page);
    const conta = `e2e-${stamp} Corrente CSV`;
    const id = await novaConta(page, conta);
    await importarPorApi(page, id, fixture("conta-corrente-1.ofx")); // já existem 12 transações vindas do OFX

    await page.goto("/importacoes");
    await enviarPelaTela(page, conta, fixture("banco-b.csv"));

    // passo das colunas: preenchido pelo palpite, com a amostra do arquivo
    await expect(page.getByRole("heading", { name: "Como ler este CSV" })).toBeVisible();
    await expect(page.getByRole("table", { name: "Amostra das primeiras linhas do arquivo" })).toBeVisible();
    await expect(page.getByLabel("Coluna da data")).toHaveValue("0");
    await expect(page.getByLabel("Colunas separadas de débito e de crédito")).toBeChecked();
    await page.getByRole("button", { name: "Ler extrato" }).click();

    // 3 prováveis duplicatas (mesma descrição) e 9 possíveis (mesmo valor e dia, descrição diferente)
    await expect(page.getByRole("heading", { name: "Revise antes de importar" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Duplicadas (3)" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Possíveis duplicatas (9)" })).toHaveAttribute("aria-pressed", "true"); // abre onde há decisão
    await expect(page.getByText("Nada selecionado para importar.")).toBeVisible(); // possíveis vêm desmarcadas
    await expect(page.getByRole("row").nth(1)).toContainText("Mesmo valor em data próxima");
    await expect(page.getByRole("row").nth(1)).toContainText("OFX"); // o lançamento existente com o qual foi comparada

    await page.getByLabel(/Importar a linha/).first().check();
    await expect(page.getByText("Serão importadas 1 transação.")).toBeVisible();
    await page.getByRole("button", { name: "Importar 1 transação" }).click();
    await expect(page.getByRole("status")).toContainText("1 transação importada");
    expect((await (await page.request.get(`/bff/transactions?accountId=${id}&size=100`)).json()).totalElements).toBe(13);
  });

  test("extrato recusado mostra o motivo e continua na escolha do arquivo", async ({ page }) => {
    await login(page);
    const conta = `e2e-${stamp} Recusas`;
    await novaConta(page, conta);
    await page.goto("/importacoes");

    await enviarPelaTela(page, conta, fixture("moeda-usd.ofx"));
    await expect(page.getByRole("alert").filter({ hasText: "reais (BRL)" })).toBeVisible();
    await expect(page.getByLabel("Arquivo do extrato (OFX ou CSV)")).toBeVisible();

    await page.getByLabel("Arquivo do extrato (OFX ou CSV)").setInputFiles({ name: "x.ofx", mimeType: "application/zip", buffer: Buffer.from([0x50, 0x4b, 3, 4, 0, 0, 0, 0]) });
    await page.getByRole("button", { name: "Continuar" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "não parece um extrato" })).toBeVisible();

    await page.getByLabel("Arquivo do extrato (OFX ou CSV)").setInputFiles(fixture("duas-contas.ofx"));
    await page.getByRole("button", { name: "Continuar" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "mais de uma conta" })).toBeVisible();
    await expect(page.getByRole("list", { name: "Histórico de importações" })).toHaveCount(0);
  });

  test("o proxy de importação exige sessão, só aceita multipart, bloqueia CSRF e corta arquivo grande", async ({ page, request }) => {
    const falso = "00000000-0000-0000-0000-000000000000";
    const arquivo = { name: "a.ofx", mimeType: "application/octet-stream", buffer: Buffer.from("x") };
    expect((await request.post("/bff/imports", { multipart: { file: arquivo, accountId: falso } })).status()).toBe(401);
    expect((await request.get("/bff/imports")).status()).toBe(401);

    await login(page);
    expect((await page.request.get("/bff/imports")).status()).toBe(200);
    expect((await page.request.post("/bff/imports", { data: { accountId: falso } })).status()).toBe(415); // JSON não é upload
    const csrf = await page.request.post("/bff/imports", { headers: { Origin: "http://evil.example" }, multipart: { file: arquivo, accountId: falso } });
    expect(csrf.status()).toBe(403);
    // 7 MB: o gateway (nginx, limite de 6 MB) recusa antes de chegar ao app
    const grande = await page.request.post("/bff/imports", { multipart: { file: { name: "grande.ofx", mimeType: "application/octet-stream", buffer: Buffer.alloc(7 * 1024 * 1024, 97) }, accountId: falso } });
    expect(grande.status()).toBe(413);
  });
});
