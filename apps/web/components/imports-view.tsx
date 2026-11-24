"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useMemo, useState } from "react";
import { formatDate } from "@/lib/datetime";
import { discardImport, listAccounts, listImports, rollbackImport } from "@/lib/finance";
import { IMPORT_STATUS } from "@/lib/labels";
import type { Account, ImportBatch } from "@/lib/types";
import { ImportWizard } from "./import-wizard";
import { btnDanger, btnPrimary, btnSecondary, cardCls, errorCls, mutedCls } from "./ui";

const NO_ACCOUNTS: Account[] = [];

function counts(b: ImportBatch): string {
  if (b.status === "PREVIEW") {
    return `${b.newRows} novas · ${b.duplicateRows} duplicadas · ${b.possibleRows} possíveis · ${b.invalidRows} inválidas`;
  }
  return `${b.importedRows} ${b.importedRows === 1 ? "transação" : "transações"} · ${b.totalRows} ${b.totalRows === 1 ? "linha" : "linhas"} no arquivo`;
}

function Row({ b, accountName, onResume }: { b: ImportBatch; accountName: string; onResume: (id: string) => void }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState<"undo" | "discard" | null>(null);
  const refresh = () => Promise.all(["imports", "accounts", "summary", "transactions"].map((k) => queryClient.invalidateQueries({ queryKey: [k] })));
  const undo = useMutation({ mutationFn: () => rollbackImport(b.id), onSuccess: async () => { setConfirming(null); await refresh(); } });
  const discard = useMutation({ mutationFn: () => discardImport(b.id), onSuccess: async () => { setConfirming(null); await refresh(); } });
  const status = IMPORT_STATUS[b.status];
  const error = undo.error ?? discard.error;

  return (
    <li className="space-y-3 px-4 py-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-medium">{b.fileName}</p>
          <p className={mutedCls}>
            {formatDate(b.createdAt)} · {b.format} · {accountName}
          </p>
          <p className={mutedCls}>{counts(b)}</p>
        </div>
        <span className={`rounded px-2 py-0.5 text-xs font-medium ${status.cls}`}>{status.label}</span>
      </div>

      {!confirming && (
        <div className="flex flex-wrap gap-2">
          {b.status === "PREVIEW" && (
            <>
              <button type="button" onClick={() => onResume(b.id)} className={btnSecondary} aria-label={`Revisar ${b.fileName}`}>Revisar</button>
              <button type="button" onClick={() => setConfirming("discard")} className={btnSecondary} aria-label={`Descartar ${b.fileName}`}>Descartar</button>
            </>
          )}
          {b.status === "CONFIRMED" && (
            <>
              <Link href={`/transacoes?importId=${b.id}`} className={btnSecondary}>
                Ver transações
              </Link>
              <button type="button" onClick={() => setConfirming("undo")} className={btnSecondary} aria-label={`Desfazer ${b.fileName}`}>Desfazer</button>
            </>
          )}
        </div>
      )}

      {confirming === "undo" && (
        <div role="alertdialog" aria-label={`Confirmar o desfazer de ${b.fileName}`} className="space-y-3 rounded-lg bg-amber-50 p-3 text-sm dark:bg-amber-950/40">
          <p>
            As <strong>{b.importedRows}</strong> transações desta importação saem dos saldos e do resumo. O histórico continua aqui, e você pode importar o arquivo de novo depois.
          </p>
          <div className="flex gap-2">
            <button type="button" onClick={() => undo.mutate()} disabled={undo.isPending} className={btnDanger}>Desfazer importação</button>
            <button type="button" onClick={() => setConfirming(null)} className={btnSecondary}>Cancelar</button>
          </div>
        </div>
      )}
      {confirming === "discard" && (
        <div role="alertdialog" aria-label={`Confirmar o descarte de ${b.fileName}`} className="space-y-3 rounded-lg bg-amber-50 p-3 text-sm dark:bg-amber-950/40">
          <p>A prévia será apagada. Nenhuma transação foi criada, então nada muda nos saldos.</p>
          <div className="flex gap-2">
            <button type="button" onClick={() => discard.mutate()} disabled={discard.isPending} className={btnDanger}>Descartar prévia</button>
            <button type="button" onClick={() => setConfirming(null)} className={btnSecondary}>Cancelar</button>
          </div>
        </div>
      )}
      {error && <p role="alert" className={errorCls}>{error.message}</p>}
    </li>
  );
}

export function ImportsView() {
  const [wizard, setWizard] = useState<{ open: boolean; resumeId?: string }>({ open: false });
  const [page, setPage] = useState(0);
  const accountsQ = useQuery({ queryKey: ["accounts", true], queryFn: () => listAccounts(true) });
  const importsQ = useQuery({ queryKey: ["imports", page], queryFn: () => listImports(page), placeholderData: (previous) => previous });
  const accounts = accountsQ.data ?? NO_ACCOUNTS;
  const names = useMemo(() => new Map(accounts.map((a) => [a.id, a.name])), [accounts]);
  const data = importsQ.data;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">Importações</h1>
        {!wizard.open && (
          <button type="button" onClick={() => setWizard({ open: true })} className={btnPrimary}>
            Nova importação
          </button>
        )}
      </div>

      {wizard.open ? (
        <ImportWizard key={wizard.resumeId ?? "nova"} resumeId={wizard.resumeId} onClose={() => setWizard({ open: false })} />
      ) : (
        <>
          <p className={mutedCls}>
            Traga o extrato do seu banco em OFX ou CSV. Você revisa o que vai entrar antes de confirmar, o sistema avisa o que já existe e dá para desfazer a importação inteira.
          </p>
          {importsQ.isPending && <p className={mutedCls}>Carregando…</p>}
          {importsQ.error && <p role="alert" className={errorCls}>Não foi possível carregar o histórico.</p>}
          {data && data.items.length === 0 && <p className={`${cardCls} text-center ${mutedCls}`}>Nenhuma importação ainda. Comece enviando um extrato.</p>}
          {data && data.items.length > 0 && (
            <ul className={`${cardCls} divide-y divide-zinc-200 p-0 dark:divide-zinc-800`} aria-label="Histórico de importações">
              {data.items.map((b) => (
                <Row key={b.id} b={b} accountName={names.get(b.accountId) ?? "Conta"} onResume={(id) => setWizard({ open: true, resumeId: id })} />
              ))}
            </ul>
          )}
          {data && data.totalPages > 1 && (
            <nav aria-label="Paginação do histórico" className="flex items-center justify-between gap-3">
              <p className={mutedCls}>Página {page + 1} de {data.totalPages}</p>
              <div className="flex gap-2">
                <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0} className={btnSecondary}>Anterior</button>
                <button type="button" onClick={() => setPage((p) => p + 1)} disabled={page + 1 >= data.totalPages} className={btnSecondary}>Próxima</button>
              </div>
            </nav>
          )}
        </>
      )}
    </div>
  );
}
