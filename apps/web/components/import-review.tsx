"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate } from "@/lib/datetime";
import { classifyImportItem, confirmImport, discardImport, listImportItems } from "@/lib/finance";
import { ITEM_STATUS, SOURCE_LABEL } from "@/lib/labels";
import { formatMoney } from "@/lib/money";
import type { ImportBatch, ImportItem, ImportItemStatus } from "@/lib/types";
import { ClassificationBadge, CategorySelect, ContextSelect, useCatalog } from "./catalog";
import { btnDanger, btnPrimary, btnSecondary, cardCls, errorCls, mutedCls, negCls, posCls, stackedCell } from "./ui";

type Props = {
  batch: ImportBatch;
  onConfirmed: (result: ImportBatch) => void;
  onDiscarded: () => void;
};

const TABS: ImportItemStatus[] = ["NEW", "POSSIBLE_DUPLICATE", "DUPLICATE", "INVALID"];
const COUNT: Record<ImportItemStatus, (b: ImportBatch) => number> = {
  NEW: (b) => b.newRows,
  POSSIBLE_DUPLICATE: (b) => b.possibleRows,
  DUPLICATE: (b) => b.duplicateRows,
  INVALID: (b) => b.invalidRows,
};
const PAGE = 50;

function signed(item: ImportItem): { text: string; cls: string } {
  if (item.amount === null) return { text: "—", cls: "" };
  const money = formatMoney(item.amount);
  return item.type === "INCOME" ? { text: `+${money}`, cls: posCls } : { text: `-${money}`, cls: negCls };
}

type Catalog = ReturnType<typeof useCatalog>;

/**
 * Categoria e contexto de uma linha: o que o motor decidiu (com quem decidiu e a confiança) e os seletores para trocar.
 * A escolha vira uma requisição na hora (fica na prévia) e só ensina o favorecido quando a importação é confirmada.
 */
function ClassificationCell({ batchId, item, catalog }: { batchId: string; item: ImportItem; catalog: Catalog }) {
  const queryClient = useQueryClient();
  const c = item.classification;
  const choose = useMutation({
    mutationFn: (body: { categoryId?: string | null; contextId?: string | null; reset?: boolean }) => classifyImportItem(batchId, item.id, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["import-items", batchId] }),
  });
  if (!c) return <span className={mutedCls}>—</span>;

  // o que a transação terá se nada mudar: a escolha do usuário, ou o que o motor aplicaria sozinho
  const category = c.chosen ? (c.chosenCategoryId ?? "") : c.categoryDecision === "AUTO" ? (c.categoryId ?? "") : "";
  const context = c.chosen ? (c.chosenContextId ?? "") : c.contextDecision === "AUTO" ? (c.contextId ?? "") : "";
  const suggestion = !c.chosen && c.decision === "SUGGEST";

  return (
    <div className="space-y-1.5 max-md:text-right">
      <div>
        <ClassificationBadge decision={c.chosen ? "CONFIRMED" : c.decision} source={c.chosen ? null : c.source} confidence={c.chosen ? null : c.confidence} />
      </div>
      {suggestion && (
        <p className={mutedCls}>
          Sugerido: {[catalog.categoryName(c.categoryId), catalog.contextName(c.contextId)].filter(Boolean).join(" · ")}{" "}
          <button
            type="button"
            className="font-medium text-emerald-700 underline dark:text-emerald-400"
            onClick={() => choose.mutate({ categoryId: c.categoryId, contextId: c.contextId })}
            disabled={choose.isPending}
            aria-label={`Aceitar a sugestão da linha ${item.lineNo}`}
          >
            Aceitar
          </button>
        </p>
      )}
      <div className="grid gap-1 sm:grid-cols-2">
        <CategorySelect
          compact
          categories={catalog.categories}
          label={`Categoria da linha ${item.lineNo}`}
          value={category}
          disabled={choose.isPending}
          onChange={(v) => choose.mutate({ categoryId: v || null, contextId: context || null })}
        />
        <ContextSelect
          compact
          contexts={catalog.contexts}
          label={`Contexto da linha ${item.lineNo}`}
          value={context}
          disabled={choose.isPending}
          onChange={(v) => choose.mutate({ categoryId: category || null, contextId: v || null })}
        />
      </div>
      {c.chosen && (
        <button type="button" className="text-sm text-zinc-600 underline dark:text-zinc-400" onClick={() => choose.mutate({ reset: true })} disabled={choose.isPending} aria-label={`Voltar a linha ${item.lineNo} ao que o sistema decidiu`}>
          Voltar ao que o sistema decidiu
        </button>
      )}
      {choose.error && (
        <p role="alert" className={errorCls}>
          {choose.error.message}
        </p>
      )}
    </div>
  );
}

/**
 * Revisão da prévia: o que será importado, o que já existe e o que não deu para ler. Nada é criado até confirmar. As
 * escolhas (desmarcar linhas novas, aceitar possíveis duplicatas) ficam aqui e só viram requisição ao confirmar.
 */
