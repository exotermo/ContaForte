"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { createAccount } from "@/lib/finance";
import { ACCOUNT_TYPES, LIQUIDITY } from "@/lib/labels";
import { parseMoney } from "@/lib/money";
import type { AccountType } from "@/lib/types";
import { MoneyField } from "./money-field";
import { btnPrimary, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls } from "./ui";

export function AccountForm({ onDone }: { onDone?: () => void }) {
  const queryClient = useQueryClient();
  const id = useId();
  const [name, setName] = useState("");
  const [type, setType] = useState<AccountType>("CHECKING_ACCOUNT");
  const [opening, setOpening] = useState("");
  const [nameError, setNameError] = useState<string | null>(null);
  const [openingError, setOpeningError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: createAccount,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["summary"] }),
      ]);
      setName("");
      setOpening("");
      onDone?.();
    },
  });

  const selected = ACCOUNT_TYPES.find((t) => t.value === type)!;

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    const nameMsg = trimmed ? null : "Informe o nome da conta.";
    // saldo inicial pode ser zero (padrão) ou negativo (dívida de cartão)
    const parsed = opening.trim() === "" ? ({ ok: true, value: "0.00" } as const) : parseMoney(opening, { allowNegative: true, allowZero: true });
    setNameError(nameMsg);
    setOpeningError(parsed.ok ? null : parsed.error);
    if (nameMsg || !parsed.ok) return;
    mutation.mutate({ name: trimmed, type, openingBalance: parsed.value });
  }

  return (
    <form onSubmit={onSubmit} noValidate className={`${cardCls} space-y-4`} aria-label="Nova conta">
      <div>
        <label htmlFor={`${id}-nome`} className={labelCls}>
          Nome da conta
        </label>
        <input
          id={`${id}-nome`}
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={80}
          autoComplete="off"
          aria-invalid={nameError ? true : undefined}
          aria-describedby={nameError ? `${id}-nome-erro` : undefined}
          className={inputCls}
        />
        {nameError && (
          <p id={`${id}-nome-erro`} role="alert" className={`mt-1 ${errorCls}`}>
            {nameError}
          </p>
        )}
      </div>
      <div>
        <label htmlFor={`${id}-tipo`} className={labelCls}>
          Tipo
        </label>
        <select
          id={`${id}-tipo`}
          value={type}
          onChange={(e) => setType(e.target.value as AccountType)}
          aria-describedby={`${id}-tipo-ajuda`}
          className={inputCls}
        >
          {ACCOUNT_TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
        <p id={`${id}-tipo-ajuda`} aria-live="polite" className={`mt-1 ${mutedCls}`}>
          <strong>{LIQUIDITY[selected.liquidity].label}:</strong> {LIQUIDITY[selected.liquidity].help}
        </p>
      </div>
      <MoneyField
        id={`${id}-saldo`}
        label="Saldo inicial (R$)"
        value={opening}
        onChange={setOpening}
        error={openingError}
        hint="Opcional. Pode ser negativo para dívidas, como a fatura aberta de um cartão."
      />
      {mutation.error && (
        <p role="alert" className={errorCls}>
          {mutation.error.message}
        </p>
      )}
      <div className="flex gap-2">
        <button type="submit" disabled={mutation.isPending} className={btnPrimary}>
          {mutation.isPending ? "Salvando…" : "Salvar conta"}
        </button>
        {onDone && (
          <button type="button" onClick={onDone} className={btnSecondary}>
            Cancelar
          </button>
        )}
      </div>
    </form>
  );
}
