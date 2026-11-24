"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useId, useState } from "react";
import { localInputToIso, nowLocalInput } from "@/lib/datetime";
import { createTransaction, testRule } from "@/lib/finance";
import { CLASS_SOURCE_LABEL, TRANSACTION_TYPES, hasCounterpart, percent } from "@/lib/labels";
import { parseMoney } from "@/lib/money";
import type { Account, CategoryNode, ContextItem, NewTransaction, TransactionType } from "@/lib/types";
import { MoneyField } from "./money-field";
import { btnPrimary, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls } from "./ui";

type Props = {
  accounts: Account[]; // só as ativas
  categories: CategoryNode[];
  contexts: ContextItem[];
  onDone?: () => void;
};

type Errors = Partial<Record<"account" | "counterpart" | "amount" | "date", string>>;

export function TransactionForm({ accounts, categories, contexts, onDone }: Props) {
  const queryClient = useQueryClient();
  const id = useId();
  const [type, setType] = useState<TransactionType>("EXPENSE");
  const [accountId, setAccountId] = useState(accounts[0]?.id ?? "");
  const [counterpartId, setCounterpartId] = useState("");
  const [amount, setAmount] = useState("");
  const [when, setWhen] = useState(() => nowLocalInput());
  const [description, setDescription] = useState("");
  const [merchant, setMerchant] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [contextId, setContextId] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [saved, setSaved] = useState(false);

  // sugestão de categoria enquanto a descrição é digitada (só receita e despesa, só com a categoria ainda vazia)
  const [typed, setTyped] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setTyped(description.trim()), 500);
    return () => clearTimeout(timer);
  }, [description]);
  const suggestable = (type === "EXPENSE" || type === "INCOME") && typed.length >= 3 && !categoryId;
  const suggestionQ = useQuery({
    queryKey: ["category-suggestion", type, typed],
    queryFn: () => testRule({ description: typed, type: type as "INCOME" | "EXPENSE" }),
    enabled: suggestable,
    staleTime: 60_000,
    retry: false,
  });
  const suggestion = suggestable && suggestionQ.data && suggestionQ.data.category.id ? suggestionQ.data : null;
  const suggestedLabel = suggestion ? (categories.flatMap((c) => [c, ...c.children]).find((c) => c.id === suggestion.category.id)?.name ?? "") : "";

  const mutation = useMutation({
    mutationFn: createTransaction,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["transactions"] }),
        queryClient.invalidateQueries({ queryKey: ["accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["summary"] }),
      ]);
      // mantém tipo, conta e data para lançar vários seguidos
      setAmount("");
      setDescription("");
      setMerchant("");
      setSaved(true);
    },
  });

  if (accounts.length === 0) {
    return (
      <p className={`${cardCls} ${mutedCls}`}>
        Cadastre uma conta antes de lançar transações.{" "}
        <Link href="/contas" className="font-medium text-emerald-700 underline dark:text-emerald-400">
          Ir para Contas
        </Link>
      </p>
    );
  }

  const needsCounterpart = hasCounterpart(type);
  // investimento só vai para conta de investimentos; em qualquer caso o destino difere da origem
  const destinations = accounts.filter((a) => a.id !== accountId && (type !== "INVESTMENT" || a.type === "INVESTMENT_ACCOUNT"));
  const typeHelp = TRANSACTION_TYPES.find((t) => t.value === type)!.help;

  function changeType(next: TransactionType) {
    setType(next);
    setCounterpartId("");
    setSaved(false);
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaved(false);
    const next: Errors = {};
    if (!accountId) next.account = "Escolha a conta.";
    if (needsCounterpart && !counterpartId) {
      next.counterpart =
        type === "INVESTMENT" && destinations.length === 0
          ? "Cadastre uma conta de investimentos para receber o aporte."
          : "Escolha a conta de destino.";
    }
    const parsed = parseMoney(amount);
    if (!parsed.ok) next.amount = parsed.error;
    const iso = localInputToIso(when);
    if (!iso) next.date = "Informe a data e a hora.";
    setErrors(next);
    if (Object.keys(next).length > 0 || !parsed.ok || !iso) return;

    const body: NewTransaction = { type, accountId, amount: parsed.value, occurredAt: iso };
    if (needsCounterpart) body.counterpartAccountId = counterpartId;
    if (description.trim()) body.description = description.trim();
    if (merchant.trim()) body.merchant = merchant.trim();
    if (categoryId) body.categoryId = categoryId;
    if (contextId) body.contextId = contextId;
    mutation.mutate(body);
  }

  const fieldError = (key: keyof Errors) =>
    errors[key] ? (
      <p id={`${id}-${key}-erro`} role="alert" className={`mt-1 ${errorCls}`}>
        {errors[key]}
      </p>
    ) : null;
  const invalid = (key: keyof Errors) => ({
    "aria-invalid": errors[key] ? (true as const) : undefined,
    "aria-describedby": errors[key] ? `${id}-${key}-erro` : undefined,
  });

  return (
    <form onSubmit={onSubmit} noValidate className={`${cardCls} space-y-4`} aria-label="Nova transação">
      <fieldset>
        <legend className={labelCls}>Tipo</legend>
        <div className="mt-2 flex flex-wrap gap-2">
          {TRANSACTION_TYPES.map((t) => (
            <label
              key={t.value}
              className={`cursor-pointer rounded-lg border px-3 py-1.5 text-sm has-[:checked]:border-emerald-700 has-[:checked]:bg-emerald-50 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-emerald-600/50 dark:border-zinc-700 dark:has-[:checked]:bg-emerald-950 ${
                type === t.value ? "border-emerald-700 font-medium" : "border-zinc-300"
              }`}
            >
              <input
                type="radio"
                name={`${id}-tipo`}
                value={t.value}
                checked={type === t.value}
                onChange={() => changeType(t.value)}
                className="sr-only"
              />
              {t.label}
            </label>
          ))}
        </div>
        <p aria-live="polite" className={`mt-2 ${mutedCls}`}>
          {typeHelp}
        </p>
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor={`${id}-conta`} className={labelCls}>
            {needsCounterpart ? "Conta de origem" : "Conta"}
          </label>
          <select
            id={`${id}-conta`}
            value={accountId}
            onChange={(e) => {
              setAccountId(e.target.value);
              if (e.target.value === counterpartId) setCounterpartId("");
            }}
            className={inputCls}
            {...invalid("account")}
          >
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
          {fieldError("account")}
        </div>
        {needsCounterpart && (
          <div>
            <label htmlFor={`${id}-destino`} className={labelCls}>
              Conta de destino
            </label>
            <select
              id={`${id}-destino`}
              value={counterpartId}
              onChange={(e) => setCounterpartId(e.target.value)}
              className={inputCls}
              {...invalid("counterpart")}
            >
              <option value="">Selecione…</option>
              {destinations.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
            {fieldError("counterpart")}
          </div>
        )}
        <MoneyField id={`${id}-valor`} label="Valor (R$)" value={amount} onChange={setAmount} error={errors.amount} />
        <div>
          <label htmlFor={`${id}-data`} className={labelCls}>
            Data e hora
          </label>
          <input
            id={`${id}-data`}
            type="datetime-local"
            value={when}
            onChange={(e) => setWhen(e.target.value)}
            className={inputCls}
            {...invalid("date")}
          />
          {fieldError("date")}
        </div>
        <div>
          <label htmlFor={`${id}-descricao`} className={labelCls}>
            Descrição
          </label>
          <input id={`${id}-descricao`} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={200} className={inputCls} />
        </div>
        {suggestion && suggestedLabel && (
          <p className="text-sm sm:col-span-2" role="status">
            Sugestão de categoria: <strong>{suggestedLabel}</strong>{" "}
            <span className={mutedCls}>
              ({suggestion.category.source ? CLASS_SOURCE_LABEL[suggestion.category.source] : ""} · {percent(suggestion.category.confidence)})
            </span>{" "}
            <button
              type="button"
              className="font-medium text-emerald-700 underline dark:text-emerald-400"
              onClick={() => {
                setCategoryId(suggestion.category.id as string);
                if (suggestion.context.id && !contextId) setContextId(suggestion.context.id);
              }}
            >
              Usar
            </button>
          </p>
        )}
        <div>
          <label htmlFor={`${id}-estab`} className={labelCls}>
            Estabelecimento
          </label>
          <input id={`${id}-estab`} value={merchant} onChange={(e) => setMerchant(e.target.value)} maxLength={120} className={inputCls} />
        </div>
        <div>
          <label htmlFor={`${id}-categoria`} className={labelCls}>
            Categoria
          </label>
          <select id={`${id}-categoria`} value={categoryId} onChange={(e) => setCategoryId(e.target.value)} className={inputCls}>
            <option value="">Sem categoria</option>
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
        </div>
        <div>
          <label htmlFor={`${id}-contexto`} className={labelCls}>
            Contexto
          </label>
          <select id={`${id}-contexto`} value={contextId} onChange={(e) => setContextId(e.target.value)} className={inputCls}>
            <option value="">Sem contexto</option>
            {contexts.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      {mutation.error && (
        <p role="alert" className={errorCls}>
          {mutation.error.message}
        </p>
      )}
      {saved && (
        <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">
          Transação registrada.
        </p>
      )}
      <div className="flex gap-2">
        <button type="submit" disabled={mutation.isPending} className={btnPrimary}>
          {mutation.isPending ? "Salvando…" : "Registrar"}
        </button>
        {onDone && (
          <button type="button" onClick={onDone} className={btnSecondary}>
            Fechar
          </button>
        )}
      </div>
    </form>
  );
}
