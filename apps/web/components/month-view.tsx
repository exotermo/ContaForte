"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { currentMonth, diff, monthLong, monthRange, shiftMonth } from "@/lib/dashboard";
import { getHistory, getMonthly } from "@/lib/finance";
import { formatMoney, isNegative } from "@/lib/money";
import { MonthlyBars, ShareList, type ShareRow } from "./dashboard-charts";
import { btnSecondary, cardCls, errorCls, mutedCls, negCls, posCls } from "./ui";

type StatProps = { testId: string; title: string; value: string; previous?: string; goodWhenUp: boolean; help: string };

/** Valor do mês e a diferença para o mês anterior (verde = melhor, laranja = pior; sempre com texto, nunca só cor). */
function Stat({ testId, title, value, previous, goodWhenUp, help }: StatProps) {
  const change = previous === undefined ? null : diff(value, previous);
  const up = change !== null && !change.startsWith("-") && change !== "0.00";
  const down = change !== null && change.startsWith("-");
  const good = (up && goodWhenUp) || (down && !goodWhenUp);
  return (
    <section data-testid={testId} aria-labelledby={`${testId}-t`} className={cardCls}>
      <h3 id={`${testId}-t`} className="text-sm font-medium text-zinc-600 dark:text-zinc-400">{title}</h3>
      <p className={`mt-1 text-xl font-semibold tabular-nums ${isNegative(value) ? negCls : ""}`}>{formatMoney(value)}</p>
      <p className={`mt-1 text-sm ${change === null || (!up && !down) ? "text-zinc-600 dark:text-zinc-400" : good ? posCls : "text-orange-700 dark:text-orange-400"}`}>
        {change === null ? help : !up && !down ? "Igual ao mês anterior" : `${up ? "▲ +" : "▼ "}${formatMoney(up ? change : change)} vs mês anterior`}
      </p>
    </section>
  );
}

export function MonthView() {
  const now = currentMonth();
  const [month, setMonth] = useState(now);
  const monthly = useQuery({ queryKey: ["monthly", month], queryFn: () => getMonthly(month) });
  const history = useQuery({ queryKey: ["history", month], queryFn: () => getHistory(month, 6) });

  const range = monthRange(month);
  const drill = (extra: Record<string, string>) => `/transacoes?${new URLSearchParams({ type: "EXPENSE", ...range, ...extra })}`;

  const data = monthly.data;
  const months = history.data?.months ?? [];
  const previous = months.length >= 2 ? months[months.length - 2] : undefined;
  const empty = data !== undefined && data.income === "0.00" && data.expense === "0.00" && data.refunds === "0.00" && data.invested === "0.00";

  const categories: ShareRow[] = (data?.byCategory ?? []).map((c) => ({
    key: c.categoryId ?? "sem-categoria",
    label: c.name,
    total: c.total,
    href: c.categoryId ? drill({ categoryId: c.categoryId }) : undefined,
    children: c.children.map((k) => ({ key: k.categoryId, label: k.name, total: k.total })),
  }));
  const contexts: ShareRow[] = (data?.byContext ?? []).map((c) => ({
    key: c.contextId ?? "sem-contexto",
    label: c.name,
    total: c.total,
    href: c.contextId ? drill({ contextId: c.contextId }) : undefined,
  }));

  return (
    <section aria-labelledby="mes-titulo" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="mes-titulo" className="text-lg font-semibold">
          Movimentação de <span data-testid="month-label" className="inline-block first-letter:uppercase">{monthLong(month)}</span>
        </h2>
        <div className="flex gap-2">
          <button type="button" className={btnSecondary} onClick={() => setMonth(shiftMonth(month, -1))}>
            Mês anterior
          </button>
          <button type="button" className={btnSecondary} disabled={month >= now} onClick={() => setMonth(shiftMonth(month, 1))}>
            Próximo mês
          </button>
        </div>
      </div>

      {monthly.isPending && <p className={mutedCls}>Carregando…</p>}
      {monthly.error && <p role="alert" className={errorCls}>Não foi possível carregar a movimentação do mês.</p>}

      {data && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Stat testId="stat-income" title="Receitas" value={data.income} previous={previous?.income} goodWhenUp help="Dinheiro que entrou." />
            <Stat testId="stat-expense" title="Gastos" value={data.netExpense} previous={previous?.netExpense} goodWhenUp={false} help="Despesas menos estornos." />
            <Stat testId="stat-net" title="Sobrou no mês" value={data.net} previous={previous?.net} goodWhenUp help="Receitas menos gastos." />
            <Stat testId="stat-invested" title="Investido" value={data.invested} previous={previous?.invested} goodWhenUp help="Enviado a contas de investimento." />
          </div>
          <p className={mutedCls}>
            Transferências entre suas contas e pagamentos de fatura não contam como receita nem como gasto. VA e VR contam como
            gasto quando você os usa, mas nunca entram no caixa disponível.
          </p>

          {empty ? (
            <p data-testid="month-empty" className={`${cardCls} text-center ${mutedCls}`}>
              Nenhuma movimentação em {monthLong(month)}. Lance uma transação ou importe um extrato para ver o mês aqui.
            </p>
          ) : (
            <>
              {history.data && (
                <div className={cardCls}>
                  <h3 className="mb-2 font-semibold">Últimos 6 meses</h3>
                  <MonthlyBars months={history.data.months} selected={month} />
                </div>
              )}
              <div className="grid gap-4 lg:grid-cols-2">
                <div className={cardCls}>
                  <h3 className="mb-3 font-semibold">Gastos por categoria</h3>
                  <ShareList testId="share-category" rows={categories} total={data.netExpense} emptyText="Nenhum gasto neste mês." />
                </div>
                <div className={cardCls}>
                  <h3 className="mb-3 font-semibold">Gastos por contexto</h3>
                  <ShareList testId="share-context" rows={contexts} total={data.netExpense} emptyText="Nenhum gasto neste mês." />
                </div>
              </div>
            </>
          )}
        </>
      )}
      {history.error && <p role="alert" className={errorCls}>Não foi possível carregar o histórico.</p>}
    </section>
  );
}
