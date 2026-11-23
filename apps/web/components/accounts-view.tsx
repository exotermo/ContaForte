"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { listAccounts, updateAccount } from "@/lib/finance";
import { LIQUIDITY, accountTypeLabel } from "@/lib/labels";
import { formatMoney, isNegative } from "@/lib/money";
import type { Account } from "@/lib/types";
import { AccountForm } from "./account-form";
import { btnDanger, btnPrimary, btnSecondary, cardCls, errorCls, inputCls, mutedCls, negCls } from "./ui";

function AccountRow({ account }: { account: Account }) {
  const queryClient = useQueryClient();
  const [renaming, setRenaming] = useState(false);
  const [newName, setNewName] = useState(account.name);
  const [confirmArchive, setConfirmArchive] = useState(false);

  const mutation = useMutation({
    mutationFn: (body: { name?: string; archived?: boolean }) => updateAccount(account.id, body),
    onSuccess: async () => {
      setRenaming(false);
      setConfirmArchive(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["summary"] }),
      ]);
    },
  });

  const hasBalance = account.balance !== "0.00";

  return (
    <li className="space-y-3 px-4 py-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          {renaming ? (
            <form
              className="flex items-center gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                mutation.mutate({ name: newName });
              }}
            >
              <label htmlFor={`rn-${account.id}`} className="sr-only">
                Novo nome da conta {account.name}
              </label>
              <input
                id={`rn-${account.id}`}
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                maxLength={80}
                className={`${inputCls} mt-0`}
              />
              <button type="submit" disabled={mutation.isPending} className={btnPrimary}>
                Salvar
              </button>
              <button type="button" onClick={() => { setRenaming(false); setNewName(account.name); }} className={btnSecondary}>
                Cancelar
              </button>
            </form>
          ) : (
            <p className="truncate font-medium">
              {account.name}
              {account.archived && <span className={`ml-2 rounded bg-zinc-100 px-1.5 py-0.5 text-xs dark:bg-zinc-800 ${mutedCls}`}>Arquivada</span>}
            </p>
          )}
          <p className={mutedCls}>
            {accountTypeLabel(account.type)} · {LIQUIDITY[account.liquidity].label}
          </p>
        </div>
        <p className={`font-medium tabular-nums ${isNegative(account.balance) ? negCls : ""}`}>{formatMoney(account.balance)}</p>
      </div>

      {!renaming && !confirmArchive && (
        <div className="flex gap-2">
          <button type="button" onClick={() => setRenaming(true)} className={btnSecondary} aria-label={`Renomear ${account.name}`}>
            Renomear
          </button>
          {account.archived ? (
            <button
              type="button"
              onClick={() => mutation.mutate({ archived: false })}
              disabled={mutation.isPending}
              className={btnSecondary}
              aria-label={`Reativar ${account.name}`}
            >
              Reativar
            </button>
          ) : (
            <button type="button" onClick={() => setConfirmArchive(true)} className={btnSecondary} aria-label={`Arquivar ${account.name}`}>
              Arquivar
            </button>
          )}
        </div>
      )}

      {confirmArchive && (
        <div role="alertdialog" aria-label={`Confirmar arquivamento de ${account.name}`} className="space-y-3 rounded-lg bg-amber-50 p-3 text-sm dark:bg-amber-950/40">
          <p>
            {hasBalance ? (
              <>
                Esta conta tem saldo de <strong>{formatMoney(account.balance)}</strong>. Contas arquivadas{" "}
                <strong>não entram no patrimônio nem no dinheiro disponível</strong>. Arquivar mesmo assim?
              </>
            ) : (
              <>Contas arquivadas somem das listas e não recebem novos lançamentos. Arquivar “{account.name}”?</>
            )}
          </p>
          <div className="flex gap-2">
            <button type="button" onClick={() => mutation.mutate({ archived: true })} disabled={mutation.isPending} className={btnDanger}>
              Arquivar conta
            </button>
            <button type="button" onClick={() => setConfirmArchive(false)} className={btnSecondary}>
              Cancelar
            </button>
          </div>
        </div>
      )}

      {mutation.error && (
        <p role="alert" className={errorCls}>
          {mutation.error.message}
        </p>
      )}
    </li>
  );
}

export function AccountsView() {
  const [showForm, setShowForm] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const { data, error, isPending } = useQuery({
    queryKey: ["accounts", showArchived],
    queryFn: () => listAccounts(showArchived),
  });

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">Contas</h1>
        {!showForm && (
          <button type="button" onClick={() => setShowForm(true)} className={btnPrimary}>
            Nova conta
          </button>
        )}
      </div>

      {showForm && <AccountForm onDone={() => setShowForm(false)} />}

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} className="size-4 accent-emerald-700" />
        Mostrar contas arquivadas
      </label>

      {isPending && <p className={mutedCls}>Carregando…</p>}
      {error && <p role="alert" className={errorCls}>Não foi possível carregar as contas.</p>}
      {data && data.length === 0 && (
        <p className={`${cardCls} text-center ${mutedCls}`}>
          {showArchived ? "Nenhuma conta ainda." : "Você ainda não tem contas. Cadastre a primeira para começar."}
        </p>
      )}
      {data && data.length > 0 && (
        <ul className={`${cardCls} divide-y divide-zinc-200 p-0 dark:divide-zinc-800`} aria-label="Lista de contas">
          {data.map((a) => (
            <AccountRow key={a.id} account={a} />
          ))}
        </ul>
      )}
    </div>
  );
}
