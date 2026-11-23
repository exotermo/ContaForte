// Cliente das rotas de dados (/bff/*). O proxy do Next injeta o token do cookie httpOnly: o JS nunca o vê.
import { UnauthorizedError, refreshSession } from "./api";
import type {
  Account,
  CategoryNode,
  ContextItem,
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
  if (res.status === 429) return "Muitas requisições. Aguarde um instante e tente de novo.";
  if (res.status === 403) return "Você não tem permissão para isso.";
  if (res.status >= 500) return "Serviço indisponível no momento. Tente novamente em instantes.";
  if (res.status === 400 && (detail === "" || isFrameworkMessage(detail))) return "Confira os campos informados.";
  return detail || "Não foi possível concluir a operação.";
}

async function request<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const send = () =>
    fetch(`/bff${path}`, {
      method: init.method ?? "GET",
      headers: init.body === undefined ? undefined : { "Content-Type": "application/json" },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
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
