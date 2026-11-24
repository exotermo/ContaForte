"use client";

import { useId, useState } from "react";
import { DATE_FORMATS, DELIMITER_LABEL } from "@/lib/labels";
import type { CsvSuggestion } from "@/lib/types";
import { btnPrimary, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls } from "./ui";

type Mode = "single" | "split" | "typed";

type Props = {
  suggestion: CsvSuggestion;
  pending?: boolean;
  error?: string | null;
  onSubmit: (mapping: Record<string, string>) => void;
  onBack: () => void;
};

/** Sem acento e sem caixa: só para casar o nome sugerido pelo servidor com uma coluna do cabeçalho. */
const key = (s: string) => s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

/** Posição (0-based) da coluna que o servidor sugeriu, por "#N" ou pelo nome; "" se não houver. */
function indexOf(ref: string | undefined, headers: string[]): string {
  if (!ref) return "";
  if (ref.startsWith("#")) {
    const n = Number(ref.slice(1));
    return Number.isInteger(n) && n >= 1 ? String(n - 1) : "";
  }
  const i = headers.findIndex((h) => key(h) === key(ref));
  return i >= 0 ? String(i) : "";
}

function initialMode(m: Record<string, string>): Mode {
  if (m.debitColumn || m.creditColumn) return "split";
  if (m.typeColumn) return "typed";
  return "single";
}

/**
 * Mapeamento de colunas de um CSV. Parte do palpite do servidor (cabeçalho, delimitador e amostra detectados, só
 * exibidos) e deixa o usuário ajustar quais colunas são data, valor, descrição e identificador. As colunas vão ao
 * backend por posição (#N), que funciona também com cabeçalho repetido, vazio ou com acento.
 */
