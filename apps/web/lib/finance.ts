// Cliente das rotas de dados (/bff/*). O proxy do Next injeta o token do cookie httpOnly: o JS nunca o vê.
import { UnauthorizedError, refreshSession } from "./api";
import type {
  Account,
  CategoryNode,
  ContextItem,
  CsvSuggestion,
  ImportBatch,
  ImportItem,
  ImportItemStatus,
  DecisionBody,
  NewRule,
  ReclassifyOutcome,
  ReclassifyPreview,
  Recipient,
  ReviewCounts,
  Rule,
  RuleTestResult,
  NewTransaction,
  Page,
  Summary,
  Transaction,
  TransactionFilter,
} from "./types";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

/** Mensagens do framework vêm em inglês; as de negócio do backend já vêm em português. */
function isFrameworkMessage(detail: string): boolean {
  return /^(Invalid |Failed |Required |Method |Type mismatch|Bad request|Unsupported )/i.test(detail);
}

async function messageOf(res: Response): Promise<string> {
  let detail = "";
  try {
    const body = (await res.json()) as { detail?: unknown };
    if (typeof body.detail === "string") detail = body.detail;
  } catch {
    // corpo ausente ou não JSON
  }
  if (res.status === 413) return "O arquivo é grande demais (o limite é de 5 MB).";
  if (res.status === 429) {
    const seconds = Number(res.headers.get("retry-after"));
    if (Number.isFinite(seconds) && seconds > 0) {
      const minutes = Math.max(1, Math.ceil(seconds / 60));
      return `Muitos envios em pouco tempo. Tente de novo em ${minutes} min.`;
    }
    return "Muitas requisições. Aguarde um instante e tente de novo.";
  }
  if (res.status === 403) return "Você não tem permissão para isso.";
  if (res.status >= 500) return "Serviço indisponível no momento. Tente novamente em instantes.";
  if (res.status === 400 && (detail === "" || isFrameworkMessage(detail))) return "Confira os campos informados.";
  return detail || "Não foi possível concluir a operação.";
}

/** `form` envia multipart (upload de extrato): o navegador define o Content-Type com o boundary, nunca nós. */
async function request<T>(path: string, init: { method?: string; body?: unknown; form?: FormData } = {}): Promise<T> {
  const send = () =>
    fetch(`/bff${path}`, {
      method: init.method ?? "GET",
      headers: init.body === undefined ? undefined : { "Content-Type": "application/json" },
      body: init.form ?? (init.body === undefined ? undefined : JSON.stringify(init.body)),
      cache: "no-store",
    });
  let res = await send();
  if (res.status === 401 && (await refreshSession())) res = await send();
  if (res.status === 401) throw new UnauthorizedError();
  if (!res.ok) throw new ApiError(res.status, await messageOf(res));
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const query = (params: Record<string, string | number | boolean | undefined>) => {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== "") q.set(k, String(v));
  const s = q.toString();
  return s ? `?${s}` : "";
};

// ---- contas
export const listAccounts = (includeArchived = false) =>
  request<Account[]>(`/accounts${query({ includeArchived: includeArchived || undefined })}`);
export const createAccount = (body: { name: string; type: string; openingBalance: string }) =>
  request<Account>("/accounts", { method: "POST", body });
export const updateAccount = (id: string, body: { name?: string; archived?: boolean }) =>
  request<Account>(`/accounts/${id}`, { method: "PATCH", body });

// ---- categorias e contextos
export const listCategories = () => request<CategoryNode[]>("/categories");
export const listContexts = () => request<ContextItem[]>("/contexts");

// ---- transações
export const listTransactions = (filter: TransactionFilter, page: number, size = 20) =>
  request<Page<Transaction>>(`/transactions${query({ ...filter, page, size })}`);
export const createTransaction = (body: NewTransaction) =>
  request<Transaction>("/transactions", { method: "POST", body });
export const deleteTransaction = (id: string) => request<void>(`/transactions/${id}`, { method: "DELETE" });

// ---- resumo
export const getSummary = () => request<Summary>("/summary");

// ---- importação de extratos
export const suggestMapping = (file: File) => {
  const form = new FormData();
  form.append("file", file);
  return request<CsvSuggestion>("/imports/suggest-mapping", { method: "POST", form });
};

export const createImport = (args: { file: File; accountId: string; mapping?: Record<string, string> }) => {
  const form = new FormData();
  form.append("file", args.file);
  form.append("accountId", args.accountId);
  if (args.mapping) form.append("mapping", JSON.stringify(args.mapping));
  return request<ImportBatch>("/imports", { method: "POST", form });
};

export const listImports = (page = 0, size = 20) => request<Page<ImportBatch>>(`/imports${query({ page, size })}`);
export const getImport = (id: string) => request<ImportBatch>(`/imports/${id}`);
export const listImportItems = (id: string, opts: { status?: ImportItemStatus; page?: number; size?: number } = {}) =>
  request<Page<ImportItem>>(`/imports/${id}/items${query({ status: opts.status, page: opts.page ?? 0, size: opts.size ?? 50 })}`);
export const confirmImport = (id: string, body: { includePossible: string[]; exclude: string[] }) =>
  request<ImportBatch>(`/imports/${id}/confirm`, { method: "POST", body });
export const rollbackImport = (id: string) => request<ImportBatch>(`/imports/${id}/rollback`, { method: "POST" });
export const discardImport = (id: string) => request<void>(`/imports/${id}`, { method: "DELETE" });

export const classifyImportItem = (importId: string, itemId: string, body: { categoryId?: string | null; contextId?: string | null; reset?: boolean }) =>
  request<ImportItem>(`/imports/${importId}/items/${itemId}`, { method: "PATCH", body: { categoryId: null, contextId: null, reset: false, ...body } });

// ---- regras do usuário
export const listRules = () => request<Rule[]>("/categorization-rules");
export const createRule = (body: NewRule) => request<Rule>("/categorization-rules", { method: "POST", body });
export const updateRule = (id: string, body: Partial<Omit<Rule, "id" | "createdAt" | "updatedAt">>) =>
  request<Rule>(`/categorization-rules/${id}`, { method: "PATCH", body });
export const deleteRule = (id: string) => request<void>(`/categorization-rules/${id}`, { method: "DELETE" });
export const testRule = (body: { description: string; type: "INCOME" | "EXPENSE" }) =>
  request<RuleTestResult>("/categorization-rules/test", { method: "POST", body });

// ---- favorecidos PIX
export const listRecipients = (q: string, page = 0, size = 20) => request<Page<Recipient>>(`/pix-recipients${query({ q, page, size })}`);
export const forgetRecipient = (id: string) => request<void>(`/pix-recipients/${id}`, { method: "DELETE" });

// ---- revisão e reclassificação
export const reviewQueue = (page = 0, size = 20) => request<Page<Transaction>>(`/classification/review${query({ page, size })}`);
export const reviewCount = () => request<ReviewCounts>("/classification/review/count");
export const decideClassification = (transactionId: string, body: DecisionBody) =>
  request<Transaction>(`/transactions/${transactionId}/classification`, { method: "POST", body });
export const reclassifyPreview = () => request<ReclassifyPreview>("/classification/reclassify/preview", { method: "POST" });
export const reclassify = (body: { expectedAuto: number; expectedSuggest: number }) =>
  request<ReclassifyOutcome>("/classification/reclassify", { method: "POST", body });
