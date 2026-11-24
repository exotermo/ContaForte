"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate } from "@/lib/datetime";
import { forgetRecipient, listRecipients } from "@/lib/finance";
import { percent } from "@/lib/labels";
import { formatMoney } from "@/lib/money";
import type { Learned, Recipient } from "@/lib/types";
import { useCatalog } from "./catalog";
import { btnDanger, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls } from "./ui";

type Catalog = ReturnType<typeof useCatalog>;
const PAGE = 20;

function LearnedLine({ title, category, context, catalog }: { title: string; category: Learned | null; context: Learned | null; catalog: Catalog }) {
  if (!category && !context) return null;
  const part = (kind: string, l: Learned | null, name: string) =>
    l && (
      <span>
        {kind}: <strong>{name}</strong>{" "}
        <span className={mutedCls}>
          ({l.votes} de {l.totalVotes} {l.totalVotes === 1 ? "decisão" : "decisões"} · {percent(l.confidence)} ·{" "}
          {l.decision === "AUTO" ? "aplica sozinho" : l.decision === "SUGGEST" ? "só sugere" : "ainda não sugere"})
        </span>
      </span>
    );
  return (
    <p className="text-sm">
      <span className="font-medium">{title}</span> {part("categoria", category, catalog.categoryName(category?.id))}
      {category && context && " · "}
      {part("contexto", context, catalog.contextName(context?.id))}
    </p>
  );
}

function RecipientItem({ r, catalog }: { r: Recipient; catalog: Catalog }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const forget = useMutation({
    mutationFn: () => forgetRecipient(r.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["recipients"] }),
  });
  return (
    <li className={`${cardCls} space-y-2`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="break-words font-medium">{r.displayName}</p>
          <p className={mutedCls}>
            {r.transactionCount} {r.transactionCount === 1 ? "decisão sua" : "decisões suas"}
            {r.averageAmount ? ` · média ${formatMoney(r.averageAmount)}` : ""}
            {r.lastTransactionAt ? ` · última em ${formatDate(r.lastTransactionAt)}` : ""}
          </p>
        </div>
        {confirming ? (
          <div role="group" aria-label={`Confirmar esquecer ${r.displayName}`} className="flex gap-2">
            <button type="button" className={btnDanger} onClick={() => forget.mutate()} disabled={forget.isPending}>
              Esquecer favorecido
            </button>
            <button type="button" className={btnSecondary} onClick={() => setConfirming(false)}>
              Cancelar
            </button>
          </div>
        ) : (
          <button type="button" className={btnSecondary} onClick={() => setConfirming(true)} aria-label={`Esquecer ${r.displayName}`}>
            Esquecer
          </button>
        )}
      </div>
      <LearnedLine title="Você paga:" category={r.expense.category} context={r.expense.context} catalog={catalog} />
      <LearnedLine title="Ele te paga:" category={r.income.category} context={r.income.context} catalog={catalog} />
      {confirming && <p className={mutedCls}>Esquecer apaga só o que foi aprendido. As transações continuam como estão.</p>}
      {forget.error && (
        <p role="alert" className={errorCls}>
          {forget.error.message}
        </p>
      )}
    </li>
  );
}

export function RecipientsView() {
  const catalog = useCatalog();
  const [draft, setDraft] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const listQ = useQuery({ queryKey: ["recipients", q, page], queryFn: () => listRecipients(q, page, PAGE), placeholderData: (previous) => previous });
  const data = listQ.data;
  const totalPages = Math.max(data?.totalPages ?? 1, 1);

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Favorecidos</h1>
        <p className={`mt-1 max-w-2xl ${mutedCls}`}>
          Quem você paga e quem te paga por PIX, e o que o sistema aprendeu de cada um com as suas decisões. Só aprende com o que você escolhe, nunca com o que ele aplicou sozinho. Os nomes ficam só na sua conta.
        </p>
      </header>

      <form
        className="flex max-w-lg items-end gap-2"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          setQ(draft.trim());
          setPage(0);
        }}
      >
        <div className="flex-1">
          <label htmlFor="busca-favorecido" className={labelCls}>
            Buscar por nome
          </label>
          <input id="busca-favorecido" value={draft} onChange={(e) => setDraft(e.target.value)} maxLength={100} className={inputCls} />
        </div>
        <button type="submit" className={btnSecondary}>
          Buscar
        </button>
      </form>

      {listQ.error && (
        <p role="alert" className={errorCls}>
          Não foi possível carregar os favorecidos.
        </p>
      )}
      {data && data.items.length === 0 && (
        <p className={`${cardCls} text-center ${mutedCls}`}>
          {q ? "Nenhum favorecido com esse nome." : "Nenhum favorecido ainda. Eles aparecem quando você classifica transações de PIX."}
        </p>
      )}
      {data && data.items.length > 0 && (
        <ul className="space-y-3" aria-label="Favorecidos">
          {data.items.map((r) => (
            <RecipientItem key={r.id} r={r} catalog={catalog} />
          ))}
        </ul>
      )}
      {data && data.totalElements > PAGE && (
        <nav aria-label="Paginação dos favorecidos" className="flex flex-wrap items-center justify-between gap-3">
          <p className={mutedCls}>
            Página {page + 1} de {totalPages} · {data.totalElements} favorecidos
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
