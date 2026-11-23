"use client";

import { inputCls, errorCls, labelCls, mutedCls } from "./ui";

type Props = {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string | null;
  hint?: string;
};

/** Campo de valor em reais. Guarda o texto digitado; a conversão para decimal é feita por parseMoney no envio. */
export function MoneyField({ id, label, value, onChange, error, hint }: Props) {
  const describedBy = [error ? `${id}-erro` : null, hint ? `${id}-dica` : null].filter(Boolean).join(" ") || undefined;
  return (
    <div>
      <label htmlFor={id} className={labelCls}>
        {label}
      </label>
      <input
        id={id}
        name={id}
        type="text"
        inputMode="decimal"
        autoComplete="off"
        placeholder="0,00"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={inputCls}
      />
      {hint && (
        <p id={`${id}-dica`} className={`mt-1 ${mutedCls}`}>
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-erro`} role="alert" className={`mt-1 ${errorCls}`}>
          {error}
        </p>
      )}
    </div>
  );
}
