// Classes compartilhadas (Tailwind). Foco sempre visível, contraste AA nos dois temas.
export const inputCls =
  "mt-1 w-full rounded-lg border border-zinc-300 bg-white px-3 py-2 text-zinc-900 shadow-sm focus:border-emerald-600 focus:outline-none focus:ring-2 focus:ring-emerald-600/30 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100";
export const labelCls = "block text-sm font-medium";
export const btnPrimary =
  "rounded-lg bg-emerald-700 px-4 py-2 text-sm font-medium text-white transition hover:bg-emerald-800 focus:outline-none focus:ring-2 focus:ring-emerald-600/50 disabled:cursor-not-allowed disabled:opacity-60";
export const btnSecondary =
  "rounded-lg border border-zinc-300 px-3 py-2 text-sm font-medium transition hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-emerald-600/40 disabled:cursor-not-allowed disabled:opacity-60 dark:border-zinc-700 dark:hover:bg-zinc-800";
export const btnDanger =
  "rounded-lg bg-red-700 px-3 py-2 text-sm font-medium text-white transition hover:bg-red-800 focus:outline-none focus:ring-2 focus:ring-red-600/50 disabled:cursor-not-allowed disabled:opacity-60";
export const cardCls = "rounded-xl border border-zinc-200 bg-white p-4 shadow-sm dark:border-zinc-800 dark:bg-zinc-900";
export const errorCls = "text-sm text-red-700 dark:text-red-400";
export const mutedCls = "text-sm text-zinc-600 dark:text-zinc-400";
export const negCls = "text-red-700 dark:text-red-400";
export const posCls = "text-emerald-700 dark:text-emerald-400";

// Célula de tabela que no celular vira uma pilha de cartões: cada célula mostra seu rótulo (data-label) à esquerda.
// A tabela, o thead, o tbody e o tr precisam de max-md:block (e o thead de max-md:sr-only) e o contêiner de `relative`.
export const stackedCell =
  "px-3 py-3 max-md:flex max-md:items-start max-md:justify-between max-md:gap-4 max-md:px-0 max-md:py-1 max-md:before:shrink-0 max-md:before:font-medium max-md:before:text-zinc-500 max-md:before:content-[attr(data-label)] dark:max-md:before:text-zinc-400";
