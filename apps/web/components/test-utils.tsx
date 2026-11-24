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
  importId: null,
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

import type { CsvSuggestion, ImportBatch, ImportItem } from "@/lib/types";

export const batch = (over: Partial<ImportBatch> = {}): ImportBatch => ({
  id: "i1",
  accountId: "a1",
  format: "OFX",
  status: "PREVIEW",
  fileName: "extrato.ofx",
  totalRows: 12,
  newRows: 12,
  duplicateRows: 0,
  possibleRows: 0,
  invalidRows: 0,
  importedRows: 0,
  mapping: null,
  expiresAt: "2026-11-25T12:00:00Z",
  createdAt: "2026-11-24T12:00:00Z",
  ...over,
});

export const item = (over: Partial<ImportItem> = {}): ImportItem => ({
  id: "n1",
  lineNo: 1,
  status: "NEW",
  occurredAt: "2026-03-02T15:00:00Z",
  type: "EXPENSE",
  amount: "187.45",
  description: "COMPRA CARTAO MERCADO EXEMPLO",
  merchant: "Mercado Exemplo",
  reason: null,
  selected: true,
  duplicateOf: null,
  existing: null,
  ...over,
});

export const pageOf = <T,>(items: T[], over: Partial<{ totalElements: number; totalPages: number; page: number }> = {}) => ({
  items,
  page: over.page ?? 0,
  size: 50,
  totalElements: over.totalElements ?? items.length,
  totalPages: over.totalPages ?? (items.length ? 1 : 0),
});

export const suggestionA = (over: Partial<CsvSuggestion> = {}): CsvSuggestion => ({
  delimiter: ";",
  skipLines: 0,
  hasHeader: true,
  headers: ["Data", "Histórico", "Documento", "Valor"],
  sample: [
    ["02/03/2026", "COMPRA CARTAO MERCADO EXEMPLO", "FIT0001", "-187,45"],
    ["03/03/2026", "PIX ENVIADO CAFÉ DA PRAÇA", "FIT0002", "-18,50"],
  ],
  mapping: {
    delimiter: ";",
    skipLines: "0",
    hasHeader: "true",
    dateColumn: "Data",
    dateFormat: "DMY_SLASH",
    amountColumn: "Valor",
    descriptionColumn: "Histórico",
    idColumn: "Documento",
    invertSign: "false",
  },
  warnings: [],
  ...over,
});
