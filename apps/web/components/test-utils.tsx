import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import type { Account, CategoryNode, ContextItem, Summary, Transaction } from "@/lib/types";

/** Renderiza dentro de um QueryClient limpo (sem retry, sem cache compartilhado entre testes). */
export function renderWithQuery(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const result = render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
  return { ...result, client };
}

export const account = (over: Partial<Account> = {}): Account => ({
  id: "a1",
  name: "Nubank",
  type: "CHECKING_ACCOUNT",
  liquidity: "AVAILABLE",
  currency: "BRL",
  openingBalance: "0.00",
  balance: "1234.50",
  archived: false,
  createdAt: "2026-05-01T12:00:00Z",
  ...over,
});

export const tx = (over: Partial<Transaction> = {}): Transaction => ({
  id: "t1",
  accountId: "a1",
  counterpartAccountId: null,
  type: "EXPENSE",
  amount: "120.50",
  currency: "BRL",
  description: "Almoço",
  merchant: "Cantina",
  categoryId: "c2",
  contextId: null,
  occurredAt: "2026-05-10T15:00:00Z",
  source: "MANUAL",
  createdAt: "2026-05-10T15:00:00Z",
  updatedAt: "2026-05-10T15:00:00Z",
  ...over,
});

export const categories: CategoryNode[] = [
  {
    id: "c1",
    parentId: null,
    code: "FOOD",
    name: "Alimentação",
    system: true,
    children: [{ id: "c2", parentId: "c1", code: "FOOD_RESTAURANT", name: "Restaurante", system: true, children: [] }],
  },
];

export const contexts: ContextItem[] = [
  { id: "x1", code: "FAMILY", name: "Família", system: true },
  { id: "x2", code: "PET", name: "Pet", system: true },
];

export const summary = (over: Partial<Summary> = {}): Summary => ({
  availableCash: "4200.00",
  restrictedBalance: "1200.00",
  investedAssets: "8400.00",
  creditCardBalance: "0.00",
  otherBalance: "0.00",
  totalNetWorth: "13800.00",
  accounts: [
    { id: "a1", name: "Banco", type: "CHECKING_ACCOUNT", liquidity: "AVAILABLE", balance: "4200.00" },
    { id: "a2", name: "Vale alimentação", type: "VA", liquidity: "RESTRICTED", balance: "700.00" },
  ],
  ...over,
});
