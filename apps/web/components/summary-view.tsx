"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { getSummary } from "@/lib/finance";
import { LIQUIDITY, accountTypeLabel } from "@/lib/labels";
import { formatMoney, isNegative } from "@/lib/money";
import { cardCls, errorCls, mutedCls, negCls } from "./ui";

type CardProps = { testId: string; title: string; value: string; help: string; highlight?: boolean };

function Card({ testId, title, value, help, highlight }: CardProps) {
  return (
    <section
      data-testid={testId}
      aria-labelledby={`${testId}-titulo`}
      className={`${cardCls} ${highlight ? "ring-2 ring-emerald-600/30" : ""}`}
    >
      <h2 id={`${testId}-titulo`} className="text-sm font-medium text-zinc-600 dark:text-zinc-400">
        {title}
      </h2>
      <p className={`mt-1 text-2xl font-semibold tabular-nums ${isNegative(value) ? negCls : ""}`}>{formatMoney(value)}</p>
      <p className={`mt-2 ${mutedCls}`}>{help}</p>
    </section>
  );
}

export function SummaryView() {
  const { data, error, isPending } = useQuery({ queryKey: ["summary"], queryFn: getSummary });

  if (isPending) return <p className={mutedCls}>Carregando…</p>;
  if (error) return <p role="alert" className={errorCls}>Não foi possível carregar o resumo.</p>;

  if (data.accounts.length === 0) {
    return (
      <section className={`${cardCls} text-center`}>
        <h2 className="text-lg font-semibold">Cadastre sua primeira conta</h2>
        <p className={`mx-auto mt-2 max-w-md ${mutedCls}`}>
          Comece pela conta onde seu dinheiro fica (corrente, carteira, VA/VR…). Depois você registra receitas e
          despesas e acompanha tudo aqui.
        </p>
        <Link
          href="/contas"
          className="mt-4 inline-block rounded-lg bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-800 focus:outline-none focus:ring-2 focus:ring-emerald-600/50"
        >
          Cadastrar conta
        </Link>
      </section>
    );
  }

  const hasCard = data.creditCardBalance !== "0.00";
  const hasOther = data.otherBalance !== "0.00";
  return (
    <div className="space-y-8">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card testId="card-available" title="Disponível" value={data.availableCash} help={LIQUIDITY.AVAILABLE.help} />
        <Card testId="card-restricted" title="Restrito (VA/VR)" value={data.restrictedBalance} help={LIQUIDITY.RESTRICTED.help} />
        <Card testId="card-invested" title="Investido" value={data.investedAssets} help={LIQUIDITY.INVESTED.help} />
        <Card
          testId="card-net-worth"
          title="Patrimônio"
          value={data.totalNetWorth}
          help="Tudo somado: disponível, restrito, investido, dívidas e outros."
          highlight
        />
      </div>
      {(hasCard || hasOther) && (
        <div className="grid gap-4 sm:grid-cols-2">
          {hasCard && <Card testId="card-credit" title="Cartão de crédito" value={data.creditCardBalance} help={LIQUIDITY.LIABILITY.help} />}
          {hasOther && <Card testId="card-other" title="Outros" value={data.otherBalance} help={LIQUIDITY.OTHER.help} />}
        </div>
      )}

      <section aria-labelledby="contas-titulo">
        <h2 id="contas-titulo" className="mb-3 text-lg font-semibold">
          Suas contas
        </h2>
        <ul className={`${cardCls} divide-y divide-zinc-200 p-0 dark:divide-zinc-800`}>
          {data.accounts.map((a) => (
            <li key={a.id} className="flex items-center justify-between gap-4 px-4 py-3">
              <div className="min-w-0">
                <p className="truncate font-medium">{a.name}</p>
                <p className={mutedCls}>
                  {accountTypeLabel(a.type)} · {LIQUIDITY[a.liquidity].label}
                </p>
              </div>
              <p className={`shrink-0 font-medium tabular-nums ${isNegative(a.balance) ? negCls : ""}`}>{formatMoney(a.balance)}</p>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
