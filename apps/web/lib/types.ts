// Contratos da API (valores monetários sempre como string decimal).

export type AccountType =
  | "CHECKING_ACCOUNT"
  | "SAVINGS_ACCOUNT"
  | "CASH"
  | "CREDIT_CARD"
  | "VA"
  | "VR"
  | "INVESTMENT_ACCOUNT"
  | "OTHER";

export type Liquidity = "AVAILABLE" | "RESTRICTED" | "INVESTED" | "LIABILITY" | "OTHER";

export type Account = {
  id: string;
  name: string;
  type: AccountType;
  liquidity: Liquidity;
  currency: string;
  openingBalance: string;
  balance: string;
  archived: boolean;
  createdAt: string;
};

export type CategoryNode = {
  id: string;
  parentId: string | null;
  code: string | null;
  name: string;
  system: boolean;
  children: CategoryNode[];
};

export type ContextItem = { id: string; code: string | null; name: string; system: boolean };

export type TransactionType = "INCOME" | "EXPENSE" | "TRANSFER" | "INVESTMENT" | "REFUND";

export type Transaction = {
  id: string;
  accountId: string;
  counterpartAccountId: string | null;
  type: TransactionType;
  amount: string;
  currency: string;
  description: string | null;
  merchant: string | null;
  categoryId: string | null;
  contextId: string | null;
  occurredAt: string;
  source: string;
  importId: string | null;
  createdAt: string;
  updatedAt: string;
};

export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number };

export type Summary = {
  availableCash: string;
  restrictedBalance: string;
  investedAssets: string;
  creditCardBalance: string;
  otherBalance: string;
  totalNetWorth: string;
  accounts: { id: string; name: string; type: AccountType; liquidity: Liquidity; balance: string }[];
};

export type TransactionFilter = {
  from?: string;
  to?: string;
  accountId?: string;
  type?: TransactionType | "";
  categoryId?: string;
  contextId?: string;
  q?: string;
  importId?: string;
};

export type NewTransaction = {
  type: TransactionType;
  accountId: string;
  counterpartAccountId?: string;
  amount: string;
  occurredAt?: string;
  description?: string;
  merchant?: string;
  categoryId?: string;
  contextId?: string;
};

// ---- importação de extratos (M3)
export type ImportFormat = "OFX" | "CSV";
export type ImportStatus = "PREVIEW" | "CONFIRMED" | "ROLLED_BACK";
export type ImportItemStatus = "NEW" | "DUPLICATE" | "POSSIBLE_DUPLICATE" | "INVALID";

export type ImportBatch = {
  id: string;
  accountId: string;
  format: ImportFormat;
  status: ImportStatus;
  fileName: string;
  totalRows: number;
  newRows: number;
  duplicateRows: number;
  possibleRows: number;
  invalidRows: number;
  importedRows: number;
  mapping?: Record<string, string> | null;
  expiresAt?: string;
  confirmedAt?: string;
  rolledBackAt?: string;
  createdAt: string;
  /** só na criação da prévia */
  fileAlreadyImported?: boolean;
  warnings?: string[];
  /** só na confirmação */
  skippedByConflict?: number;
};

export type ExistingSnapshot = {
  id: string;
  occurredAt: string;
  type: TransactionType;
  amount: string;
  description: string | null;
  source: string;
};

export type ImportItem = {
  id: string;
  lineNo: number;
  status: ImportItemStatus;
  occurredAt: string | null;
  type: "INCOME" | "EXPENSE" | null;
  amount: string | null;
  description: string | null;
  merchant: string | null;
  reason: string | null;
  selected: boolean;
  duplicateOf: string | null;
  existing: ExistingSnapshot | null;
};

export type CsvSuggestion = {
  delimiter: string;
  skipLines: number;
  hasHeader: boolean;
  headers: string[];
  sample: string[][];
  mapping: Record<string, string>;
  warnings: string[];
};
