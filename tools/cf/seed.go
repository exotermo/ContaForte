package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // fuso de São Paulo embutido: não depende do tzdata do sistema/container
)

// scenario é o arquivo datasets/synthetic/scenario.json (gerado por tools/gen-synthetic). Tudo é fictício.
type scenario struct {
	Version      int               `json:"version"`
	Fictional    bool              `json:"fictional"`
	Seed         int               `json:"seed"`
	DemoEmail    string            `json:"demoEmail"`
	Accounts     []scAccount       `json:"accounts"`
	Expected     map[string]string `json:"expected"`
	Transactions []scTx            `json:"transactions"`
}

type scAccount struct {
	Key            string `json:"key"`
	Name           string `json:"name"`
	Type           string `json:"type"`
	OpeningBalance string `json:"openingBalance"`
}

type scTx struct {
	DaysAgo     int     `json:"daysAgo"`
	Hour        int     `json:"hour"`
	Type        string  `json:"type"`
	Account     string  `json:"account"`
	Counterpart *string `json:"counterpart"`
	Amount      string  `json:"amount"`
	Description string  `json:"description"`
	Merchant    *string `json:"merchant"`
	Category    *string `json:"category"`
	Context     *string `json:"context"`
}

var (
	spZone      = mustZone("America/Sao_Paulo")
	moneyRe     = regexp.MustCompile(`^-?\d{1,12}\.\d{2}$`)
	demoEmailRe = regexp.MustCompile(`^[a-z0-9._-]+@[a-z0-9.-]+$`)

	accountTypes = map[string]bool{"CHECKING_ACCOUNT": true, "SAVINGS_ACCOUNT": true, "CASH": true, "CREDIT_CARD": true,
		"VA": true, "VR": true, "INVESTMENT_ACCOUNT": true, "OTHER": true}
	txTypes = map[string]bool{"INCOME": true, "EXPENSE": true, "TRANSFER": true, "INVESTMENT": true, "REFUND": true}
)

func mustZone(name string) *time.Location {
	loc, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return loc
}

func loadScenario(path string) (*scenario, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	dec := json.NewDecoder(f)
	dec.DisallowUnknownFields() // um campo digitado errado no cenário não passa em silêncio
	var sc scenario
	if err := dec.Decode(&sc); err != nil {
		return nil, fmt.Errorf("cenário inválido: %w", err)
	}
	return &sc, validateScenario(&sc)
}

func validateScenario(sc *scenario) error {
	if sc.Version != 1 {
		return fmt.Errorf("versão de cenário não suportada: %d", sc.Version)
	}
	if !sc.Fictional {
		return fmt.Errorf("o cenário precisa declarar fictional=true (nunca carregue dados reais)")
	}
	if !demoEmailRe.MatchString(sc.DemoEmail) {
		return fmt.Errorf("demoEmail inválido: %q", sc.DemoEmail)
	}
	keys := map[string]string{}
	for _, a := range sc.Accounts {
		if a.Key == "" || a.Name == "" {
			return fmt.Errorf("conta sem chave ou nome: %+v", a)
		}
		if _, dup := keys[a.Key]; dup {
			return fmt.Errorf("conta duplicada: %s", a.Key)
		}
		if !accountTypes[a.Type] {
			return fmt.Errorf("conta %s: tipo inválido %q", a.Key, a.Type)
		}
		if !moneyRe.MatchString(a.OpeningBalance) {
			return fmt.Errorf("conta %s: saldo inicial inválido %q", a.Key, a.OpeningBalance)
		}
		keys[a.Key] = a.Type
	}
	for i, t := range sc.Transactions {
		at := fmt.Sprintf("transação %d (%s)", i+1, t.Description)
		if !txTypes[t.Type] {
			return fmt.Errorf("%s: tipo inválido %q", at, t.Type)
		}
		if _, ok := keys[t.Account]; !ok {
			return fmt.Errorf("%s: conta desconhecida %q", at, t.Account)
		}
		if !moneyRe.MatchString(t.Amount) || strings.HasPrefix(t.Amount, "-") || strings.Trim(t.Amount, "0.") == "" {
			return fmt.Errorf("%s: valor inválido %q (deve ser positivo, com 2 casas)", at, t.Amount)
		}
		needsDest := t.Type == "TRANSFER" || t.Type == "INVESTMENT"
		switch {
		case needsDest && t.Counterpart == nil:
			return fmt.Errorf("%s: %s exige conta de destino", at, t.Type)
		case !needsDest && t.Counterpart != nil:
			return fmt.Errorf("%s: %s não aceita conta de destino", at, t.Type)
		case needsDest:
			dest, ok := keys[*t.Counterpart]
			if !ok || *t.Counterpart == t.Account {
				return fmt.Errorf("%s: destino inválido %q", at, *t.Counterpart)
			}
			if t.Type == "INVESTMENT" && dest != "INVESTMENT_ACCOUNT" {
				return fmt.Errorf("%s: investimento precisa de destino do tipo INVESTMENT_ACCOUNT", at)
			}
		}
		if t.DaysAgo < 0 || t.DaysAgo > 365 {
			return fmt.Errorf("%s: daysAgo fora de 0..365", at)
		}
		if t.Hour < 0 || t.Hour > 23 {
			return fmt.Errorf("%s: hour fora de 0..23", at)
		}
	}
	return nil
}

