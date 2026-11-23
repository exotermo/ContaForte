import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LoginForm, messageFor } from "./login-form";

const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh }) }));

afterEach(() => {
  vi.restoreAllMocks();
  replace.mockClear();
  refresh.mockClear();
});

async function preencherEEnviar() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("E-mail"), "ana@exemplo.com");
  await user.type(screen.getByLabelText("Senha"), "segredo123");
  await user.click(screen.getByRole("button", { name: "Entrar" }));
}

describe("LoginForm", () => {
  it("envia as credenciais e navega para a home em caso de sucesso", async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response("{}", { status: 200 })));
    vi.stubGlobal("fetch", fetchMock);
    render(<LoginForm />);

    await preencherEEnviar();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/session/login");
    expect(JSON.parse(init.body as string)).toEqual({ email: "ana@exemplo.com", password: "segredo123" });
  });

  it("mostra mensagem genérica quando as credenciais são recusadas", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("{}", { status: 401 }))));
    render(<LoginForm />);

    await preencherEEnviar();

    expect(await screen.findByRole("alert")).toHaveTextContent("E-mail ou senha incorretos.");
    expect(replace).not.toHaveBeenCalled();
  });

  it("informa o tempo de espera no bloqueio por excesso de tentativas", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response("{}", { status: 429, headers: { "Retry-After": "600" } }))),
    );
    render(<LoginForm />);

    await preencherEEnviar();

    expect(await screen.findByRole("alert")).toHaveTextContent("Tente novamente em 10 min.");
  });

  it("trata falha de rede sem quebrar", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("rede"))));
    render(<LoginForm />);

    await preencherEEnviar();

    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível entrar agora");
  });
});

describe("messageFor", () => {
  it("usa pelo menos 1 minuto", () => {
    expect(messageFor(429, "5")).toContain("1 min");
  });
});
