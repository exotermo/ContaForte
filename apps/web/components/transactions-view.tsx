"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { formatDate } from "@/lib/datetime";
import { deleteTransaction, listAccounts, listCategories, listContexts, listTransactions } from "@/lib/finance";
import { TRANSACTION_TYPES, transactionTypeLabel } from "@/lib/labels";
import { formatMoney } from "@/lib/money";
import type { Account, CategoryNode, ContextItem, Transaction, TransactionFilter } from "@/lib/types";
import { TransactionForm } from "./transaction-form";
import { btnDanger, btnPrimary, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls, negCls, posCls } from "./ui";

// Célula da tabela. No celular a tabela vira uma pilha de cartões: cada célula mostra seu rótulo (data-label) à esquerda.
const CELL =
  "px-3 py-3 max-md:flex max-md:items-start max-md:justify-between max-md:gap-4 max-md:px-0 max-md:py-1 max-md:before:shrink-0 max-md:before:font-medium max-md:before:text-zinc-500 max-md:before:content-[attr(data-label)] dark:max-md:before:text-zinc-400";

// referências estáveis: um `?? []` novo a cada render invalidaria os useMemo abaixo
const NO_ACCOUNTS: Account[] = [];
const NO_CATEGORIES: CategoryNode[] = [];
const NO_CONTEXTS: ContextItem[] = [];

function categoryNames(tree: CategoryNode[]): Map<string, string> {
  const map = new Map<string, string>();
  for (const c of tree) {
    map.set(c.id, c.name);
    for (const f of c.children) map.set(f.id, `${c.name} › ${f.name}`);
  }
  return map;
}

/** Entrada soma, saída subtrai; transferência e investimento só movem entre contas. */
function amountView(t: Transaction): { text: string; cls: string } {
  const money = formatMoney(t.amount);
  if (t.type === "INCOME" || t.type === "REFUND") return { text: `+${money}`, cls: posCls };
  if (t.type === "EXPENSE") return { text: `-${money}`, cls: negCls };
  return { text: money, cls: "" };
}

function Row({ t, accountName, category }: { t: Transaction; accountName: (id: string) => string; category: string }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const mutation = useMutation({
    mutationFn: () => deleteTransaction(t.id),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["transactions"] }),
        queryClient.invalidateQueries({ queryKey: ["accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["summary"] }),
      ]);
    },
  });
  const amount = amountView(t);
  const title = t.description || t.merchant || "—";

  return (
    <tr className="align-top max-md:block max-md:px-4 max-md:py-3">
      <td data-label="Data" className={`${CELL} whitespace-nowrap`}>{formatDate(t.occurredAt)}</td>
      <td data-label="Descrição" className={CELL}>
        <div className="max-md:text-right">
          <p className="font-medium">{title}</p>
          <p className={mutedCls}>
            {transactionTypeLabel(t.type)}
            {t.description && t.merchant ? ` · ${t.merchant}` : ""}
          </p>
        </div>
      </td>
      <td data-label="Categoria" className={CELL}>
        <span className="max-md:text-right">{category}</span>
      </td>
      <td data-label="Conta" className={CELL}>
        <div className="max-md:text-right">
          {accountName(t.accountId)}
          {t.counterpartAccountId && <span className={mutedCls}> → {accountName(t.counterpartAccountId)}</span>}
        </div>
      </td>
      <td data-label="Valor" className={`${CELL} whitespace-nowrap font-medium tabular-nums md:text-right ${amount.cls}`}>{amount.text}</td>
      <td className={`${CELL} md:text-right`}>
        {confirming ? (
          <div role="group" aria-label={`Confirmar exclusão de ${title}`} className="flex flex-wrap gap-2 md:justify-end">
            <button type="button" onClick={() => mutation.mutate()} disabled={mutation.isPending} className={btnDanger}>
              Confirmar exclusão
            </button>
            <button type="button" onClick={() => setConfirming(false)} className={btnSecondary}>
              Cancelar
            </button>
          </div>
        ) : (
          <button type="button" onClick={() => setConfirming(true)} className={btnSecondary} aria-label={`Excluir ${title}`}>
            Excluir
          </button>
        )}
        {mutation.error && (
          <p role="alert" className={`mt-1 ${errorCls}`}>
            {mutation.error.message}
          </p>
        )}
      </td>
    </tr>
  );
}

