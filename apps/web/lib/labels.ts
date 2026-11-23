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
