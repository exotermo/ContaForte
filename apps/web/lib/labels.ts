import type { AccountType, Liquidity, TransactionType } from "./types";

export const ACCOUNT_TYPES: { value: AccountType; label: string; liquidity: Liquidity }[] = [
  { value: "CHECKING_ACCOUNT", label: "Conta corrente", liquidity: "AVAILABLE" },
  { value: "SAVINGS_ACCOUNT", label: "Poupança", liquidity: "AVAILABLE" },
  { value: "CASH", label: "Dinheiro em espécie", liquidity: "AVAILABLE" },
  { value: "CREDIT_CARD", label: "Cartão de crédito", liquidity: "LIABILITY" },
  { value: "VA", label: "Vale-alimentação (VA)", liquidity: "RESTRICTED" },
  { value: "VR", label: "Vale-refeição (VR)", liquidity: "RESTRICTED" },
  { value: "INVESTMENT_ACCOUNT", label: "Conta de investimentos", liquidity: "INVESTED" },
  { value: "OTHER", label: "Outro", liquidity: "OTHER" },
];

export const accountTypeLabel = (t: AccountType) => ACCOUNT_TYPES.find((a) => a.value === t)?.label ?? t;

export const LIQUIDITY: Record<Liquidity, { label: string; help: string }> = {
  AVAILABLE: { label: "Disponível", help: "Dinheiro que você pode gastar ou investir agora." },
  RESTRICTED: { label: "Restrito", help: "Só vale em usos específicos, como VA e VR. Não conta como dinheiro para investir." },
  INVESTED: { label: "Investido", help: "Patrimônio aplicado em investimentos." },
  LIABILITY: { label: "Dívida", help: "Reduz o patrimônio, mas não o dinheiro disponível." },
  OTHER: { label: "Outro", help: "Entra só no patrimônio líquido." },
};

export const TRANSACTION_TYPES: { value: TransactionType; label: string; help: string }[] = [
  { value: "EXPENSE", label: "Despesa", help: "Dinheiro que sai da conta." },
  { value: "INCOME", label: "Receita", help: "Dinheiro que entra na conta." },
  { value: "TRANSFER", label: "Transferência", help: "Move dinheiro entre duas contas suas." },
  { value: "INVESTMENT", label: "Investimento", help: "Move dinheiro para uma conta de investimentos." },
  { value: "REFUND", label: "Estorno", help: "Dinheiro devolvido para a conta." },
];

export const transactionTypeLabel = (t: TransactionType) => TRANSACTION_TYPES.find((x) => x.value === t)?.label ?? t;

/** Tipos que exigem uma conta de destino. */
export const hasCounterpart = (t: TransactionType) => t === "TRANSFER" || t === "INVESTMENT";

// ---- importação de extratos
export const IMPORT_STATUS: Record<string, { label: string; cls: string }> = {
  PREVIEW: { label: "Aguardando confirmação", cls: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200" },
  CONFIRMED: { label: "Importada", cls: "bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200" },
  ROLLED_BACK: { label: "Desfeita", cls: "bg-zinc-200 text-zinc-800 dark:bg-zinc-800 dark:text-zinc-200" },
};

export const ITEM_STATUS: Record<string, { tab: string; help: string }> = {
  NEW: { tab: "Novas", help: "Serão importadas. Desmarque as que você não quer." },
  DUPLICATE: { tab: "Duplicadas", help: "Já existem na conta e não serão importadas." },
  POSSIBLE_DUPLICATE: {
    tab: "Possíveis duplicatas",
    help: "Parecem lançamentos que você já tem (mesmo valor em data próxima). Marque só as que são mesmo novas.",
  },
  INVALID: { tab: "Inválidas", help: "Não deu para ler estas linhas e elas não serão importadas." },
};

export const SOURCE_LABEL: Record<string, string> = { MANUAL: "Manual", OFX: "OFX", CSV: "CSV", PIX: "PIX", OPEN_FINANCE: "Open Finance", SYSTEM: "Sistema" };

export const DATE_FORMATS: { value: string; label: string }[] = [
  { value: "AUTO", label: "Detectar automaticamente" },
  { value: "DMY_SLASH", label: "dd/mm/aaaa (10/03/2026)" },
  { value: "DMY_SLASH_SHORT", label: "dd/mm/aa (10/03/26)" },
  { value: "DMY_DASH", label: "dd-mm-aaaa (10-03-2026)" },
  { value: "DMY_DOT", label: "dd.mm.aaaa (10.03.2026)" },
  { value: "YMD_DASH", label: "aaaa-mm-dd (2026-03-10)" },
  { value: "YMD_SLASH", label: "aaaa/mm/dd (2026/03/10)" },
];

export const DELIMITER_LABEL: Record<string, string> = { ";": "ponto e vírgula (;)", ",": "vírgula (,)", "\t": "tabulação", "|": "barra vertical (|)" };
