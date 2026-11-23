package main

import (
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

const scenarioPath = "../../datasets/synthetic/scenario.json"

func str(s string) *string { return &s }

// validScenario é a base dos casos negativos: cada teste estraga uma coisa só.
func validScenario() *scenario {
	return &scenario{
		Version: 1, Fictional: true, DemoEmail: "demo@contaforte.local",
		Accounts: []scAccount{
			{Key: "cc", Name: "Corrente", Type: "CHECKING_ACCOUNT", OpeningBalance: "100.00"},
			{Key: "inv", Name: "Corretora", Type: "INVESTMENT_ACCOUNT", OpeningBalance: "0.00"},
			{Key: "pp", Name: "Poupança", Type: "SAVINGS_ACCOUNT", OpeningBalance: "0.00"},
		},
		Transactions: []scTx{{DaysAgo: 3, Hour: 10, Type: "EXPENSE", Account: "cc", Amount: "10.00", Description: "x"}},
	}
}

func TestScenarioCommittedIsValidAndConsistent(t *testing.T) {
	sc, err := loadScenario(scenarioPath)
	if err != nil {
		t.Fatalf("o cenário versionado é inválido: %v", err)
	}
	if n := len(sc.Transactions); n < 120 || n > 200 {
		t.Fatalf("esperava de 120 a 200 transações, veio %d", n)
	}
	if !sc.Fictional || sc.Seed == 0 {
		t.Fatal("o cenário precisa ser fictício e ter semente")
	}

	// ordem cronológica: do mais antigo ao mais recente
	sorted := sort.SliceIsSorted(sc.Transactions, func(i, j int) bool {
		a, b := sc.Transactions[i], sc.Transactions[j]
		return a.DaysAgo > b.DaysAgo || (a.DaysAgo == b.DaysAgo && a.Hour < b.Hour)
	})
	if !sorted {
		t.Fatal("as transações precisam estar em ordem cronológica")
	}

	// recalcula os saldos em Go (implementação independente do gerador Python) e compara com o "expected"
	kind := map[string]string{}
	bal := map[string]int64{}
	for _, a := range sc.Accounts {
		kind[a.Key] = a.Type
		c, err := centsOf(a.OpeningBalance)
		if err != nil {
			t.Fatal(err)
		}
		bal[a.Key] = c
	}
	sign := map[string]int64{"INCOME": 1, "REFUND": 1, "EXPENSE": -1, "TRANSFER": -1, "INVESTMENT": -1}
	for _, tx := range sc.Transactions {
		c, _ := centsOf(tx.Amount)
		bal[tx.Account] += sign[tx.Type] * c
		if tx.Counterpart != nil {
			bal[*tx.Counterpart] += c
		}
		for k, v := range bal {
			if kind[k] != "CREDIT_CARD" && v < 0 {
				t.Fatalf("conta %s ficou negativa (%d) após %q", k, v, tx.Description)
			}
		}
	}
	var available, restricted, invested, card int64
	for k, v := range bal {
		switch kind[k] {
		case "CHECKING_ACCOUNT", "SAVINGS_ACCOUNT", "CASH":
			available += v
		case "VA", "VR":
			restricted += v
		case "INVESTMENT_ACCOUNT":
			invested += v
		case "CREDIT_CARD":
			card += v
		}
	}
	want := map[string]int64{
		"availableCash": available, "restrictedBalance": restricted, "investedAssets": invested,
		"creditCardBalance": card, "totalNetWorth": available + restricted + invested + card,
	}
	for k, w := range want {
		got, err := centsOf(sc.Expected[k])
		if err != nil || got != w {
			t.Errorf("expected.%s = %s, recalculado %d centavos", k, sc.Expected[k], w)
		}
	}
	// a promessa central: VA/VR não entram no disponível
	if restricted == 0 || restricted == available {
		t.Error("o cenário precisa ter saldo restrito (VA/VR) distinto do disponível")
	}
}

func TestScenarioCoversEveryTransactionTypeAndAccountType(t *testing.T) {
	sc, err := loadScenario(scenarioPath)
	if err != nil {
		t.Fatal(err)
	}
	types := map[string]bool{}
	for _, tx := range sc.Transactions {
		types[tx.Type] = true
	}
	for tp := range txTypes {
		if !types[tp] {
			t.Errorf("o cenário não tem nenhuma transação %s", tp)
		}
	}
	accTypes := map[string]bool{}
	for _, a := range sc.Accounts {
		accTypes[a.Type] = true
	}
	for _, tp := range []string{"CHECKING_ACCOUNT", "SAVINGS_ACCOUNT", "CASH", "CREDIT_CARD", "VA", "VR", "INVESTMENT_ACCOUNT"} {
		if !accTypes[tp] {
			t.Errorf("o cenário não tem conta %s", tp)
		}
	}
}

func TestValidateScenarioRecusa(t *testing.T) {
	cases := map[string]func(*scenario){
		"não fictício":              func(s *scenario) { s.Fictional = false },
		"versão desconhecida":       func(s *scenario) { s.Version = 2 },
		"e-mail demo com aspas":     func(s *scenario) { s.DemoEmail = "x'; drop table users;--@a.b" },
		"conta duplicada":           func(s *scenario) { s.Accounts = append(s.Accounts, s.Accounts[0]) },
		"tipo de conta inválido":    func(s *scenario) { s.Accounts[0].Type = "POUPANCA_MAGICA" },
		"saldo inicial sem 2 casas": func(s *scenario) { s.Accounts[0].OpeningBalance = "10.5" },
		"tipo de transação":         func(s *scenario) { s.Transactions[0].Type = "DOACAO" },
		"conta desconhecida":        func(s *scenario) { s.Transactions[0].Account = "fantasma" },
		"valor zero":                func(s *scenario) { s.Transactions[0].Amount = "0.00" },
		"valor negativo":            func(s *scenario) { s.Transactions[0].Amount = "-5.00" },
		"valor com 3 casas":         func(s *scenario) { s.Transactions[0].Amount = "1.005" },
		"valor com vírgula":         func(s *scenario) { s.Transactions[0].Amount = "10,50" },
		"transferência sem destino": func(s *scenario) { s.Transactions[0].Type = "TRANSFER" },
		"despesa com destino":       func(s *scenario) { s.Transactions[0].Counterpart = str("pp") },
		"destino igual à origem": func(s *scenario) {
			s.Transactions[0].Type, s.Transactions[0].Counterpart = "TRANSFER", str("cc")
		},
		"investimento fora de conta de investimento": func(s *scenario) {
			s.Transactions[0].Type, s.Transactions[0].Counterpart = "INVESTMENT", str("pp")
		},
		"dias negativos": func(s *scenario) { s.Transactions[0].DaysAgo = -1 },
		"dias demais":    func(s *scenario) { s.Transactions[0].DaysAgo = 400 },
		"hora inválida":  func(s *scenario) { s.Transactions[0].Hour = 24 },
	}
	if err := validateScenario(validScenario()); err != nil {
		t.Fatalf("a base deveria ser válida: %v", err)
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			s := validScenario()
			mutate(s)
			if err := validateScenario(s); err == nil {
				t.Fatal("deveria ter sido recusado")
			}
		})
	}
}

