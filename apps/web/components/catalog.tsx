"use client";

import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { listCategories, listContexts } from "@/lib/finance";
import { CLASS_SOURCE_LABEL, percent } from "@/lib/labels";
import type { CategoryNode, ClassificationSource, ContextItem, Decision } from "@/lib/types";
import { inputCls } from "./ui";

// referências estáveis: um `?? []` novo a cada render invalidaria os useMemo
const NO_CATEGORIES: CategoryNode[] = [];
const NO_CONTEXTS: ContextItem[] = [];

/** Categorias e contextos do usuário, com busca de nome por id ("Alimentação › Restaurante"). */
export function useCatalog() {
  const categoriesQ = useQuery({ queryKey: ["categories"], queryFn: listCategories });
  const contextsQ = useQuery({ queryKey: ["contexts"], queryFn: listContexts });
  const categories = categoriesQ.data ?? NO_CATEGORIES;
  const contexts = contextsQ.data ?? NO_CONTEXTS;
  const categoryLabels = useMemo(() => {
    const map = new Map<string, string>();
    for (const c of categories) {
      map.set(c.id, c.name);
      for (const f of c.children) map.set(f.id, `${c.name} › ${f.name}`);
    }
    return map;
  }, [categories]);
  const contextLabels = useMemo(() => new Map(contexts.map((c) => [c.id, c.name])), [contexts]);
  return {
    categories,
    contexts,
    categoryName: (id: string | null | undefined) => (id ? (categoryLabels.get(id) ?? "Categoria") : ""),
    contextName: (id: string | null | undefined) => (id ? (contextLabels.get(id) ?? "Contexto") : ""),
    loading: categoriesQ.isLoading || contextsQ.isLoading,
  };
}

type SelectProps = {
  id?: string;
  label: string; // vira aria-label quando o rótulo visível está em outro lugar
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  compact?: boolean;
};

const compactCls = "mt-0 w-full rounded-md border border-zinc-300 bg-white px-2 py-1 text-sm dark:border-zinc-700 dark:bg-zinc-900";

export function CategorySelect({ categories, emptyLabel = "Sem categoria", compact, ...p }: SelectProps & { categories: CategoryNode[]; emptyLabel?: string }) {
  return (
    <select id={p.id} aria-label={p.label} value={p.value} onChange={(e) => p.onChange(e.target.value)} disabled={p.disabled} className={compact ? compactCls : inputCls}>
      <option value="">{emptyLabel}</option>
      {categories.map((c) => (
        <optgroup key={c.id} label={c.name}>
          <option value={c.id}>{c.name} (geral)</option>
          {c.children.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  );
}

export function ContextSelect({ contexts, emptyLabel = "Sem contexto", compact, ...p }: SelectProps & { contexts: ContextItem[]; emptyLabel?: string }) {
  return (
    <select id={p.id} aria-label={p.label} value={p.value} onChange={(e) => p.onChange(e.target.value)} disabled={p.disabled} className={compact ? compactCls : inputCls}>
      <option value="">{emptyLabel}</option>
      {contexts.map((c) => (
        <option key={c.id} value={c.id}>
          {c.name}
        </option>
      ))}
    </select>
  );
}

const BADGE = "inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium";

/** Selo de quem decidiu e com que confiança. O texto diz tudo (não depende só de cor). */
export function ClassificationBadge({ decision, source, confidence }: { decision: Decision | "CONFIRMED"; source: ClassificationSource | null; confidence: number | null }) {
  const by = source ? (CLASS_SOURCE_LABEL[source] ?? source) : "";
  const pct = confidence == null ? "" : ` · ${percent(confidence)}`;
  if (decision === "AUTO")
    return (
      <span className={`${BADGE} bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200`} title={`Aplicada sozinha: ${by}${pct}`}>
        Automática · {by}
        {pct}
      </span>
    );
  if (decision === "SUGGEST")
    return (
      <span className={`${BADGE} bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200`} title={`Só uma sugestão: ${by}${pct}`}>
        Sugestão · {by}
        {pct}
      </span>
    );
  if (decision === "CONFIRMED")
    return <span className={`${BADGE} bg-sky-100 text-sky-900 dark:bg-sky-950 dark:text-sky-200`}>Sua escolha</span>;
  return <span className={`${BADGE} bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300`}>Sem sugestão</span>;
}
