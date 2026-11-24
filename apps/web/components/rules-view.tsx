"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { createRule, deleteRule, listRules, testRule, updateRule } from "@/lib/finance";
import { CLASS_SOURCE_LABEL, MATCH_TYPES, matchTypeLabel, percent } from "@/lib/labels";
import type { MatchType, NewRule, Rule, RuleTestResult } from "@/lib/types";
import { CategorySelect, ContextSelect, useCatalog } from "./catalog";
import { btnDanger, btnPrimary, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls } from "./ui";

type Catalog = ReturnType<typeof useCatalog>;
type TypeFilter = "" | "INCOME" | "EXPENSE";

const TYPE_LABEL: Record<string, string> = { "": "Receita ou despesa", INCOME: "Só receitas", EXPENSE: "Só despesas" };
/** O servidor exige 3 letras ou números depois de normalizar; avisar antes poupa uma ida e volta. */
const significant = (s: string) => (s.match(/[\p{L}\p{N}]/gu) ?? []).length;

function RuleForm({ catalog, editing, onDone }: { catalog: Catalog; editing: Rule | null; onDone: () => void }) {
  const id = useId();
  const queryClient = useQueryClient();
  const [pattern, setPattern] = useState(editing?.pattern ?? "");
  const [matchType, setMatchType] = useState<MatchType>(editing?.matchType ?? "CONTAINS");
  const [type, setType] = useState<TypeFilter>(editing?.transactionType ?? "");
  const [category, setCategory] = useState(editing?.categoryId ?? "");
  const [context, setContext] = useState(editing?.contextId ?? "");
  const [priority, setPriority] = useState(String(editing?.priority ?? 100));
  const [error, setError] = useState("");

  const save = useMutation({
    mutationFn: () => {
      const body: NewRule = {
        matchType,
        pattern: pattern.trim(),
        ...(type ? { transactionType: type } : {}),
        ...(category ? { categoryId: category } : {}),
        ...(context ? { contextId: context } : {}),
        priority: Number(priority),
      };
      // na edição, campos limpos vão como null (o PATCH distingue ausente de nulo)
      if (editing) {
        return updateRule(editing.id, { matchType, pattern: pattern.trim(), transactionType: type || null, categoryId: category || null, contextId: context || null, priority: Number(priority) });
      }
      return createRule(body);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["rules"] });
      onDone();
    },
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const p = Number(priority);
    if (significant(pattern) < 3) return setError("O trecho precisa ter ao menos 3 letras ou números.");
    if (!category && !context) return setError("Escolha uma categoria ou um contexto.");
    if (!Number.isInteger(p) || p < 0 || p > 10000) return setError("A prioridade vai de 0 a 10000 (menor vale primeiro).");
    setError("");
    save.mutate();
  }

  return (
    <form onSubmit={submit} className={`${cardCls} space-y-4`} aria-label={editing ? "Editar regra" : "Nova regra"} noValidate>
      <h2 className="text-lg font-semibold">{editing ? "Editar regra" : "Nova regra"}</h2>
      <div className="grid gap-4 sm:grid-cols-[11rem_1fr]">
        <div>
          <label htmlFor={`${id}-cmp`} className={labelCls}>
            Comparação
          </label>
          <select id={`${id}-cmp`} value={matchType} onChange={(e) => setMatchType(e.target.value as MatchType)} className={inputCls}>
            {MATCH_TYPES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-pat`} className={labelCls}>
            Trecho da descrição
          </label>
          <input id={`${id}-pat`} value={pattern} onChange={(e) => setPattern(e.target.value)} maxLength={200} placeholder="ex.: mercadinho do ze" className={inputCls} />
        </div>
      </div>
      <p className={mutedCls}>
        Sem diferenciar maiúsculas, acentos ou pontuação, e por palavras inteiras: &ldquo;posto&rdquo; casa com &ldquo;COMPRA POSTO ABC&rdquo;, não com &ldquo;COMPOSTO&rdquo;.
      </p>
      <div className="grid gap-4 sm:grid-cols-3">
        <div>
          <label htmlFor={`${id}-tipo`} className={labelCls}>
            Vale para
          </label>
          <select id={`${id}-tipo`} value={type} onChange={(e) => setType(e.target.value as TypeFilter)} className={inputCls}>
            {Object.entries(TYPE_LABEL).map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-cat`} className={labelCls}>
            Categoria
          </label>
          <CategorySelect id={`${id}-cat`} label="Categoria" categories={catalog.categories} value={category} onChange={setCategory} />
        </div>
        <div>
          <label htmlFor={`${id}-ctx`} className={labelCls}>
            Contexto
          </label>
          <ContextSelect id={`${id}-ctx`} label="Contexto" contexts={catalog.contexts} value={context} onChange={setContext} />
        </div>
      </div>
      <div className="max-w-40">
        <label htmlFor={`${id}-prio`} className={labelCls}>
          Prioridade
        </label>
        <input id={`${id}-prio`} type="number" inputMode="numeric" min={0} max={10000} value={priority} onChange={(e) => setPriority(e.target.value)} className={inputCls} />
        <p className={mutedCls}>Menor vale primeiro.</p>
      </div>
      {(error || save.error) && (
        <p role="alert" className={errorCls}>
          {error || save.error?.message}
        </p>
      )}
      <div className="flex gap-2">
        <button type="submit" className={btnPrimary} disabled={save.isPending}>
          {save.isPending ? "Salvando…" : editing ? "Salvar regra" : "Criar regra"}
        </button>
        <button type="button" className={btnSecondary} onClick={onDone}>
          Cancelar
        </button>
      </div>
    </form>
  );
}