func TestValidateScenarioAceitaInvestimentoEmContaDeInvestimento(t *testing.T) {
	s := validScenario()
	s.Transactions[0].Type, s.Transactions[0].Counterpart = "INVESTMENT", str("inv")
	if err := validateScenario(s); err != nil {
		t.Fatal(err)
	}
}

func TestLoadScenarioRecusaCampoDesconhecido(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "s.json")
	writeFile(t, p, `{"version":1,"fictional":true,"demoEmail":"a@b.c","accounts":[],"transactions":[],"campoTypo":1}`)
	if _, err := loadScenario(p); err == nil || !strings.Contains(err.Error(), "inválido") {
		t.Fatalf("esperava recusa por campo desconhecido, veio %v", err)
	}
}

func TestOccurredAtUsaOFusoDeSaoPaulo(t *testing.T) {
	// 01:00 UTC de 23/11 ainda é 22:00 de 22/11 em São Paulo (UTC-3)
	now := time.Date(2026, 11, 23, 1, 0, 0, 0, time.UTC)
	cases := []struct {
		daysAgo, hour int
		want          string
	}{
		{0, 9, "2026-11-22T12:00:00Z"},   // hoje (em SP = 22/11), 09:00 SP
		{1, 9, "2026-11-21T12:00:00Z"},   // ontem
		{30, 23, "2026-10-24T02:00:00Z"}, // 23/10 23:00 SP vira 02:00 UTC do dia seguinte
		{90, 0, "2026-08-24T03:00:00Z"},  // 90 dias atrás, meia-noite SP
	}
	for _, c := range cases {
		if got := occurredAt(now, c.daysAgo, c.hour); got != c.want {
			t.Errorf("occurredAt(daysAgo=%d, hour=%d) = %s, esperado %s", c.daysAgo, c.hour, got, c.want)
		}
	}
}

