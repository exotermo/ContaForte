package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
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
