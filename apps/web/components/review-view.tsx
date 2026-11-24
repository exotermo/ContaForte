"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate } from "@/lib/datetime";
import { decideClassification, reclassify, reclassifyPreview, reviewCount, reviewQueue } from "@/lib/finance";
import { MATCH_TYPES } from "@/lib/labels";
import { formatMoney } from "@/lib/money";
import type { MatchType, ReclassifyOutcome, ReclassifyPreview, Transaction } from "@/lib/types";
import { CategorySelect, ClassificationBadge, ContextSelect, useCatalog } from "./catalog";
import { btnPrimary, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls, negCls, posCls } from "./ui";

type Catalog = ReturnType<typeof useCatalog>;
const PAGE = 20;

/** Depois de decidir, tudo que mostra categorias, sugestões ou o que foi aprendido precisa recarregar. */
function useRefreshAfterDecision() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all(["review", "review-count", "transactions", "recipients", "rules", "summary"].map((k) => queryClient.invalidateQueries({ queryKey: [k] })));
}

function ReviewRow({ t, catalog }: { t: Transaction; catalog: Catalog }) {
  const refresh = useRefreshAfterDecision();
  const suggested = t.classificationStatus === "SUGGESTED";
  const [editing, setEditing] = useState(false);
  const [category, setCategory] = useState(t.suggestedCategoryId ?? t.categoryId ?? "");
  const [context, setContext] = useState(t.suggestedContextId ?? t.contextId ?? "");
  const [makeRule, setMakeRule] = useState(false);
  const [pattern, setPattern] = useState(t.description ?? "");
  const [matchType, setMatchType] = useState<MatchType>("CONTAINS");

  const decide = useMutation({
    mutationFn: (body: Parameters<typeof decideClassification>[1]) => decideClassification(t.id, body),
    onSuccess: refresh,
  });

  function save() {
    decide.mutate({
      action: "CORRECT",
      ...(category ? { categoryId: category } : {}),
      ...(context ? { contextId: context } : {}),
      ...(makeRule && pattern.trim() ? { createRule: { matchType, pattern: pattern.trim() } } : {}),
    });
  }

  const money = formatMoney(t.amount);
  const sign = t.type === "INCOME" ? { text: `+${money}`, cls: posCls } : { text: `-${money}`, cls: negCls };
  const label = t.description || t.merchant || "Sem descrição";

  return (
    <li className={`${cardCls} space-y-3`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="break-words font-medium">{label}</p>
          <p className={mutedCls}>{formatDate(t.occurredAt)}</p>
        </div>
        <p className={`font-medium tabular-nums ${sign.cls}`}>{sign.text}</p>
      </div>

      {suggested ? (
        <p className="text-sm">
          <ClassificationBadge decision="SUGGEST" source={t.classificationSource} confidence={t.classificationConfidence} />{" "}
          <span>{[catalog.categoryName(t.suggestedCategoryId), catalog.contextName(t.suggestedContextId)].filter(Boolean).join(" · ")}</span>
        </p>
      ) : (
        <p className={mutedCls}>Sem categoria e sem sugestão.</p>
      )}

      {editing ? (
        <div className="space-y-3 rounded-lg bg-zinc-50 p-3 dark:bg-zinc-800/50">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className={labelCls}>
              Categoria
              <CategorySelect categories={catalog.categories} label={`Categoria de ${label}`} value={category} onChange={setCategory} />
            </label>
            <label className={labelCls}>
              Contexto
              <ContextSelect contexts={catalog.contexts} label={`Contexto de ${label}`} value={context} onChange={setContext} />
            </label>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={makeRule} onChange={(e) => setMakeRule(e.target.checked)} className="size-4 accent-emerald-700" />
            Aplicar sempre que a descrição tiver este trecho
          </label>
          {makeRule && (
            <div className="grid gap-3 sm:grid-cols-[10rem_1fr]">
              <label className={labelCls}>
                Comparação
                <select value={matchType} onChange={(e) => setMatchType(e.target.value as MatchType)} className={inputCls}>
                  {MATCH_TYPES.map((m) => (
                    <option key={m.value} value={m.value}>
                      {m.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className={labelCls}>
                Trecho da descrição
                <input value={pattern} onChange={(e) => setPattern(e.target.value)} maxLength={200} className={inputCls} />
              </label>
            </div>
          )}
          <div className="flex flex-wrap gap-2">
            <button type="button" className={btnPrimary} onClick={save} disabled={decide.isPending || (!category && !context)}>
              {decide.isPending ? "Salvando…" : "Salvar escolha"}
            </button>
            <button type="button" className={btnSecondary} onClick={() => setEditing(false)}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          {suggested && (
            <button type="button" className={btnPrimary} onClick={() => decide.mutate({ action: "CONFIRM" })} disabled={decide.isPending} aria-label={`Confirmar a sugestão de ${label}`}>
              Confirmar sugestão
            </button>
          )}
          <button type="button" className={btnSecondary} onClick={() => setEditing(true)} aria-label={`Escolher a categoria de ${label}`}>
            Escolher…
          </button>
        </div>
      )}
      {decide.error && (
        <p role="alert" className={errorCls}>
          {decide.error.message}
        </p>
      )}
    </li>
  );
}

/** Reclassificar o que está sem categoria, em duas fases: primeiro a prévia, depois aplicar o que foi visto. */
function ReclassifyPanel() {
  const refresh = useRefreshAfterDecision();
  const [preview, setPreview] = useState<ReclassifyPreview | null>(null);
  const [done, setDone] = useState<ReclassifyOutcome | null>(null);
  const previewM = useMutation({ mutationFn: reclassifyPreview, onSuccess: (p) => { setPreview(p); setDone(null); } });
  const applyM = useMutation({
    mutationFn: (p: ReclassifyPreview) => reclassify({ expectedAuto: p.auto, expectedSuggest: p.suggest }),
    onSuccess: async (r) => {
      setDone(r);
      setPreview(null);
      await refresh();
    },
  });
  const error = previewM.error ?? applyM.error;

  return (
    <section className={`${cardCls} space-y-3`} aria-labelledby="reclassificar">
      <h2 id="reclassificar" className="text-lg font-semibold">
        Categorizar o que está sem categoria
      </h2>
      <p className={mutedCls}>Usa suas regras, o que o sistema aprendeu e as regras do sistema. Você vê o resultado antes e nada que você já escolheu é alterado.</p>
      {!preview && (
        <button type="button" className={btnSecondary} onClick={() => previewM.mutate()} disabled={previewM.isPending}>
          {previewM.isPending ? "Calculando…" : "Ver o que seria categorizado"}
        </button>
      )}
      {preview && (
        <div className="space-y-3">
          <p aria-live="polite">
            Entre {preview.eligible} {preview.eligible === 1 ? "transação" : "transações"}: <strong>{preview.auto}</strong> seriam categorizadas sozinhas,{" "}
            <strong>{preview.suggest}</strong> ficariam como sugestão e <strong>{preview.none}</strong> continuariam sem sugestão.
            {preview.truncated && " Há mais transações do que cabe numa rodada: rode de novo depois."}
          </p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className={btnPrimary} onClick={() => applyM.mutate(preview)} disabled={applyM.isPending || preview.auto + preview.suggest === 0}>
              {applyM.isPending ? "Aplicando…" : "Aplicar"}
            </button>
            <button type="button" className={btnSecondary} onClick={() => setPreview(null)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
      {done && (
        <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">
          Pronto: {done.auto} categorizadas sozinhas e {done.suggest} sugeridas.
        </p>
      )}
      {error && (
        <p role="alert" className={errorCls}>
          {error.message}
        </p>
      )}
    </section>
  );
}

export function ReviewView() {
  const catalog = useCatalog();
  const [page, setPage] = useState(0);
  const countQ = useQuery({ queryKey: ["review-count"], queryFn: reviewCount });
  const queueQ = useQuery({ queryKey: ["review", page], queryFn: () => reviewQueue(page, PAGE), placeholderData: (previous) => previous });
  const data = queueQ.data;
  const totalPages = Math.max(data?.totalPages ?? 1, 1);

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">Revisão</h1>
        <p className={`mt-1 ${mutedCls}`}>
          Confirme as sugestões e escolha a categoria do que ficou sem. Cada escolha sua ensina o sistema: quem você já classificou vem certo na próxima vez.
        </p>
        {countQ.data && (
          <p className="mt-2 text-sm" aria-live="polite">
            <strong>{countQ.data.suggested}</strong> {countQ.data.suggested === 1 ? "sugestão aguarda" : "sugestões aguardam"} confirmação e{" "}
            <strong>{countQ.data.uncategorized}</strong> {countQ.data.uncategorized === 1 ? "transação está" : "transações estão"} sem categoria.
          </p>
        )}
      </header>

      <ReclassifyPanel />

      {queueQ.error && (
        <p role="alert" className={errorCls}>
          Não foi possível carregar a fila.
        </p>
      )}
      {data && data.items.length === 0 && <p className={`${cardCls} text-center ${mutedCls}`}>Nada para revisar. Tudo classificado.</p>}
      {data && data.items.length > 0 && (
        <ul className="space-y-3" aria-label="Transações para revisar">
          {data.items.map((t) => (
            <ReviewRow key={`${t.id}-${t.classificationStatus}`} t={t} catalog={catalog} />
          ))}
        </ul>
      )}
      {data && data.totalElements > PAGE && (
        <nav aria-label="Paginação da fila" className="flex flex-wrap items-center justify-between gap-3">
          <p className={mutedCls}>
            Página {page + 1} de {totalPages} · {data.totalElements} transações
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
