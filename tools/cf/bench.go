package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

// k6Image fica fixada: o resultado do bench precisa ser comparável entre execuções.
const (
	k6Image    = "grafana/k6:2.4.0"
	benchEmail = "bench@contaforte.local"
)

// benchRow é o p95 de uma rota, em milissegundos.
type benchRow struct {
	Route string
	P95   float64
	Avg   float64
}

// parseK6 lê o summary.json do handleSummary e devolve p95 e média por rota (tag endpoint). Aceita as duas formas
// de JSON do k6 (valores em "values" ou direto no objeto da métrica).
func parseK6(raw []byte) ([]benchRow, error) {
	var doc struct {
		Metrics map[string]map[string]json.RawMessage `json:"metrics"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, err
	}
	const prefix, suffix = "http_req_duration{endpoint:", "}"
	var rows []benchRow
	for name, metric := range doc.Metrics {
		if !strings.HasPrefix(name, prefix) || !strings.HasSuffix(name, suffix) {
			continue
		}
		vals := map[string]float64{}
		if v, ok := metric["values"]; ok {
			_ = json.Unmarshal(v, &vals)
		} else {
			for k, v := range metric {
				var f float64
				if json.Unmarshal(v, &f) == nil {
					vals[k] = f
				}
			}
		}
		p95, found := vals["p(95)"]
		if !found {
			return nil, fmt.Errorf("métrica %s sem p(95)", name)
		}
		rows = append(rows, benchRow{Route: strings.TrimSuffix(strings.TrimPrefix(name, prefix), suffix), P95: p95, Avg: vals["avg"]})
	}
	if len(rows) == 0 {
		return nil, fmt.Errorf("o resultado do k6 não tem métricas por rota")
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].Route < rows[j].Route })
	return rows, nil
}

// benchInsertSQL cria `rows` transações sintéticas para o usuário de bench, espalhadas por cerca de um ano.
func benchInsertSQL(email string, rows int) (string, error) {
	if !demoEmailRe.MatchString(email) {
		return "", fmt.Errorf("e-mail de bench inválido: %q", email)
	}
	return fmt.Sprintf(`insert into transactions (user_id, account_id, type, amount, description, merchant, category_id, occurred_at)
select u.id, a.id,
       case when n %% 7 = 0 then 'INCOME' else 'EXPENSE' end,
       round((((n * 7919) %% 99000) / 100.0) + 1, 2),
       'Compra ' || n, 'Loja ' || (n %% 50),
       (select id from transaction_categories
         where code = (array['FOOD_GROCERIES','FOOD_RESTAURANT','TRANSPORT_FUEL','HEALTH_PHARMACY','LEISURE_STREAMING'])[1 + n %% 5]),
       now() - (n * interval '50 minutes')
  from generate_series(1, %d) n
  join users u on lower(u.email) = '%s'
  join lateral (select id from accounts where user_id = u.id order by name limit 1 offset (n %% 3)) a on true`, rows, email), nil
}

func cmdBench(args []string) error {
	fs := flag.NewFlagSet("bench", flag.ExitOnError)
	rows := fs.Int("rows", 10000, "quantidade de transações sintéticas")
	duration := fs.String("duration", "20s", "duração do teste de carga")
	vus := fs.Int("vus", 10, "usuários virtuais simultâneos")
	fs.Parse(args)
	if *rows < 100 || *rows > 200000 {
		return fmt.Errorf("--rows deve estar entre 100 e 200000")
	}

	root, err := findRoot()
	if err != nil {
		return err
	}
	env, err := parseEnv(filepath.Join(root, ".env"))
	if err != nil {
		return fmt.Errorf(".env não encontrado: rode ./cf init primeiro")
	}
	if tunnelEnabled(env) {
		return fmt.Errorf("recusado: o perfil tunnel está ativo. O bench cria dados sintéticos e só roda em ambiente local")
	}
	port := env["GATEWAY_PORT"]
	if port == "" {
		port = "8080"
	}
	base := "http://127.0.0.1:" + port
	if !httpOK(base + "/healthz") {
		return fmt.Errorf("o gateway não responde em 127.0.0.1:%s: suba a stack com ./cf up", port)
	}

	delSQL, err := deleteDemoSQL(benchEmail)
	if err != nil {
		return err
	}
	cleanup := func() {
		if err := psql(root, env, delSQL); err != nil {
			fmt.Fprintln(os.Stderr, "aviso: não consegui remover o usuário de bench:", err)
		}
	}
	cleanup() // sobra de uma execução interrompida
	defer cleanup()

	admin := newAPI(base)
	if _, err := admin.login(env["ADMIN_EMAIL"], env["ADMIN_PASSWORD"]); err != nil {
		return fmt.Errorf("login do administrador falhou: %w", err)
	}
	pass, err := randomHex(12)
	if err != nil {
		return err
	}
	if _, err := admin.call("POST", "/api/v1/admin/users", map[string]string{"email": benchEmail, "password": pass}, nil); err != nil {
		return err
	}
	user := newAPI(base)
	if _, err := user.login(benchEmail, pass); err != nil {
		return err
	}
	for _, a := range []map[string]string{
		{"name": "Bench A", "type": "CHECKING_ACCOUNT"}, {"name": "Bench B", "type": "SAVINGS_ACCOUNT"}, {"name": "Bench C", "type": "CASH"},
	} {
		if _, err := user.call("POST", "/api/v1/accounts", a, nil); err != nil {
			return err
		}
	}
	insert, err := benchInsertSQL(benchEmail, *rows)
	if err != nil {
		return err
	}
	if err := psql(root, env, insert); err != nil {
		return fmt.Errorf("carga sintética falhou: %w", err)
	}
	ok("%d transações sintéticas inseridas para %s (removidas ao final)", *rows, benchEmail)

	out, err := os.MkdirTemp("", "cf-bench-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(out)
	if err := os.Chmod(out, 0o777); err != nil { // o k6 roda como usuário não-root dentro do container
		return err
	}
	fmt.Printf("Rodando k6 (%d usuários, %s) contra %s ...\n", *vus, *duration, base)
	cmd := exec.Command("docker", "run", "--rm", "--network", "host",
		"-e", "BASE_URL="+base, "-e", "TOKEN="+user.token, "-e", fmt.Sprintf("VUS=%d", *vus), "-e", "DURATION="+*duration,
		"-v", filepath.Join(root, "tools", "loadtest")+":/scripts:ro", "-v", out+":/out",
		k6Image, "run", "--quiet", "/scripts/k6.js")
	cmd.Stderr = os.Stderr
	runErr := cmd.Run() // limiar estourado também sai com erro; os números ainda são lidos abaixo

	raw, err := os.ReadFile(filepath.Join(out, "summary.json"))
	if err != nil {
		return fmt.Errorf("o k6 não gerou resultado: %w (erro do k6: %v)", err, runErr)
	}
	result, err := parseK6(raw)
	if err != nil {
		return err
	}
	fmt.Printf("\nLatência com %d transações no banco (p95 / média):\n", *rows)
	for _, r := range result {
		fmt.Printf("  %-16s %8.1f ms / %7.1f ms\n", r.Route, r.P95, r.Avg)
	}
	if runErr != nil {
		return fmt.Errorf("algum limiar do k6 foi estourado (p95 < 1000 ms ou falhas > 1%%): veja os números acima")
	}
	fmt.Println("\nTodos os limiares respeitados (p95 < 1000 ms, falhas < 1%).")
	return nil
}
