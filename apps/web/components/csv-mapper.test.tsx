import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CsvMapper } from "./csv-mapper";
import { suggestionA } from "./test-utils";

const setup = (suggestion = suggestionA(), extra: Partial<React.ComponentProps<typeof CsvMapper>> = {}) => {
  const onSubmit = vi.fn();
  const onBack = vi.fn();
  render(<CsvMapper suggestion={suggestion} onSubmit={onSubmit} onBack={onBack} {...extra} />);
  return { onSubmit, onBack, user: userEvent.setup() };
};

describe("CsvMapper", () => {
  it("mostra o que foi detectado e a amostra do arquivo", () => {
    setup();
    expect(screen.getByText(/Detectado: separador ponto e vírgula \(;\), cabeçalho na linha 1/)).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Amostra das primeiras linhas do arquivo" })).toBeInTheDocument();
    expect(screen.getByText("COMPRA CARTAO MERCADO EXEMPLO")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Histórico" })).toBeInTheDocument();
  });

  it("já vem preenchido com o palpite do servidor", () => {
    setup();
    expect(screen.getByLabelText("Coluna da data")).toHaveValue("0");
    expect(screen.getByLabelText("Formato da data")).toHaveValue("DMY_SLASH");
    expect(screen.getByLabelText("Coluna do valor")).toHaveValue("3");
    expect(screen.getByLabelText("Coluna da descrição")).toHaveValue("1");
    expect(screen.getByLabelText("Coluna do identificador")).toHaveValue("2");
  });

  it("envia o mapeamento por posição (#N), com delimitador e cabeçalho detectados", async () => {
    const { onSubmit, user } = setup();
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));

    expect(onSubmit).toHaveBeenCalledWith({
      delimiter: ";",
      skipLines: "0",
      hasHeader: "true",
      dateColumn: "#1",
      dateFormat: "DMY_SLASH",
      invertSign: "false",
      amountColumn: "#4",
      descriptionColumn: "#2",
      idColumn: "#3",
    });
  });

  it("deixa trocar colunas, formato e inverter o sinal", async () => {
    const { onSubmit, user } = setup();
    await user.selectOptions(screen.getByLabelText("Coluna da descrição"), "");   // "Não usar"
    await user.selectOptions(screen.getByLabelText("Coluna do identificador"), "");
    await user.selectOptions(screen.getByLabelText("Formato da data"), "AUTO");
    await user.click(screen.getByLabelText(/Inverter o sinal/));
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));

    const mapping = onSubmit.mock.calls[0][0];
    expect(mapping).toMatchObject({ dateFormat: "AUTO", invertSign: "true" });
    expect(mapping).not.toHaveProperty("descriptionColumn");
    expect(mapping).not.toHaveProperty("idColumn");
  });

  it("tabulação vai como TAB e o salto de linhas do título é repassado", async () => {
    const s = suggestionA({ delimiter: "\t", skipLines: 3, mapping: { ...suggestionA().mapping, delimiter: "TAB", skipLines: "3" } });
    const { onSubmit, user } = setup(s);
    expect(screen.getByText(/separador tabulação, cabeçalho na linha 4/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ delimiter: "TAB", skipLines: "3" });
  });

  it("débito e crédito em colunas separadas (banco B)", async () => {
    const s = suggestionA({
      delimiter: ",",
      headers: ["Date", "Description", "Debit", "Credit", "Balance"],
      sample: [["2026-03-02", "x", "187.45", "", "1000.00"]],
      mapping: { delimiter: ",", skipLines: "0", hasHeader: "true", dateColumn: "Date", dateFormat: "YMD_DASH", debitColumn: "Debit", creditColumn: "Credit", descriptionColumn: "Description", invertSign: "false" },
    });
    const { onSubmit, user } = setup(s);

    expect(screen.getByLabelText("Colunas separadas de débito e de crédito")).toBeChecked();
    expect(screen.getByLabelText("Coluna de débito (saídas)")).toHaveValue("2");
    expect(screen.queryByLabelText("Coluna do valor")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ debitColumn: "#3", creditColumn: "#4", dateFormat: "YMD_DASH", delimiter: "," });
    expect(onSubmit.mock.calls[0][0]).not.toHaveProperty("amountColumn");
  });

  it("valor positivo com coluna de tipo D/C (banco C)", async () => {
    const s = suggestionA({
      headers: ["Data", "Lançamento", "Tipo", "Valor"],
      sample: [["02/03/26", "x", "D", "187,45"]],
      mapping: { delimiter: "TAB", skipLines: "3", hasHeader: "true", dateColumn: "Data", dateFormat: "DMY_SLASH_SHORT", typeColumn: "Tipo", amountColumn: "Valor", descriptionColumn: "Lançamento", invertSign: "false" },
    });
    const { onSubmit, user } = setup(s);

    expect(screen.getByLabelText(/Valor sempre positivo, com uma coluna de tipo/)).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ amountColumn: "#4", typeColumn: "#3", dateColumn: "#1" });
  });

  it("sem palpite, o usuário escolhe tudo e o formulário cobra o obrigatório", async () => {
    const s = suggestionA({ mapping: {}, headers: ["alfa", "beta", "gama"], sample: [["1", "2", "3"]] });
    const { onSubmit, user } = setup(s);

    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Escolha a coluna da data.");
    await user.selectOptions(screen.getByLabelText("Coluna da data"), "0");
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Escolha a coluna do valor.");
    expect(onSubmit).not.toHaveBeenCalled();

    await user.selectOptions(screen.getByLabelText("Coluna do valor"), "2");
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ dateColumn: "#1", amountColumn: "#3" });
  });

  it("modo tipo exige a coluna do tipo e modo split exige débito ou crédito", async () => {
    const s = suggestionA({ mapping: { dateColumn: "Data" } });
    const { onSubmit, user } = setup(s);
    await user.click(screen.getByLabelText(/Valor sempre positivo/));
    await user.selectOptions(screen.getByLabelText("Coluna do valor"), "3");
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(screen.getByRole("alert")).toHaveTextContent("valor e a do tipo");

    await user.click(screen.getByLabelText(/Colunas separadas de débito e de crédito/));
    await user.click(screen.getByRole("button", { name: "Ler extrato" }));
    expect(screen.getByRole("alert")).toHaveTextContent("débito e/ou a de crédito");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("casa o nome sugerido com o cabeçalho sem diferenciar acento e caixa, e aceita #N", () => {
    const s = suggestionA({ mapping: { ...suggestionA().mapping, descriptionColumn: "HISTORICO", idColumn: "#3", amountColumn: "valor" } });
    setup(s);
    expect(screen.getByLabelText("Coluna da descrição")).toHaveValue("1");
    expect(screen.getByLabelText("Coluna do identificador")).toHaveValue("2");
    expect(screen.getByLabelText("Coluna do valor")).toHaveValue("3");
  });

  it("sem cabeçalho, as colunas são 'Coluna N'", () => {
    const s = suggestionA({ hasHeader: false, headers: [], mapping: {}, sample: [["02/03/2026", "MERCADO", "-10,00"]] });
    setup(s);
    expect(screen.getByText(/sem cabeçalho/)).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Coluna 3" })).toBeInTheDocument();
  });

  it("mostra avisos do servidor, o erro da leitura e o estado de espera; Voltar funciona", async () => {
    const { onBack, user } = setup(suggestionA({ warnings: ["O arquivo não parece ter cabeçalho."] }), { error: "Não consegui ler.", pending: false });
    expect(screen.getByText("O arquivo não parece ter cabeçalho.")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Não consegui ler.");
    await user.click(screen.getByRole("button", { name: "Voltar" }));
    expect(onBack).toHaveBeenCalled();
  });

  it("enquanto lê, o botão fica desabilitado", () => {
    setup(suggestionA(), { pending: true });
    expect(screen.getByRole("button", { name: "Lendo o extrato…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Voltar" })).toBeDisabled();
  });
});