export function CsvMapper({ suggestion, pending, error, onSubmit, onBack }: Props) {
  const id = useId();
  const m = suggestion.mapping;
  const width = Math.max(suggestion.headers.length, ...suggestion.sample.map((r) => r.length), 0);
  const columns = Array.from({ length: width }, (_, i) => ({
    value: String(i),
    label: suggestion.hasHeader && suggestion.headers[i] ? `${suggestion.headers[i]}` : `Coluna ${i + 1}`,
  }));

  const [mode, setMode] = useState<Mode>(initialMode(m));
  const [date, setDate] = useState(indexOf(m.dateColumn, suggestion.headers));
  const [dateFormat, setDateFormat] = useState(m.dateFormat ?? "AUTO");
  const [amount, setAmount] = useState(indexOf(m.amountColumn, suggestion.headers));
  const [debit, setDebit] = useState(indexOf(m.debitColumn, suggestion.headers));
  const [credit, setCredit] = useState(indexOf(m.creditColumn, suggestion.headers));
  const [type, setType] = useState(indexOf(m.typeColumn, suggestion.headers));
  const [description, setDescription] = useState(indexOf(m.descriptionColumn, suggestion.headers));
  const [externalId, setExternalId] = useState(indexOf(m.idColumn, suggestion.headers));
  const [invert, setInvert] = useState(m.invertSign === "true");
  const [problem, setProblem] = useState<string | null>(null);

  const ref = (idx: string) => `#${Number(idx) + 1}`;

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const missing =
      date === "" ? "Escolha a coluna da data."
      : mode === "single" && amount === "" ? "Escolha a coluna do valor."
      : mode === "typed" && (amount === "" || type === "") ? "Escolha a coluna do valor e a do tipo (débito/crédito)."
      : mode === "split" && debit === "" && credit === "" ? "Escolha a coluna de débito e/ou a de crédito."
      : null;
    setProblem(missing);
    if (missing) return;

    const mapping: Record<string, string> = {
      delimiter: suggestion.delimiter === "\t" ? "TAB" : suggestion.delimiter,
      skipLines: String(suggestion.skipLines),
      hasHeader: String(suggestion.hasHeader),
      dateColumn: ref(date),
      dateFormat,
      invertSign: String(invert),
    };
    if (mode === "split") {
      if (debit !== "") mapping.debitColumn = ref(debit);
      if (credit !== "") mapping.creditColumn = ref(credit);
    } else {
      mapping.amountColumn = ref(amount);
      if (mode === "typed") mapping.typeColumn = ref(type);
    }
    if (description !== "") mapping.descriptionColumn = ref(description);
    if (externalId !== "") mapping.idColumn = ref(externalId);
    onSubmit(mapping);
  }

  const select = (name: string, label: string, value: string, set: (v: string) => void, optional = false, help?: string) => (
    <div>
      <label htmlFor={`${id}-${name}`} className={labelCls}>
        {label}
      </label>
      <select id={`${id}-${name}`} value={value} onChange={(e) => set(e.target.value)} className={inputCls} aria-describedby={help ? `${id}-${name}-ajuda` : undefined}>
        <option value="">{optional ? "Não usar" : "Selecione…"}</option>
        {columns.map((c) => (
          <option key={c.value} value={c.value}>
            {c.label}
          </option>
        ))}
      </select>
      {help && (
        <p id={`${id}-${name}-ajuda`} className={`mt-1 ${mutedCls}`}>
          {help}
        </p>
      )}
    </div>
  );

  return (
    <form onSubmit={submit} noValidate className="space-y-5" aria-label="Colunas do CSV">
      <div className={cardCls}>
        <h2 className="text-lg font-semibold">Como ler este CSV</h2>
        <p className={`mt-1 ${mutedCls}`}>
          Detectado: separador {DELIMITER_LABEL[suggestion.delimiter] ?? suggestion.delimiter},{" "}
          {suggestion.hasHeader ? `cabeçalho na linha ${suggestion.skipLines + 1}` : "sem cabeçalho"}. Confira a amostra e escolha o que é cada coluna.
        </p>
        {suggestion.warnings.map((w) => (
          <p key={w} className="mt-2 text-sm text-amber-800 dark:text-amber-300">
            {w}
          </p>
        ))}
        <div className="relative mt-4 overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
          <table className="w-full min-w-max text-left text-sm">
            <caption className="sr-only">Amostra das primeiras linhas do arquivo</caption>
            <thead className="bg-zinc-50 text-zinc-600 dark:bg-zinc-900 dark:text-zinc-400">
              <tr>
                {columns.map((c) => (
                  <th key={c.value} scope="col" className="px-3 py-2 font-medium">
                    {c.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {suggestion.sample.map((row, i) => (
                <tr key={i}>
                  {columns.map((c) => (
                    <td key={c.value} className="whitespace-nowrap px-3 py-2">
                      {row[Number(c.value)] ?? ""}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className={`${cardCls} grid gap-4 sm:grid-cols-2`}>
        {select("data", "Coluna da data", date, setDate)}
        <div>
          <label htmlFor={`${id}-formato`} className={labelCls}>
            Formato da data
          </label>
          <select id={`${id}-formato`} value={dateFormat} onChange={(e) => setDateFormat(e.target.value)} className={inputCls}>
            {DATE_FORMATS.map((f) => (
              <option key={f.value} value={f.value}>
                {f.label}
              </option>
            ))}
          </select>
        </div>

        <fieldset className="sm:col-span-2">
          <legend className={labelCls}>Como vem o valor</legend>
          <div className="mt-2 flex flex-col gap-2 text-sm">
            {(
              [
                ["single", "Uma coluna de valor (negativo é despesa)"],
                ["typed", "Valor sempre positivo, com uma coluna de tipo (D/C, débito/crédito)"],
                ["split", "Colunas separadas de débito e de crédito"],
              ] as const
            ).map(([value, text]) => (
              <label key={value} className="flex items-center gap-2">
                <input type="radio" name={`${id}-modo`} checked={mode === value} onChange={() => setMode(value)} className="size-4 accent-emerald-700" />
                {text}
              </label>
            ))}
          </div>
        </fieldset>

        {mode !== "split" && select("valor", "Coluna do valor", amount, setAmount)}
        {mode === "typed" && select("tipo", "Coluna do tipo (D/C)", type, setType)}
        {mode === "split" && select("debito", "Coluna de débito (saídas)", debit, setDebit, true)}
        {mode === "split" && select("credito", "Coluna de crédito (entradas)", credit, setCredit, true)}
        {select("descricao", "Coluna da descrição", description, setDescription, true, "Ajuda a achar lançamentos que você já tem.")}
        {select("id", "Coluna do identificador", externalId, setExternalId, true, "Opcional: número do documento ou do lançamento, quando existir.")}

        <label className="flex items-center gap-2 text-sm sm:col-span-2">
          <input type="checkbox" checked={invert} onChange={(e) => setInvert(e.target.checked)} className="size-4 accent-emerald-700" />
          Inverter o sinal (use quando as despesas vêm positivas, como em algumas faturas de cartão)
        </label>
      </div>

      {(problem || error) && (
        <p role="alert" className={errorCls}>
          {problem ?? error}
        </p>
      )}
      <div className="flex gap-2">
        <button type="submit" disabled={pending} className={btnPrimary}>
          {pending ? "Lendo o extrato…" : "Ler extrato"}
        </button>
        <button type="button" onClick={onBack} disabled={pending} className={btnSecondary}>
          Voltar
        </button>
      </div>
    </form>
  );
}
