// Funções puras do dashboard: meses (AAAA-MM), intervalos de datas e contas em centavos exatos.

const MONTHS = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];

/** "2026-05" → "mai/2026". */
export function monthLabel(month: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) return month;
  return `${MONTHS[Number(m[2]) - 1]}/${m[1]}`;
}

/** "2026-05" → "maio de 2026" (para títulos e leitores de tela). */
export function monthLong(month: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) return month;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, 1));
  return d.toLocaleDateString("pt-BR", { month: "long", year: "numeric", timeZone: "UTC" });
}

/** Mês corrente em America/Sao_Paulo, como o backend. */
export function currentMonth(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Sao_Paulo", year: "numeric", month: "2-digit" }).formatToParts(now);
  const y = parts.find((p) => p.type === "year")!.value;
  const m = parts.find((p) => p.type === "month")!.value;
  return `${y}-${m}`;
}

export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split("-").map(Number);
  const index = y * 12 + (m - 1) + delta;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`;
}

/** Primeiro e último dia do mês, no formato dos filtros de data ("2026-02" → 2026-02-01 a 2026-02-28). */
export function monthRange(month: string): { from: string; to: string } {
  const [y, m] = month.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, "0")}` };
}

/** "1234.50" → 123450 (centavos, inteiro exato). */
export function toCents(decimal: string): number {
  const m = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(decimal);
  if (!m) return 0;
  const cents = Number(m[2]) * 100 + Number((m[3] ?? "").padEnd(2, "0"));
  return m[1] ? -cents : cents;
}

export function fromCents(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

/** Diferença exata `current - previous`, em decimal. */
export function diff(current: string, previous: string): string {
  return fromCents(toCents(current) - toCents(previous));
}

/** Parte de `value` no `total`, em % inteiro (0 se o total for zero). Só para desenhar; o valor exibido vem do servidor. */
export function share(value: string, total: string): number {
  const t = toCents(total);
  if (t <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((toCents(value) / t) * 100)));
}
