// Datas: o usuário digita no horário do aparelho; a API guarda o instante em UTC e o "dia" é o de São Paulo.

const pad = (n: number) => String(n).padStart(2, "0");

/** Valor para <input type="datetime-local"> no horário local do aparelho. */
export function nowLocalInput(d = new Date()): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** "2026-05-10T15:30" (horário local) → instante ISO em UTC; null se inválido. */
export function localInputToIso(value: string): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

/** dd/mm/aaaa no fuso de São Paulo (o mesmo que o backend usa para "o dia"). */
export function formatDate(iso: string): string {
  return new Intl.DateTimeFormat("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  }).format(new Date(iso));
}
