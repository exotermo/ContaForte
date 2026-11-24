import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppShell } from "./app-shell";
import { renderWithQuery } from "./test-utils";

let pathname = "/";
vi.mock("next/navigation", () => ({ usePathname: () => pathname, useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock("@/lib/api", async (orig) => ({ ...(await orig<typeof import("@/lib/api")>()), fetchMe: () => Promise.resolve({ email: "ana@exemplo.com" }), logout: vi.fn() }));
const reviewCount = vi.fn();
vi.mock("@/lib/finance", async (orig) => ({ ...(await orig<typeof import("@/lib/finance")>()), reviewCount: (...a: unknown[]) => reviewCount(...a) }));

afterEach(() => {
  pathname = "/";
  reviewCount.mockReset();
});

describe("AppShell", () => {
  it("tem os links das telas de classificação e marca a página atual", async () => {
    reviewCount.mockResolvedValue({ suggested: 0, uncategorized: 0 });
    pathname = "/regras";
    renderWithQuery(<AppShell>conteúdo</AppShell>);

    for (const name of ["Resumo", "Contas", "Transações", "Importações", "Revisão", "Regras", "Favorecidos"]) {
      expect(screen.getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Regras" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Revisão" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "Favorecidos" })).toHaveAttribute("href", "/favorecidos");
  });

  it("mostra quantas sugestões aguardam, com texto acessível, quando há alguma", async () => {
    reviewCount.mockResolvedValue({ suggested: 3, uncategorized: 40 });
    renderWithQuery(<AppShell>conteúdo</AppShell>);

    expect(await screen.findByRole("link", { name: /Revisão\s*,?\s*3 sugestões aguardando/ })).toBeInTheDocument();
  });

  it("no singular e sem contador quando não há sugestão (o total sem categoria não entra)", async () => {
    reviewCount.mockResolvedValue({ suggested: 1, uncategorized: 0 });
    const { unmount } = renderWithQuery(<AppShell>x</AppShell>);
    expect(await screen.findByRole("link", { name: /Revisão\s*,?\s*1 sugestão aguardando/ })).toBeInTheDocument();
    unmount();

    reviewCount.mockResolvedValue({ suggested: 0, uncategorized: 99 });
    renderWithQuery(<AppShell>x</AppShell>);
    await screen.findByText("ana@exemplo.com");
    expect(screen.getByRole("link", { name: "Revisão" })).toBeInTheDocument();
  });

  it("se o contador falhar, o menu continua funcionando sem ele", async () => {
    reviewCount.mockRejectedValue(new Error("falhou"));
    renderWithQuery(<AppShell>conteúdo</AppShell>);

    expect(await screen.findByText("ana@exemplo.com")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Revisão" })).toBeInTheDocument();
  });
});
