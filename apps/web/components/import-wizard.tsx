"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";
import { createImport, getImport, listAccounts, suggestMapping } from "@/lib/finance";
import type { CsvSuggestion, ImportBatch } from "@/lib/types";
import { CsvMapper } from "./csv-mapper";
import { ImportReview } from "./import-review";
import { btnPrimary, btnSecondary, cardCls, errorCls, inputCls, labelCls, mutedCls } from "./ui";

const MAX_BYTES = 5 * 1024 * 1024;

type Step = "file" | "columns" | "review" | "done";

/** OFX tem uma marca no começo; qualquer outro texto é tratado como CSV. O servidor confere de novo pelo conteúdo. */
function looksLikeOfx(file: File): Promise<boolean> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const head = String(reader.result ?? "").toLowerCase();
      resolve(head.includes("ofxheader") || head.includes("<ofx"));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsText(file.slice(0, 4096), "iso-8859-1");   // só a marca no começo importa; ISO-8859-1 nunca falha
  });
}

type Props = {
  /** Reabre uma prévia que ficou aguardando confirmação. */
  resumeId?: string;
  onClose: () => void;
};

export function ImportWizard({ resumeId, onClose }: Props) {
  const id = useId();
  const queryClient = useQueryClient();
  const accountsQ = useQuery({ queryKey: ["accounts", false], queryFn: () => listAccounts(false) });
  const accounts = accountsQ.data ?? [];

  const [step, setStep] = useState<Step>(resumeId ? "review" : "file");
  const [accountId, setAccountId] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [suggestion, setSuggestion] = useState<CsvSuggestion | null>(null);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [result, setResult] = useState<ImportBatch | null>(null);

  const resume = useQuery({
    queryKey: ["import", resumeId],
    queryFn: () => getImport(resumeId!),
    enabled: !!resumeId,
  });
  const current = batch ?? resume.data ?? null;
  const chosenAccount = accountId || accounts[0]?.id || "";

  const suggest = useMutation({
    mutationFn: (f: File) => suggestMapping(f),
    onSuccess: (s) => {
      setSuggestion(s);
      setStep("columns");
    },
  });
  const create = useMutation({
    mutationFn: (mapping?: Record<string, string>) => createImport({ file: file!, accountId: chosenAccount, mapping }),
    onSuccess: async (b) => {
      setBatch(b);
      setStep("review");
      await queryClient.invalidateQueries({ queryKey: ["imports"] });
    },
  });

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0] ?? null;
    setFile(f);
    setFileError(null);
    suggest.reset();
    create.reset();
    if (f && f.size > MAX_BYTES) setFileError("O arquivo passa de 5 MB: exporte um período menor.");
    else if (f && f.size === 0) setFileError("O arquivo está vazio.");
  }

  async function onContinue(e: React.FormEvent) {
    e.preventDefault();
    if (!file || fileError || !chosenAccount) return;
    if (await looksLikeOfx(file)) create.mutate(undefined);
    else suggest.mutate(file);
  }

  const busy = suggest.isPending || create.isPending;
  const submitError = (suggest.error ?? create.error)?.message ?? null;

  return (
    <div className="space-y-5">
      <ol className={`flex flex-wrap gap-x-6 gap-y-1 ${mutedCls}`} aria-label="Etapas">
        {[
          ["file", "1. Arquivo"],
          ["columns", "2. Colunas (CSV)"],
          ["review", "3. Revisão"],
          ["done", "4. Concluído"],
        ].map(([s, label]) => (
          <li key={s} aria-current={step === s ? "step" : undefined} className={step === s ? "font-semibold text-emerald-700 dark:text-emerald-400" : ""}>
            {label}
          </li>
        ))}
      </ol>

      {step === "file" && (
        <form onSubmit={onContinue} noValidate className={`${cardCls} space-y-4`} aria-label="Escolher extrato">
          {accountsQ.isSuccess && accounts.length === 0 ? (
            <p className={mutedCls}>
              Cadastre uma conta antes de importar.{" "}
              <Link href="/contas" className="font-medium text-emerald-700 underline dark:text-emerald-400">
                Ir para Contas
              </Link>
            </p>
          ) : (
            <>
              <div>
                <label htmlFor={`${id}-conta`} className={labelCls}>
                  Conta que recebe o extrato
                </label>
                <select id={`${id}-conta`} value={chosenAccount} onChange={(e) => setAccountId(e.target.value)} className={inputCls}>
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor={`${id}-arquivo`} className={labelCls}>
                  Arquivo do extrato (OFX ou CSV)
                </label>
                <input
                  id={`${id}-arquivo`}
                  type="file"
                  accept=".ofx,.qfx,.csv,.txt"
                  onChange={onFileChange}
                  aria-describedby={`${id}-ajuda`}
                  className="mt-1 block w-full text-sm file:mr-3 file:rounded-lg file:border file:border-zinc-300 file:bg-white file:px-3 file:py-2 file:text-sm dark:file:border-zinc-700 dark:file:bg-zinc-900"
                />
                <p id={`${id}-ajuda`} className={`mt-1 ${mutedCls}`}>
                  Até 5 MB, em reais. O arquivo é lido e <strong>não fica guardado</strong>: só as linhas da prévia, por 24 horas. Nada é importado até você confirmar.
                </p>
              </div>
              {(fileError || submitError) && (
                <p role="alert" className={errorCls}>
                  {fileError ?? submitError}
                </p>
              )}
              <div className="flex gap-2">
                <button type="submit" disabled={!file || !!fileError || busy} className={btnPrimary}>
                  {busy ? "Lendo o extrato…" : "Continuar"}
                </button>
                <button type="button" onClick={onClose} className={btnSecondary}>
                  Cancelar
                </button>
              </div>
            </>
          )}
        </form>
      )}

      {step === "columns" && suggestion && (
        <CsvMapper
          suggestion={suggestion}
          pending={create.isPending}
          error={create.error?.message ?? null}
          onSubmit={(mapping) => create.mutate(mapping)}
          onBack={() => {
            create.reset();
            setStep("file");
          }}
        />
      )}

      {step === "review" && !current && (resume.isError ? <p role="alert" className={errorCls}>Não foi possível abrir esta prévia. Ela pode ter vencido.</p> : <p className={mutedCls}>Carregando…</p>)}
      {step === "review" && current && (
        <ImportReview
          batch={current}
          onConfirmed={(r) => {
            setResult(r);
            setStep("done");
          }}
          onDiscarded={onClose}
        />
      )}

      {step === "done" && result && (
        <section className={`${cardCls} space-y-3`} aria-label="Importação concluída">
          <h2 className="text-lg font-semibold">Importação concluída</h2>
          <p role="status">
            <strong>{result.importedRows}</strong> {result.importedRows === 1 ? "transação importada" : "transações importadas"}.
            {(result.skippedByConflict ?? 0) > 0 && ` ${result.skippedByConflict} já existiam e foram puladas.`}
          </p>
          <p className={mutedCls}>Saldos e resumo já foram atualizados. Se algo não ficou como esperado, você pode desfazer a importação pelo histórico.</p>
          <div className="flex flex-wrap gap-2">
            <Link href={`/transacoes?importId=${result.id}`} className={btnPrimary}>
              Ver as transações
            </Link>
            <button type="button" onClick={onClose} className={btnSecondary}>
              Voltar ao histórico
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
