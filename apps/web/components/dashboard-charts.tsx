import Link from "next/link";
import { monthLabel, monthLong, share, toCents } from "@/lib/dashboard";
import { formatMoney } from "@/lib/money";
import type { MonthTotals } from "@/lib/types";
import { mutedCls } from "./ui";

type MonthPoint = MonthTotals & { month: string };

const W = 420;
const H = 210;
const PAD = { top: 12, right: 8, bottom: 28, left: 8 };

/**
 * Receitas e gastos líquidos dos últimos meses, em barras. Não depende só da cor: a legenda é em texto, cada barra
 * tem o valor num <title> e há uma tabela equivalente para leitores de tela.
 */
export function MonthlyBars({ months, selected }: { months: MonthPoint[]; selected: string }) {
  const max = Math.max(1, ...months.flatMap((m) => [toCents(m.income), toCents(m.netExpense)]));
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const slot = plotW / months.length;
  const barW = Math.min(22, slot / 3);
  const y = (cents: number) => PAD.top + plotH - (Math.max(0, cents) / max) * plotH;
  const summary = months
    .map((m) => `${monthLong(m.month)}: receitas ${formatMoney(m.income)}, gastos ${formatMoney(m.netExpense)}`)
    .join("; ");

  return (
    <figure data-testid="chart-monthly" className="space-y-2">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Receitas e gastos por mês. ${summary}`} className="mx-auto h-auto max-h-64 w-full">
        <line x1={PAD.left} x2={W - PAD.right} y1={PAD.top + plotH} y2={PAD.top + plotH} className="stroke-zinc-300 dark:stroke-zinc-700" />
        {months.map((m, i) => {
          const cx = PAD.left + slot * i + slot / 2;
          const inc = toCents(m.income);
          const exp = toCents(m.netExpense);
          return (
            <g key={m.month} data-testid={`bars-${m.month}`}>
              {m.month === selected && (
                <rect x={cx - slot / 2 + 2} y={PAD.top} width={slot - 4} height={plotH} rx={6} className="fill-zinc-100 dark:fill-zinc-800" />
              )}
              <rect x={cx - barW - 1} y={y(inc)} width={barW} height={PAD.top + plotH - y(inc)} rx={3} className="fill-emerald-600 dark:fill-emerald-500">
                <title>{`${monthLabel(m.month)}: receitas ${formatMoney(m.income)}`}</title>
              </rect>
              <rect x={cx + 1} y={y(exp)} width={barW} height={PAD.top + plotH - y(exp)} rx={3} className="fill-orange-600 dark:fill-orange-400">
                <title>{`${monthLabel(m.month)}: gastos ${formatMoney(m.netExpense)}`}</title>
              </rect>
              <text x={cx} y={H - 8} textAnchor="middle" className="fill-zinc-600 text-[13px] dark:fill-zinc-400">
                {monthLabel(m.month).split("/")[0]}
              </text>
            </g>
          );
        })}
      </svg>
      <figcaption className={`flex flex-wrap gap-4 ${mutedCls}`}>
        <span className="inline-flex items-center gap-1.5">
          <span aria-hidden className="inline-block h-3 w-3 rounded-sm bg-emerald-600 dark:bg-emerald-500" /> Receitas
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span aria-hidden className="inline-block h-3 w-3 rounded-sm bg-orange-600 dark:bg-orange-400" /> Gastos (já sem estornos)
        </span>
      </figcaption>
      <table className="sr-only">
        <caption>Receitas e gastos dos últimos meses</caption>
        <thead>
          <tr><th>Mês</th><th>Receitas</th><th>Gastos</th><th>Sobrou</th></tr>
        </thead>
        <tbody>
          {months.map((m) => (
            <tr key={m.month}>
              <th scope="row">{monthLong(m.month)}</th>
              <td>{formatMoney(m.income)}</td>
              <td>{formatMoney(m.netExpense)}</td>
              <td>{formatMoney(m.net)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

export type ShareRow = {
  key: string;
  label: string;
  total: string;
  href?: string;
  children?: { key: string; label: string; total: string }[];
};

/** Distribuição de gastos em barras horizontais com valor e porcentagem em texto; categorias abrem as subcategorias. */
export function ShareList({ testId, rows, total, emptyText }: { testId: string; rows: ShareRow[]; total: string; emptyText: string }) {
  if (rows.length === 0) return <p className={mutedCls}>{emptyText}</p>;
  return (
    <ul data-testid={testId} className="space-y-3">
      {rows.map((r) => {
        const pct = share(r.total, total);
        const head = (
          <>
            <span className="flex items-baseline justify-between gap-3">
              <span className="min-w-0 truncate font-medium">{r.label}</span>
              <span className="shrink-0 tabular-nums">
                {formatMoney(r.total)} <span className={mutedCls}>· {pct}%</span>
              </span>
            </span>
            <span aria-hidden className="mt-1 block h-2 overflow-hidden rounded-full bg-zinc-200 dark:bg-zinc-800">
              <span className="block h-full rounded-full bg-emerald-600 dark:bg-emerald-500" style={{ width: `${Math.max(pct, 1)}%` }} />
            </span>
          </>
        );
        return (
          <li key={r.key}>
            {r.children && r.children.length > 0 ? (
              <details>
                <summary className="cursor-pointer list-none rounded focus:outline-none focus:ring-2 focus:ring-emerald-600/40">{head}</summary>
                <ul className="mt-2 space-y-1 border-l-2 border-zinc-200 pl-3 text-sm dark:border-zinc-800">
                  {r.children.map((c) => (
                    <li key={c.key} className="flex justify-between gap-3">
                      <span className="min-w-0 truncate">{c.label}</span>
                      <span className="shrink-0 tabular-nums">{formatMoney(c.total)}</span>
                    </li>
                  ))}
                </ul>
                {r.href && (
                  <Link href={r.href} className="mt-2 inline-block text-sm text-emerald-700 underline dark:text-emerald-400">
                    Ver lançamentos<span className="sr-only"> de {r.label}</span>
                  </Link>
                )}
              </details>
            ) : (
              <div>
                {head}
                {r.href && (
                  <Link href={r.href} className="mt-1 inline-block text-sm text-emerald-700 underline dark:text-emerald-400">
                    Ver lançamentos<span className="sr-only"> de {r.label}</span>
                  </Link>
                )}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