// occurredAt converte "N dias atrás, às H horas" (dia e hora de São Paulo) em instante UTC no formato da API.
func occurredAt(now time.Time, daysAgo, hour int) string {
	n := now.In(spZone)
	d := time.Date(n.Year(), n.Month(), n.Day()-daysAgo, hour, 0, 0, 0, spZone)
	return d.UTC().Format(time.RFC3339)
}

// ---------------------------------------------------------------- cliente HTTP mínimo da API

type apiClient struct {
	base  string
	hc    *http.Client
	token string
}

func newAPI(base string) *apiClient {
	return &apiClient{base: base, hc: &http.Client{Timeout: 20 * time.Second}}
}

// call devolve o status e decodifica a resposta JSON em out (se não nulo). Erros 4xx/5xx trazem o "detail" da API.
func (a *apiClient) call(method, path string, in, out any) (int, error) {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return 0, err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, a.base+path, body)
	if err != nil {
		return 0, err
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if a.token != "" {
		req.Header.Set("Authorization", "Bearer "+a.token)
	}
	resp, err := a.hc.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode >= 300 {
		var p struct {
			Detail string `json:"detail"`
		}
		_ = json.Unmarshal(raw, &p)
		return resp.StatusCode, fmt.Errorf("%s %s → %d %s", method, path, resp.StatusCode, p.Detail)
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return resp.StatusCode, err
		}
	}
	return resp.StatusCode, nil
}

// upload envia um arquivo (multipart) e decodifica a resposta JSON em out.
func (a *apiClient) upload(path string, fields map[string]string, fileName string, content []byte, out any) (int, error) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, err := mw.CreateFormFile("file", fileName)
	if err != nil {
		return 0, err
	}
	fw.Write(content)
	for k, v := range fields {
		mw.WriteField(k, v)
	}
	mw.Close()
	req, err := http.NewRequest("POST", a.base+path, &buf)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+a.token)
	hc := &http.Client{Timeout: 120 * time.Second}
	resp, err := hc.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode >= 300 {
		var p struct {
			Detail string `json:"detail"`
		}
		_ = json.Unmarshal(raw, &p)
		return resp.StatusCode, fmt.Errorf("POST %s → %d %s", path, resp.StatusCode, p.Detail)
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return resp.StatusCode, err
		}
	}
	return resp.StatusCode, nil
}

func (a *apiClient) login(email, password string) (int, error) {
	var out struct {
		AccessToken string `json:"accessToken"`
	}
	status, err := a.call("POST", "/api/v1/auth/login", map[string]string{"email": email, "password": password}, &out)
	if err == nil {
		a.token = out.AccessToken
	}
	return status, err
}

// ---------------------------------------------------------------- .env e banco

// appendEnv acrescenta uma chave ao .env sem reescrever o resto (preserva a ordem e a permissão 600).
func appendEnv(path, key, value string) error {
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = fmt.Fprintf(f, "%s=%s\n", key, value)
	return err
}

// deleteDemoSQL remove só o usuário demo (e, em cascata, todos os dados dele). O e-mail é validado antes de entrar no SQL.
func deleteDemoSQL(email string) (string, error) {
	if !demoEmailRe.MatchString(email) {
		return "", fmt.Errorf("e-mail demo inválido: %q", email)
	}
	return fmt.Sprintf("delete from users where lower(email) = lower('%s')", email), nil
}