func TestDeleteDemoSQLSoApagaOUsuarioDemoValidado(t *testing.T) {
	sql, err := deleteDemoSQL("demo@contaforte.local")
	if err != nil {
		t.Fatal(err)
	}
	if sql != "delete from users where lower(email) = lower('demo@contaforte.local')" {
		t.Fatalf("SQL inesperado: %s", sql)
	}
	for _, ruim := range []string{"", "x'; drop table users;--", "a b@c.d", "demo@x.y' or '1'='1", "DEMO@UPPER.COM"} {
		if _, err := deleteDemoSQL(ruim); err == nil {
			t.Errorf("deveria recusar %q", ruim)
		}
	}
}

func TestCentsOf(t *testing.T) {
	for in, want := range map[string]int64{"0.00": 0, "10.50": 1050, "-0.05": -5, "999999999999.99": 99999999999999} {
		if got, err := centsOf(in); err != nil || got != want {
			t.Errorf("centsOf(%q) = %d, %v; esperado %d", in, got, err, want)
		}
	}
	for _, ruim := range []string{"10", "10.5", "1,00", "abc", ""} {
		if _, err := centsOf(ruim); err == nil {
			t.Errorf("centsOf(%q) deveria falhar", ruim)
		}
	}
}

func TestParseK6AceitaAsDuasFormasDeJSON(t *testing.T) {
	antigo := `{"metrics":{"http_req_duration{endpoint:lista}":{"type":"trend","values":{"avg":12.5,"p(95)":30.25}},
	  "http_req_duration{endpoint:resumo}":{"type":"trend","values":{"avg":5,"p(95)":9}},"http_reqs":{"values":{"count":10}}}}`
	novo := `{"metrics":{"http_req_duration{endpoint:lista}":{"avg":12.5,"p(95)":30.25},"http_req_failed":{"rate":0}}}`
	for nome, raw := range map[string]string{"values": antigo, "direto": novo} {
		rows, err := parseK6([]byte(raw))
		if err != nil {
			t.Fatalf("%s: %v", nome, err)
		}
		if rows[0].Route != "lista" || rows[0].P95 != 30.25 || rows[0].Avg != 12.5 {
			t.Errorf("%s: linha inesperada %+v", nome, rows[0])
		}
	}
	rows, _ := parseK6([]byte(antigo))
	if len(rows) != 2 || rows[0].Route != "lista" || rows[1].Route != "resumo" {
		t.Errorf("esperava duas rotas em ordem alfabética, veio %+v", rows)
	}
	if _, err := parseK6([]byte(`{"metrics":{"http_reqs":{"values":{"count":1}}}}`)); err == nil {
		t.Error("sem métricas por rota deveria falhar")
	}
	if _, err := parseK6([]byte(`não é json`)); err == nil {
		t.Error("JSON inválido deveria falhar")
	}
}

func TestBenchInsertSQLValidaOEmailEUsaAQuantidadePedida(t *testing.T) {
	sql, err := benchInsertSQL("bench@contaforte.local", 5000)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(sql, "generate_series(1, 5000)") || !strings.Contains(sql, "lower(u.email) = 'bench@contaforte.local'") {
		t.Errorf("SQL inesperado:\n%s", sql)
	}
	if _, err := benchInsertSQL("x'); drop table users;--", 10); err == nil {
		t.Error("e-mail com aspas deveria ser recusado")
	}
}