export function ImportReview({ batch, onConfirmed, onDiscarded }: Props) {
  const queryClient = useQueryClient();
  const catalog = useCatalog();
  const firstTab = TABS.find((s) => COUNT[s](batch) > 0) ?? "NEW";
  const [tab, setTab] = useState<ImportItemStatus>(firstTab);
  const [page, setPage] = useState(0);
  const [excluded, setExcluded] = useState<Set<string>>(new Set()); // linhas NOVAS desmarcadas
  const [included, setIncluded] = useState<Set<string>>(new Set()); // POSSÍVEIS duplicatas aceitas
  const [confirmingDiscard, setConfirmingDiscard] = useState(false);

  const itemsQ = useQuery({
    queryKey: ["import-items", batch.id, tab, page],
    queryFn: () => listImportItems(batch.id, { status: tab, page, size: PAGE }),
    placeholderData: (previous) => previous,
  });

  const confirm = useMutation({
    mutationFn: () => confirmImport(batch.id, { includePossible: [...included], exclude: [...excluded] }),
    onSuccess: async (result) => {
      await Promise.all(["imports", "accounts", "summary", "transactions"].map((k) => queryClient.invalidateQueries({ queryKey: [k] })));
      onConfirmed(result);
    },
  });
  const discard = useMutation({
    mutationFn: () => discardImport(batch.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["imports"] });
      onDiscarded();
    },
  });

  const toImport = batch.newRows - excluded.size + included.size;
  const data = itemsQ.data;
  const totalPages = Math.max(data?.totalPages ?? 1, 1);
  const selectable = tab === "NEW" || tab === "POSSIBLE_DUPLICATE";

  const isChecked = (i: ImportItem) => (tab === "NEW" ? !excluded.has(i.id) : included.has(i.id));
  function toggle(i: ImportItem, on: boolean) {
    const set = tab === "NEW" ? excluded : included;
    const next = new Set(set);
    // em "Novas", marcado = importar (some da lista de exclusão); em "Possíveis", marcado = aceitar (entra na lista)
    if (tab === "NEW" ? on : !on) next.delete(i.id);
    else next.add(i.id);
    (tab === "NEW" ? setExcluded : setIncluded)(next);
  }
  function togglePage(on: boolean) {
    const set = tab === "NEW" ? new Set(excluded) : new Set(included);
    for (const i of data?.items ?? []) {
      if (tab === "NEW" ? on : !on) set.delete(i.id);
      else set.add(i.id);
    }
    (tab === "NEW" ? setExcluded : setIncluded)(set);
  }
  const allChecked = (data?.items ?? []).length > 0 && (data?.items ?? []).every(isChecked);

  return (
    <div className="space-y-5">
      <div className={cardCls}>
        <h2 className="text-lg font-semibold">Revise antes de importar</h2>
        <p className={`mt-1 ${mutedCls}`}>
          Arquivo <strong>{batch.fileName}</strong> · {batch.totalRows} {batch.totalRows === 1 ? "linha" : "linhas"}. Nada foi criado ainda.
        </p>
        {batch.fileAlreadyImported && (
          <p role="status" className="mt-3 rounded-lg bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
            Este arquivo já foi importado nesta conta antes. As linhas repetidas aparecem como duplicadas.
          </p>
        )}
        {(batch.warnings ?? []).map((w) => (
          <p key={w} className="mt-2 text-sm text-amber-800 dark:text-amber-300">
            {w}
          </p>
        ))}
        {((batch.autoClassified ?? 0) > 0 || (batch.suggested ?? 0) > 0) && (
          <p className="mt-3 text-sm">
            Categorizadas sozinhas: <strong>{batch.autoClassified ?? 0}</strong> · Só sugeridas: <strong>{batch.suggested ?? 0}</strong>. Confira ou troque na coluna Categoria.
          </p>
        )}
        <div role="group" aria-label="Filtrar linhas" className="mt-4 flex flex-wrap gap-2">
          {TABS.map((s) => (
            <button
              key={s}
              type="button"
              aria-pressed={tab === s}
              onClick={() => {
                setTab(s);
                setPage(0);
              }}
              className={`rounded-lg border px-3 py-1.5 text-sm ${tab === s ? "border-emerald-700 bg-emerald-50 font-medium dark:bg-emerald-950" : "border-zinc-300 dark:border-zinc-700"}`}
            >
              {ITEM_STATUS[s].tab} ({COUNT[s](batch)})
            </button>
          ))}
        </div>
        <p className={`mt-3 ${mutedCls}`}>{ITEM_STATUS[tab].help}</p>
      </div>

      {itemsQ.error && <p role="alert" className={errorCls}>Não foi possível carregar as linhas.</p>}
      {data && data.items.length === 0 && <p className={`${cardCls} text-center ${mutedCls}`}>Nenhuma linha nesta aba.</p>}
      {data && data.items.length > 0 && (
        <div className={`${cardCls} relative overflow-x-auto p-0`}>
          <table className="w-full text-left text-sm max-md:block">
            <caption className="sr-only">Linhas do extrato: {ITEM_STATUS[tab].tab}</caption>
            <thead className="border-b border-zinc-200 text-zinc-600 max-md:sr-only dark:border-zinc-800 dark:text-zinc-400">
              <tr>
                {selectable && (
                  <th scope="col" className="w-10 px-3 py-2">
                    <input type="checkbox" checked={allChecked} onChange={(e) => togglePage(e.target.checked)} aria-label="Marcar todas as linhas desta página" className="size-4 accent-emerald-700" />
                  </th>
                )}
                <th scope="col" className="px-3 py-2 font-medium">Linha</th>
                <th scope="col" className="px-3 py-2 font-medium">Data</th>
                <th scope="col" className="px-3 py-2 font-medium">Descrição</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Valor</th>
                {selectable && <th scope="col" className="px-3 py-2 font-medium">Categoria</th>}
                {(tab === "DUPLICATE" || tab === "POSSIBLE_DUPLICATE") && <th scope="col" className="px-3 py-2 font-medium">Comparada com</th>}
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 max-md:block dark:divide-zinc-800">
              {data.items.map((i) => {
                const amount = signed(i);
                return (
                  <tr key={i.id} className="align-top max-md:block max-md:px-4 max-md:py-3">
                    {selectable && (
                      <td data-label="Importar" className={stackedCell}>
                        <input type="checkbox" checked={isChecked(i)} onChange={(e) => toggle(i, e.target.checked)} aria-label={`Importar a linha ${i.lineNo}`} className="size-4 accent-emerald-700" />
                      </td>
                    )}
                    <td data-label="Linha" className={`${stackedCell} tabular-nums`}>{i.lineNo}</td>
                    <td data-label="Data" className={`${stackedCell} whitespace-nowrap`}>{i.occurredAt ? formatDate(i.occurredAt) : "—"}</td>
                    <td data-label="Descrição" className={stackedCell}>
                      <div className="max-md:text-right">
                        <p className="font-medium">{i.description || i.merchant || "—"}</p>
                        {i.description && i.merchant && <p className={mutedCls}>{i.merchant}</p>}
                        {(tab === "INVALID" || tab === "DUPLICATE" || tab === "POSSIBLE_DUPLICATE") && i.reason && <p className={mutedCls}>{i.reason}</p>}
                      </div>
                    </td>
                    <td data-label="Valor" className={`${stackedCell} whitespace-nowrap font-medium tabular-nums md:text-right ${amount.cls}`}>{amount.text}</td>
                    {selectable && (
                      <td data-label="Categoria" className={`${stackedCell} md:min-w-64`}>
                        <ClassificationCell batchId={batch.id} item={i} catalog={catalog} />
                      </td>
                    )}
                    {(tab === "DUPLICATE" || tab === "POSSIBLE_DUPLICATE") && (
                      <td data-label="Comparada com" className={stackedCell}>
                        {i.existing ? (
                          <div className="max-md:text-right">
                            <p>
                              {formatDate(i.existing.occurredAt)} · {formatMoney(i.existing.amount)}
                            </p>
                            <p className={mutedCls}>
                              {i.existing.description || "sem descrição"} · {SOURCE_LABEL[i.existing.source] ?? i.existing.source}
                            </p>
                          </div>
                        ) : (
                          <span className={mutedCls}>—</span>
                        )}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {data && data.totalElements > PAGE && (
        <nav aria-label="Paginação das linhas" className="flex flex-wrap items-center justify-between gap-3">
          <p className={mutedCls}>
            Página {page + 1} de {totalPages} · {data.totalElements} linhas
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

      <div className={`${cardCls} space-y-3`}>
        <p aria-live="polite" className="font-medium">
          {toImport > 0 ? `Serão importadas ${toImport} ${toImport === 1 ? "transação" : "transações"}.` : "Nada selecionado para importar."}
        </p>
        {(confirm.error || discard.error) && (
          <p role="alert" className={errorCls}>
            {(confirm.error ?? discard.error)?.message}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => confirm.mutate()} disabled={confirm.isPending || toImport <= 0} className={btnPrimary}>
            {confirm.isPending ? "Importando…" : `Importar ${toImport} ${toImport === 1 ? "transação" : "transações"}`}
          </button>
          {confirmingDiscard ? (
            <div role="group" aria-label="Confirmar descarte da prévia" className="flex gap-2">
              <button type="button" onClick={() => discard.mutate()} disabled={discard.isPending} className={btnDanger}>
                Descartar prévia
              </button>
              <button type="button" onClick={() => setConfirmingDiscard(false)} className={btnSecondary}>
                Cancelar
              </button>
            </div>
          ) : (
            <button type="button" onClick={() => setConfirmingDiscard(true)} className={btnSecondary}>
              Descartar
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