func psql(root string, env map[string]string, sql string) error {
	cmd := exec.Command("docker", "compose", "exec", "-T", "postgres",
		"psql", "-U", env["POSTGRES_USER"], "-d", env["POSTGRES_DB"], "-v", "ON_ERROR_STOP=1", "-q", "-c", sql)
	cmd.Dir = root
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// ---------------------------------------------------------------- comando

type idNode struct {
	ID       string   `json:"id"`
	Code     *string  `json:"code"`
	Children []idNode `json:"children"`
}

func cmdSeed(args []string) error {
	fs := flag.NewFlagSet("seed", flag.ExitOnError)
	reset := fs.Bool("reset", false, "apaga o usuário demo (e só ele) antes de carregar")
	file := fs.String("file", "", "cenário (padrão: datasets/synthetic/scenario.json)")
	withImports := fs.Bool("with-imports", false, "também importa os extratos sintéticos (OFX) numa conta própria, pela API")
	fs.Parse(args)

	root, err := findRoot()
	if err != nil {
		return err
	}
	env, err := parseEnv(filepath.Join(root, ".env"))
	if err != nil {
		return fmt.Errorf(".env não encontrado: rode ./cf init primeiro")
	}
	if tunnelEnabled(env) {
		return fmt.Errorf("recusado: o perfil tunnel está ativo (a instância é alcançável pela Internet). Dados de demonstração só em ambiente local")
	}
	path := *file
	if path == "" {
		path = filepath.Join(root, "datasets", "synthetic", "scenario.json")
	}
	sc, err := loadScenario(path)
	if err != nil {
		return err
	}
	port := env["GATEWAY_PORT"]
	if port == "" {
		port = "8080"
	}
	base := "http://127.0.0.1:" + port
	if !httpOK(base + "/healthz") {
		return fmt.Errorf("o gateway não responde em 127.0.0.1:%s: suba a stack com ./cf up", port)
	}

	admin := newAPI(base)
	if _, err := admin.login(env["ADMIN_EMAIL"], env["ADMIN_PASSWORD"]); err != nil {
		return fmt.Errorf("login do administrador falhou (confira ADMIN_EMAIL/ADMIN_PASSWORD no .env): %w", err)
	}

	pass := env["DEMO_PASSWORD"]
	if pass == "" {
		if pass, err = randomHex(12); err != nil {
			return err
		}
		if err := appendEnv(filepath.Join(root, ".env"), "DEMO_PASSWORD", pass); err != nil {
			return err
		}
		ok("senha do usuário demo gerada e guardada em .env (DEMO_PASSWORD)")
	}
	if *reset {
		sql, err := deleteDemoSQL(sc.DemoEmail)
		if err != nil {
			return err
		}
		if err := psql(root, env, sql); err != nil {
			return fmt.Errorf("não consegui remover o usuário demo: %w", err)
		}
		ok("usuário demo anterior removido (%s)", sc.DemoEmail)
	}

	status, err := admin.call("POST", "/api/v1/admin/users", map[string]string{"email": sc.DemoEmail, "password": pass}, nil)
	if err != nil && status != http.StatusConflict {
		return err
	}
	demo := newAPI(base)
	if status, err := demo.login(sc.DemoEmail, pass); err != nil {
		if status == http.StatusUnauthorized {
			return fmt.Errorf("o usuário %s já existe com outra senha: rode ./cf seed --reset", sc.DemoEmail)
		}
		return err
	}
	var existing []json.RawMessage
	if _, err := demo.call("GET", "/api/v1/accounts?includeArchived=true", nil, &existing); err != nil {
		return err
	}
	if len(existing) > 0 {
		return fmt.Errorf("o usuário demo já tem dados: rode ./cf seed --reset para recarregar do zero")
	}

	if err := loadInto(demo, sc, time.Now()); err != nil {
		return err
	}
	if err := seedClassification(demo); err != nil {
		return err
	}
	if *withImports {
		if err := seedImports(demo, filepath.Join(root, "datasets", "synthetic", "imports")); err != nil {
			return err
		}
	}
	fmt.Printf("\nPronto. Entre em http://127.0.0.1:%s com %s (senha em .env → DEMO_PASSWORD)\n", port, sc.DemoEmail)
	return nil
}

// loadInto cria contas e transações pela API (com todas as regras de negócio) e confere o resumo no final.
func loadInto(demo *apiClient, sc *scenario, now time.Time) error {
	var tree []idNode
	if _, err := demo.call("GET", "/api/v1/categories", nil, &tree); err != nil {
		return err
	}
	categoryID := map[string]string{}
	for _, c := range tree {
		if c.Code != nil {
			categoryID[*c.Code] = c.ID
		}
		for _, f := range c.Children {
			if f.Code != nil {
				categoryID[*f.Code] = f.ID
			}
		}
	}
	var ctxs []idNode
	if _, err := demo.call("GET", "/api/v1/contexts", nil, &ctxs); err != nil {
		return err
	}
	contextID := map[string]string{}
	for _, c := range ctxs {
		if c.Code != nil {
			contextID[*c.Code] = c.ID
		}
	}

	accountID := map[string]string{}
	for _, a := range sc.Accounts {
		var out struct {
			ID string `json:"id"`
		}
		body := map[string]string{"name": a.Name, "type": a.Type, "openingBalance": a.OpeningBalance}
		if _, err := demo.call("POST", "/api/v1/accounts", body, &out); err != nil {
			return fmt.Errorf("conta %s: %w", a.Key, err)
		}
		accountID[a.Key] = out.ID
	}
	ok("%d contas criadas", len(accountID))

	for i, t := range sc.Transactions {
		body := map[string]any{
			"type":        t.Type,
			"accountId":   accountID[t.Account],
			"amount":      t.Amount,
			"occurredAt":  occurredAt(now, t.DaysAgo, t.Hour),
			"description": t.Description,
		}
		if t.Counterpart != nil {
			body["counterpartAccountId"] = accountID[*t.Counterpart]
		}
		if t.Merchant != nil {
			body["merchant"] = *t.Merchant
		}
		if t.Category != nil {
			id, found := categoryID[*t.Category]
			if !found {
				return fmt.Errorf("transação %d: categoria desconhecida %q", i+1, *t.Category)
			}
			body["categoryId"] = id
		}
		if t.Context != nil {
			id, found := contextID[*t.Context]
			if !found {
				return fmt.Errorf("transação %d: contexto desconhecido %q", i+1, *t.Context)
			}
			body["contextId"] = id
		}
		if _, err := demo.call("POST", "/api/v1/transactions", body, nil); err != nil {
			return fmt.Errorf("transação %d (%s): %w", i+1, t.Description, err)
		}
	}
	ok("%d transações lançadas (últimos 90 dias)", len(sc.Transactions))

	// autoverificação: o resumo calculado pelo backend precisa bater com o esperado pelo gerador
	var got map[string]any
	if _, err := demo.call("GET", "/api/v1/summary", nil, &got); err != nil {
		return err
	}
	var diffs []string
	for _, k := range []string{"availableCash", "restrictedBalance", "investedAssets", "creditCardBalance", "totalNetWorth"} {
		if fmt.Sprint(got[k]) != sc.Expected[k] {
			diffs = append(diffs, fmt.Sprintf("%s: backend %v ≠ esperado %s", k, got[k], sc.Expected[k]))
		}
	}
	if len(diffs) > 0 {
		return fmt.Errorf("o resumo não bate com o esperado:\n  %s", strings.Join(diffs, "\n  "))
	}
	ok("resumo confere: disponível %s, restrito %s, investido %s, patrimônio %s",
		sc.Expected["availableCash"], sc.Expected["restrictedBalance"], sc.Expected["investedAssets"], sc.Expected["totalNetWorth"])
	return checkDashboard(demo, sc)
}

// historyMonth é um mês da série do dashboard (/summary/history).
type historyMonth struct {
	Month      string `json:"month"`
	Income     string `json:"income"`
	Expense    string `json:"expense"`
	Refunds    string `json:"refunds"`
	NetExpense string `json:"netExpense"`
	Invested   string `json:"invested"`
	Net        string `json:"net"`
}

// scenarioTotals soma o cenário por tipo (em centavos): receitas, despesas, estornos e investimentos. É independente
// da data de hoje, então serve para conferir a série mensal do dashboard sem saber em que mês cada compra caiu.
func scenarioTotals(sc *scenario) (map[string]int64, error) {
	totals := map[string]int64{}
	for i, t := range sc.Transactions {
		c, err := centsOf(t.Amount)
		if err != nil {
			return nil, fmt.Errorf("transação %d: %w", i+1, err)
		}
		totals[t.Type] += c
	}
	return totals, nil
}

// checkDashboard confere o que o dashboard mostra: a soma de 24 meses da série bate com o cenário carregado, cada mês
// fecha as próprias contas (gasto líquido = despesas - estornos; sobra = receitas - gasto líquido) e o último mês da
// série é igual ao resumo mensal.
func checkDashboard(demo *apiClient, sc *scenario) error {
	var h struct {
		Months []historyMonth `json:"months"`
	}
	if _, err := demo.call("GET", "/api/v1/summary/history?months=24", nil, &h); err != nil {
		return err
	}
	want, err := scenarioTotals(sc)
	if err != nil {
		return err
	}
	var income, expense, refunds, invested int64
	for _, m := range h.Months {
		vals := map[string]*int64{"income": &income, "expense": &expense, "refunds": &refunds, "invested": &invested}
		for name, raw := range map[string]string{"income": m.Income, "expense": m.Expense, "refunds": m.Refunds, "invested": m.Invested} {
			c, err := centsOf(raw)
			if err != nil {
				return fmt.Errorf("série mensal %s: %w", m.Month, err)
			}
			*vals[name] += c
		}
		ne, _ := centsOf(m.NetExpense)
		e, _ := centsOf(m.Expense)
		r, _ := centsOf(m.Refunds)
		inc, _ := centsOf(m.Income)
		net, _ := centsOf(m.Net)
		if ne != e-r || net != inc-ne {
			return fmt.Errorf("série mensal %s: as contas do mês não fecham", m.Month)
		}
	}
	got := map[string]int64{"INCOME": income, "EXPENSE": expense, "REFUND": refunds, "INVESTMENT": invested}
	for typ, g := range got {
		if g != want[typ] {
			return fmt.Errorf("dashboard: a série de 24 meses soma %d centavos de %s, o cenário tem %d", g, typ, want[typ])
		}
	}
	if len(h.Months) == 0 {
		return fmt.Errorf("dashboard: a série mensal veio vazia")
	}
	last := h.Months[len(h.Months)-1]
	var cur historyMonth
	if _, err := demo.call("GET", "/api/v1/summary/monthly?month="+last.Month, nil, &cur); err != nil {
		return err
	}
	if cur.Income != last.Income || cur.NetExpense != last.NetExpense || cur.Net != last.Net || cur.Invested != last.Invested {
		return fmt.Errorf("dashboard: o resumo mensal de %s não bate com a série", last.Month)
	}
	ok("dashboard confere: 24 meses somam as receitas e despesas do cenário; mês atual (%s) igual ao resumo mensal", last.Month)
	return nil
}

// centsOf converte "1234.56" / "-0.05" em centavos inteiros (usado nos testes e na conferência do cenário).
func centsOf(s string) (int64, error) {
	neg := strings.HasPrefix(s, "-")
	s = strings.TrimPrefix(s, "-")
	whole, frac, found := strings.Cut(s, ".")
	if !found || len(frac) != 2 {
		return 0, fmt.Errorf("valor %q fora do formato 0.00", s)
	}
	w, err := strconv.ParseInt(whole, 10, 64)
	if err != nil {
		return 0, err
	}
	f, err := strconv.ParseInt(frac, 10, 64)
	if err != nil {
		return 0, err
	}
	c := w*100 + f
	if neg {
		c = -c
	}
	return c, nil
}

// importResp é o pedaço da resposta de importação que a conferência usa.
type importResp struct {
	ID             string `json:"id"`
	Status         string `json:"status"`
	NewRows        int    `json:"newRows"`
	DuplicateRows  int    `json:"duplicateRows"`
	PossibleRows   int    `json:"possibleRows"`
	InvalidRows    int    `json:"invalidRows"`
	AutoClassified int    `json:"autoClassified"`
	Suggested      int    `json:"suggested"`
	ImportedRows   int    `json:"importedRows"`
}

// seedImports importa dois extratos OFX sintéticos (o segundo repete 4 lançamentos do primeiro) numa conta própria e
// confere contagens e saldo. Usa a API de verdade: prévia, depois confirmação, como o usuário faria.
func seedImports(demo *apiClient, dir string) error {
	var acc struct {
		ID string `json:"id"`
	}
	if _, err := demo.call("POST", "/api/v1/accounts", map[string]string{"name": "Conta de extratos (importação)", "type": "CHECKING_ACCOUNT"}, &acc); err != nil {
		return err
	}
	steps := []struct {
		file        string
		wantNew     int
		wantDup     int
		wantBalance string
		wantAuto    int // linhas que o motor categoriza sozinho (regras padrão); -1 = não confere
		wantSuggest int // linhas só sugeridas (favorecidos que o seed ensinou)
	}{
		{"conta-corrente-1.ofx", 12, 0, "4179.53", 9, 2},
		{"conta-corrente-2.ofx", 3, 4, "3954.62", -1, -1},
	}
	for _, st := range steps {
		content, err := os.ReadFile(filepath.Join(dir, st.file))
		if err != nil {
			return err
		}
		var prev importResp
		if _, err := demo.upload("/api/v1/imports", map[string]string{"accountId": acc.ID}, st.file, content, &prev); err != nil {
			return fmt.Errorf("%s: %w", st.file, err)
		}
		if prev.NewRows != st.wantNew || prev.DuplicateRows != st.wantDup {
			return fmt.Errorf("%s: prévia com %d novas e %d duplicadas, esperado %d e %d", st.file, prev.NewRows, prev.DuplicateRows, st.wantNew, st.wantDup)
		}
		if st.wantAuto >= 0 && (prev.AutoClassified != st.wantAuto || prev.Suggested != st.wantSuggest) {
			return fmt.Errorf("%s: o motor categorizou %d sozinho e sugeriu %d, esperado %d e %d (a demonstração de classificação mudou?)",
				st.file, prev.AutoClassified, prev.Suggested, st.wantAuto, st.wantSuggest)
		}
		var done importResp
		if _, err := demo.call("POST", "/api/v1/imports/"+prev.ID+"/confirm", map[string]any{}, &done); err != nil {
			return fmt.Errorf("%s: %w", st.file, err)
		}
		if done.Status != "CONFIRMED" || done.ImportedRows != st.wantNew {
			return fmt.Errorf("%s: confirmação inesperada (%s, %d importadas)", st.file, done.Status, done.ImportedRows)
		}
		var bal struct {
			Balance string `json:"balance"`
		}
		if _, err := demo.call("GET", "/api/v1/accounts/"+acc.ID, nil, &bal); err != nil {
			return err
		}
		if bal.Balance != st.wantBalance {
			return fmt.Errorf("%s: saldo %s ≠ esperado %s", st.file, bal.Balance, st.wantBalance)
		}
		ok("%s importado: %d novas, %d duplicadas puladas, saldo %s", st.file, prev.NewRows, prev.DuplicateRows, bal.Balance)
	}
	var counts struct {
		Suggested int `json:"suggested"`
	}
	if _, err := demo.call("GET", "/api/v1/classification/review/count", nil, &counts); err != nil {
		return err
	}
	if counts.Suggested < 2 {
		return fmt.Errorf("esperava ao menos 2 sugestões na Revisão depois das importações, vieram %d", counts.Suggested)
	}
	ok("Revisão: %d sugestões aguardam confirmação (confirme na tela Revisão)", counts.Suggested)
	return nil
}

// demoCatalog guarda os ids das categorias e dos contextos padrão do usuário, pelo código estável.
type demoCatalog struct {
	category map[string]string
	context  map[string]string
}

func loadCatalog(demo *apiClient) (*demoCatalog, error) {
	var tree []idNode
	if _, err := demo.call("GET", "/api/v1/categories", nil, &tree); err != nil {
		return nil, err
	}
	var ctxs []idNode
	if _, err := demo.call("GET", "/api/v1/contexts", nil, &ctxs); err != nil {
		return nil, err
	}
	c := &demoCatalog{category: map[string]string{}, context: map[string]string{}}
	for _, n := range tree {
		if n.Code != nil {
			c.category[*n.Code] = n.ID
		}
		for _, f := range n.Children {
			if f.Code != nil {
				c.category[*f.Code] = f.ID
			}
		}
	}
	for _, n := range ctxs {
		if n.Code != nil {
			c.context[*n.Code] = n.ID
		}
	}
	return c, nil
}

func (c *demoCatalog) cat(code string) (string, error) {
	id, found := c.category[code]
	if !found {
		return "", fmt.Errorf("categoria padrão desconhecida: %s", code)
	}
	return id, nil
}

// demoRule e demoTeach descrevem a demonstração de classificação (tudo fictício): duas regras do usuário e três
// favorecidos com um histórico de decisões. Com isso, o extrato sintético mostra os três comportamentos do motor: regra
// do sistema aplica sozinha, favorecido com poucas decisões só sugere, favorecido novo não sugere nada.
var demoRules = []struct{ match, pattern, txType, category string }{
	{"CONTAINS", "mercadinho do ze", "EXPENSE", "FOOD_GROCERIES"},
	{"STARTS_WITH", "academia forca", "EXPENSE", "HEALTH_WELLNESS"},
}

var demoTeach = []struct {
	description, txType, category, context string
	times                                  int
}{
	{"PIX ENVIADO CAFÉ DA PRAÇA", "EXPENSE", "FOOD_SNACKS", "", 2},                // 2 decisões: 2/3, só sugere
	{"PIX ENVIADO JOANA EXEMPLO", "EXPENSE", "LEISURE_EVENTS", "RELATIONSHIP", 6}, // 6 decisões: 6/7, aplica sozinho
	{"PIX RECEBIDO JOANA EXEMPLO", "INCOME", "INCOME_OTHER", "", 1},               // outra direção, 1 decisão: só sugere
}

// seedClassification cria as regras e ensina os favorecidos da demonstração pela API (lançando com categoria, que é uma
// decisão do usuário), numa conta própria, e confere o resultado pelo teste de texto do motor.
func seedClassification(demo *apiClient) error {
	cat, err := loadCatalog(demo)
	if err != nil {
		return err
	}
	for _, r := range demoRules {
		id, err := cat.cat(r.category)
		if err != nil {
			return err
		}
		body := map[string]any{"matchType": r.match, "pattern": r.pattern, "transactionType": r.txType, "categoryId": id}
		if _, err := demo.call("POST", "/api/v1/categorization-rules", body, nil); err != nil {
			return fmt.Errorf("regra %q: %w", r.pattern, err)
		}
	}
	var acc struct {
		ID string `json:"id"`
	}
	if _, err := demo.call("POST", "/api/v1/accounts", map[string]string{"name": "Conta PIX (demonstração)", "type": "CHECKING_ACCOUNT"}, &acc); err != nil {
		return err
	}
	for _, t := range demoTeach {
		id, err := cat.cat(t.category)
		if err != nil {
			return err
		}
		for i := 0; i < t.times; i++ {
			body := map[string]any{"type": t.txType, "accountId": acc.ID, "amount": "10.00", "description": t.description, "categoryId": id}
			if t.context != "" {
				body["contextId"] = cat.context[t.context]
			}
			if _, err := demo.call("POST", "/api/v1/transactions", body, nil); err != nil {
				return fmt.Errorf("favorecido %q: %w", t.description, err)
			}
		}
	}

	// autoverificação: o motor precisa decidir exatamente como a demonstração promete
	checks := []struct {
		text, txType, source, decision string
	}{
		{"COMPRA MERCADINHO DO ZE 12/03", "EXPENSE", "RULE", "AUTO"},
		{"PIX ENVIADO JOANA EXEMPLO", "EXPENSE", "RECIPIENT", "AUTO"},
		{"PIX ENVIADO CAFÉ DA PRAÇA", "EXPENSE", "RECIPIENT", "SUGGEST"},
		{"PIX RECEBIDO JOANA EXEMPLO", "INCOME", "RECIPIENT", "SUGGEST"},
		{"COMPRA POSTO ABC", "EXPENSE", "DEFAULT_RULE", "AUTO"},
	}
	for _, c := range checks {
		var out struct {
			Category struct {
				Source   string `json:"source"`
				Decision string `json:"decision"`
			} `json:"category"`
		}
		if _, err := demo.call("POST", "/api/v1/categorization-rules/test", map[string]string{"description": c.text, "type": c.txType}, &out); err != nil {
			return err
		}
		if out.Category.Source != c.source || out.Category.Decision != c.decision {
			return fmt.Errorf("classificação de %q: %s/%s, esperado %s/%s", c.text, out.Category.Source, out.Category.Decision, c.source, c.decision)
		}
	}
	ok("%d regras e %d favorecidos de demonstração criados; o motor decide como esperado", len(demoRules), len(demoTeach))
	return nil
}
