// Dinheiro em reais SEM ponto flutuante: tudo é string decimal ("1234.50"), como na API (ADR-013).
// O usuário digita no formato brasileiro ("1.234,56"); a conversão é feita por texto, nunca por Number.

export type MoneyParse = { ok: true; value: string } | { ok: false; error: string };

const MAX_INTEGER_DIGITS = 12; // NUMERIC(14,2) do banco

type Options = { allowNegative?: boolean; allowZero?: boolean };

/** Converte o que o usuário digitou em string decimal canônica ("1234.50"), ou devolve o motivo da recusa. */
export function parseMoney(input: string, opts: Options = {}): MoneyParse {
  let s = input.trim();
  let negative = false;
  if (s.startsWith("-")) {
    negative = true;
    s = s.slice(1).trim();
  }
  s = s.replace(/^R\$\s*/i, "").replace(/\s/g, "");

  if (s === "") return { ok: false, error: "Informe um valor." };
  if (negative && !opts.allowNegative) return { ok: false, error: "O valor não pode ser negativo." };
  if (!/^[0-9.,]+$/.test(s)) return { ok: false, error: "Use apenas números, vírgula e ponto." };

  let integer: string;
  let fraction = "";
  if (s.includes(",")) {
    // vírgula é o separador decimal; pontos só podem agrupar milhares
    const parts = s.split(",");
    if (parts.length > 2) return { ok: false, error: "Formato de valor inválido." };
    const [intPart, fracPart] = parts;
    if (intPart !== "" && !/^(\d+|\d{1,3}(\.\d{3})+)$/.test(intPart)) return { ok: false, error: "Formato de valor inválido." };
    integer = intPart.replaceAll(".", "");
    fraction = fracPart;
  } else if (s.includes(".")) {
    const dots = s.split(".").length - 1;
    const afterLast = s.slice(s.lastIndexOf(".") + 1);
    if (dots === 1 && afterLast.length >= 1 && afterLast.length <= 2) {
      // "12.5" ou "12.50": ponto decimal (estilo internacional)
      integer = s.slice(0, s.lastIndexOf("."));
      fraction = afterLast;
    } else if (/^\d{1,3}(\.\d{3})+$/.test(s)) {
      integer = s.replaceAll(".", ""); // "1.500" ou "1.234.567": milhares
    } else {
      return { ok: false, error: "Formato de valor inválido." };
    }
  } else {
    integer = s;
  }

  if (!/^\d*$/.test(fraction)) return { ok: false, error: "Formato de valor inválido." };
  if (fraction.length > 2) return { ok: false, error: "Use no máximo 2 casas decimais." };
  integer = integer.replace(/^0+(?=\d)/, "") || "0";
  if (integer.length > MAX_INTEGER_DIGITS) return { ok: false, error: "Valor acima do limite permitido." };

  const value = `${integer}.${fraction.padEnd(2, "0")}`;
  const isZero = /^0\.00$/.test(value);
  if (isZero && !opts.allowZero) return { ok: false, error: "O valor deve ser maior que zero." };
  return { ok: true, value: negative && !isZero ? `-${value}` : value };
}

/** "1234.5" → "R$ 1.234,50"; negativo → "-R$ 200,00". Texto inesperado volta como veio. */
export function formatMoney(decimal: string): string {
  const m = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(decimal);
  if (!m) return decimal;
  const [, sign, integer, fraction = ""] = m;
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  return `${sign}R$ ${grouped},${fraction.padEnd(2, "0")}`;
}

/** Negativo de verdade (-0,00 não conta). */
export function isNegative(decimal: string): boolean {
  return decimal.startsWith("-") && /[1-9]/.test(decimal);
}