export function TransactionsView() {
  const [showForm, setShowForm] = useState(false);
  const [draft, setDraft] = useState<TransactionFilter>({});
  const [applied, setApplied] = useState<TransactionFilter>({});
  const [page, setPage] = useState(0);

  // todas as contas (inclusive arquivadas) para nomear lançamentos antigos; só as ativas entram no formulário
  const accountsQ = useQuery({ queryKey: ["accounts", true], queryFn: () => listAccounts(true) });
  const categoriesQ = useQuery({ queryKey: ["categories"], queryFn: listCategories, staleTime: 5 * 60_000 });
  const contextsQ = useQuery({ queryKey: ["contexts"], queryFn: listContexts, staleTime: 5 * 60_000 });
  const txQ = useQuery({
    queryKey: ["transactions", applied, page],
    queryFn: () => listTransactions(applied, page),
    placeholderData: (previous) => previous,
  });

  const accounts = accountsQ.data ?? NO_ACCOUNTS;
  const categories = categoriesQ.data ?? NO_CATEGORIES;
  const contexts = contextsQ.data ?? NO_CONTEXTS;
  const names = useMemo(() => new Map(accounts.map((a) => [a.id, a.name])), [accounts]);
  const categoryLabel = useMemo(() => categoryNames(categories), [categories]);
  const accountName = (id: string) => names.get(id) ?? "Conta";
  const set = <K extends keyof TransactionFilter>(key: K, value: TransactionFilter[K]) => setDraft((d) => ({ ...d, [key]: value }));

  function applyFilters(e: React.FormEvent) {
    e.preventDefault();
    setApplied({ ...draft });
    setPage(0);
  }
  function clearFilters() {
    setDraft({});
    setApplied({});
    setPage(0);
  }

  const data = txQ.data;
  const totalPages = Math.max(data?.totalPages ?? 1, 1);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">Transações</h1>
        {!showForm && (
          <button type="button" onClick={() => setShowForm(true)} className={btnPrimary}>
            Nova transação
          </button>
        )}
      </div>

      {showForm && (
        <TransactionForm
          accounts={accounts.filter((a) => !a.archived)}
          categories={categories}
          contexts={contexts}
          onDone={() => setShowForm(false)}
        />
      )}

      <form onSubmit={applyFilters} className={`${cardCls} grid gap-3 sm:grid-cols-2 lg:grid-cols-4`} aria-label="Filtros">
        <div>
          <label htmlFor="f-busca" className={labelCls}>Buscar</label>
          <input id="f-busca" value={draft.q ?? ""} onChange={(e) => set("q", e.target.value)} maxLength={100} placeholder="Descrição ou estabelecimento" className={inputCls} />
        </div>
        <div>
          <label htmlFor="f-conta" className={labelCls}>Conta</label>
          <select id="f-conta" value={draft.accountId ?? ""} onChange={(e) => set("accountId", e.target.value)} className={inputCls}>
            <option value="">Todas</option>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="f-tipo" className={labelCls}>Tipo</label>
          <select id="f-tipo" value={draft.type ?? ""} onChange={(e) => set("type", e.target.value as TransactionFilter["type"])} className={inputCls}>
            <option value="">Todos</option>
            {TRANSACTION_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="f-categoria" className={labelCls}>Categoria</label>
          <select id="f-categoria" value={draft.categoryId ?? ""} onChange={(e) => set("categoryId", e.target.value)} className={inputCls}>
            <option value="">Todas</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="f-contexto" className={labelCls}>Contexto</label>
          <select id="f-contexto" value={draft.contextId ?? ""} onChange={(e) => set("contextId", e.target.value)} className={inputCls}>
            <option value="">Todos</option>
            {contexts.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="f-de" className={labelCls}>De</label>
          <input id="f-de" type="date" value={draft.from ?? ""} onChange={(e) => set("from", e.target.value)} className={inputCls} />
        </div>
        <div>
          <label htmlFor="f-ate" className={labelCls}>Até</label>
          <input id="f-ate" type="date" value={draft.to ?? ""} onChange={(e) => set("to", e.target.value)} className={inputCls} />
        </div>
        <div className="flex items-end gap-2">
          <button type="submit" className={btnPrimary}>Filtrar</button>
          <button type="button" onClick={clearFilters} className={btnSecondary}>Limpar</button>
        </div>
      </form>

      {txQ.isPending && <p className={mutedCls}>Carregando…</p>}
      {txQ.error && <p role="alert" className={errorCls}>{txQ.error.message || "Não foi possível carregar as transações."}</p>}

      {data && data.items.length === 0 && (
        <p className={`${cardCls} text-center ${mutedCls}`}>
          {Object.values(applied).some(Boolean) ? "Nenhuma transação encontrada com esses filtros." : "Nenhuma transação ainda. Registre a primeira."}
        </p>
      )}

      {/* o contêiner é `relative`: elementos sr-only (absolutos) ficam contidos nele em vez de alargar a página no celular */}
      {data && data.items.length > 0 && (
        <div className={`${cardCls} relative overflow-x-auto p-0`}>
          <table className="w-full text-left text-sm max-md:block">
            <caption className="sr-only">Transações, da mais recente para a mais antiga</caption>
            <thead className="border-b border-zinc-200 text-zinc-600 max-md:sr-only dark:border-zinc-800 dark:text-zinc-400">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">Data</th>
                <th scope="col" className="px-3 py-2 font-medium">Descrição</th>
                <th scope="col" className="px-3 py-2 font-medium">Categoria</th>
                <th scope="col" className="px-3 py-2 font-medium">Conta</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Valor</th>
                <th scope="col" className="px-3 py-2"><span className="sr-only">Ações</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 max-md:block dark:divide-zinc-800">
              {data.items.map((t) => (
                <Row key={t.id} t={t} accountName={accountName} category={t.categoryId ? (categoryLabel.get(t.categoryId) ?? "—") : "—"} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalElements > 0 && (
        <nav aria-label="Paginação" className="flex flex-wrap items-center justify-between gap-3">
          <p className={mutedCls}>
            Página {page + 1} de {totalPages} · {data.totalElements} {data.totalElements === 1 ? "transação" : "transações"}
          </p>
          <div className="flex gap-2">
            <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0} className={btnSecondary}>
              Anterior
            </button>
            <button type="button" onClick={() => setPage((p) => p + 1)} disabled={page + 1 >= totalPages} className={btnSecondary}>
              Próxima
            </button>
          </div>
        </nav>
      )}
    </div>
  );
}