function RuleItem({ rule, catalog, onEdit }: { rule: Rule; catalog: Catalog; onEdit: () => void }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["rules"] });
  // valor pedido enquanto a API responde: estado local, atualizado DENTRO do evento (o React restauraria o valor antigo de um
  // checkbox controlado se a mudança só chegasse depois, pelo estado assíncrono da mutação)
  const [asked, setAsked] = useState<boolean | null>(null);
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => updateRule(rule.id, { enabled }),
    onSuccess: refresh,
    onSettled: () => setAsked(null),
  });
  const remove = useMutation({ mutationFn: () => deleteRule(rule.id), onSuccess: refresh });
  // o interruptor mostra o novo estado já ao clicar (enquanto a requisição roda); se falhar, volta ao valor do servidor
  const enabled = asked ?? rule.enabled;
  const target = [catalog.categoryName(rule.categoryId), catalog.contextName(rule.contextId)].filter(Boolean).join(" · ");

  return (
    <li className={`${cardCls} space-y-2 ${enabled ? "" : "opacity-70"}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="break-words font-medium">
            {matchTypeLabel(rule.matchType)} &ldquo;{rule.pattern}&rdquo;
          </p>
          <p className={mutedCls}>
            {TYPE_LABEL[rule.transactionType ?? ""]} → {target} · prioridade {rule.priority}
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={enabled} onChange={(e) => {
              setAsked(e.target.checked);
              toggle.mutate(e.target.checked);
            }} className="size-4 accent-emerald-700" aria-label={`Regra ${rule.pattern} ativa`} />
          Ativa
        </label>
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="button" className={btnSecondary} onClick={onEdit} aria-label={`Editar a regra ${rule.pattern}`}>
          Editar
        </button>
        {confirming ? (
          <div role="group" aria-label={`Confirmar exclusão da regra ${rule.pattern}`} className="flex gap-2">
            <button type="button" className={btnDanger} onClick={() => remove.mutate()} disabled={remove.isPending}>
              Apagar regra
            </button>
            <button type="button" className={btnSecondary} onClick={() => setConfirming(false)}>
              Cancelar
            </button>
          </div>
        ) : (
          <button type="button" className={btnSecondary} onClick={() => setConfirming(true)} aria-label={`Apagar a regra ${rule.pattern}`}>
            Apagar
          </button>
        )}
      </div>
      {confirming && <p className={mutedCls}>Apagar a regra não altera transações já classificadas.</p>}
      {(toggle.error ?? remove.error) && (
        <p role="alert" className={errorCls}>
          {(toggle.error ?? remove.error)?.message}
        </p>
      )}
    </li>
  );
}

function TestBox({ catalog }: { catalog: Catalog }) {
  const id = useId();
  const [text, setText] = useState("");
  const [type, setType] = useState<"INCOME" | "EXPENSE">("EXPENSE");
  const test = useMutation({ mutationFn: () => testRule({ description: text.trim(), type }) });
  const part = (label: string, p: RuleTestResult["category"], name: string) =>
    p.decision === "NONE" ? null : (
      <li key={label}>
        {label}: <strong>{name}</strong> ({p.source ? CLASS_SOURCE_LABEL[p.source] : ""} · {percent(p.confidence)} · {p.decision === "AUTO" ? "aplicaria sozinha" : "só sugeriria"})
      </li>
    );

  return (
    <section className={`${cardCls} space-y-3`} aria-labelledby={`${id}-t`}>
      <h2 id={`${id}-t`} className="text-lg font-semibold">
        Testar um texto
      </h2>
      <p className={mutedCls}>Veja como o sistema classificaria uma descrição agora, com suas regras, o que ele aprendeu e as regras do sistema. Nada é gravado.</p>
      <form
        className="grid gap-3 sm:grid-cols-[1fr_10rem_auto] sm:items-end"
        onSubmit={(e) => {
          e.preventDefault();
          if (text.trim()) test.mutate();
        }}
      >
        <div>
          <label htmlFor={`${id}-d`} className={labelCls}>
            Descrição
          </label>
          <input id={`${id}-d`} value={text} onChange={(e) => setText(e.target.value)} maxLength={200} placeholder="ex.: PIX ENVIADO Café da Praça" className={inputCls} />
        </div>
        <div>
          <label htmlFor={`${id}-ty`} className={labelCls}>
            Tipo
          </label>
          <select id={`${id}-ty`} value={type} onChange={(e) => setType(e.target.value as "INCOME" | "EXPENSE")} className={inputCls}>
            <option value="EXPENSE">Despesa</option>
            <option value="INCOME">Receita</option>
          </select>
        </div>
        <button type="submit" className={btnSecondary} disabled={test.isPending || !text.trim()}>
          Testar
        </button>
      </form>
      {test.data &&
        (test.data.decision === "NONE" ? (
          <p role="status">Nenhuma regra nem favorecido conhecido decide este texto: ficaria sem categoria.</p>
        ) : (
          <ul role="status" className="list-disc pl-5 text-sm">
            {part("Categoria", test.data.category, catalog.categoryName(test.data.category.id))}
            {part("Contexto", test.data.context, catalog.contextName(test.data.context.id))}
          </ul>
        ))}
      {test.error && (
        <p role="alert" className={errorCls}>
          {test.error.message}
        </p>
      )}
    </section>
  );
}

export function RulesView() {
  const catalog = useCatalog();
  const rulesQ = useQuery({ queryKey: ["rules"], queryFn: listRules });
  const [form, setForm] = useState<{ editing: Rule | null } | null>(null);
  const rules = rulesQ.data ?? [];

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Regras</h1>
          <p className={`mt-1 max-w-2xl ${mutedCls}`}>
            Uma regra diz: &ldquo;se a descrição tiver este trecho, a categoria é esta&rdquo;. Suas regras valem antes de tudo e são aplicadas sozinhas.
          </p>
        </div>
        {!form && (
          <button type="button" className={btnPrimary} onClick={() => setForm({ editing: null })}>
            Nova regra
          </button>
        )}
      </header>

      {form && <RuleForm key={form.editing?.id ?? "new"} catalog={catalog} editing={form.editing} onDone={() => setForm(null)} />}

      {rulesQ.error && (
        <p role="alert" className={errorCls}>
          Não foi possível carregar as regras.
        </p>
      )}
      {rulesQ.data && rules.length === 0 && !form && (
        <p className={`${cardCls} text-center ${mutedCls}`}>Nenhuma regra sua ainda. Crie uma, ou escolha &ldquo;aplicar sempre&rdquo; ao revisar uma transação.</p>
      )}
      {rules.length > 0 && (
        <ul className="space-y-3" aria-label="Suas regras">
          {rules.map((r) => (
            <RuleItem key={r.id} rule={r} catalog={catalog} onEdit={() => setForm({ editing: r })} />
          ))}
        </ul>
      )}

      <TestBox catalog={catalog} />
    </div>
  );
}
