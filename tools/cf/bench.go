package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
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
	importRows := fs.Int("import-rows", 20000, "linhas do CSV usado para medir a importação (0 desliga)")
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

	if *importRows > 0 {
		if err := benchImport(root, env, user, *importRows); err != nil {
			return err
		}
	}

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

// benchCSV gera um CSV sintético (data;descrição;valor) com n linhas, todas distintas entre si. As descrições imitam um
// extrato de verdade para o motor de classificação trabalhar: 2 em cada 5 linhas são reconhecidas (uma pela regra padrão
// "supermercado", outra pela regra do usuário "posto bench") e as demais não são.
func benchCSV(n int) []byte {
	var b strings.Builder
	b.WriteString("Data;Descricao;Valor\n")
	base := time.Now().In(spZone) // datas sempre no passado: o parser recusa data futura
	for i := 0; i < n; i++ {
		d := base.AddDate(0, 0, -(i % 700))
		cents := (i*7919)%99000 + 100
		var desc string
		switch i % 5 {
		case 0:
			desc = fmt.Sprintf("COMPRA SUPERMERCADO BENCH %d", i)
		case 1:
			desc = fmt.Sprintf("PIX ENVIADO FAVORECIDO BENCH %d", i%40)
		case 2:
			desc = fmt.Sprintf("COMPRA POSTO BENCH %d", i)
		case 3:
			desc = fmt.Sprintf("IMPORTA %d LOJA %d", i, i%50)
		default:
			desc = fmt.Sprintf("DEBITO DIVERSOS %d", i)
		}
		fmt.Fprintf(&b, "%s;%s;-%d,%02d\n", d.Format("02/01/2006"), desc, cents/100, cents%100)
	}
	return []byte(b.String())
}

// benchExpectedAuto é quantas das n linhas de benchCSV o motor categoriza sozinho (supermercado e posto).
func benchExpectedAuto(n int) int {
	auto := 0
	for i := 0; i < n; i++ {
		if i%5 == 0 || i%5 == 2 {
			auto++
		}
	}
	return auto
}

// benchRules cria as regras do usuário do bench: 50 que não casam com nada (o motor precisa percorrê-las em toda linha,
// que é o pior caso de custo) e uma que casa ("posto bench").
func benchRules(user *apiClient) error {
	cat, err := loadCatalog(user)
	if err != nil {
		return err
	}
	fuel, err := cat.cat("TRANSPORT_FUEL")
	if err != nil {
		return err
	}
	for i := 0; i < 50; i++ {
		body := map[string]any{"matchType": "CONTAINS", "pattern": fmt.Sprintf("regra sem uso numero %d", i), "categoryId": fuel}
		if _, err := user.call("POST", "/api/v1/categorization-rules", body, nil); err != nil {
			return err
		}
	}
	_, err = user.call("POST", "/api/v1/categorization-rules", map[string]any{"matchType": "CONTAINS", "pattern": "posto bench", "transactionType": "EXPENSE", "categoryId": fuel}, nil)
	return err
}

// memMiB lê o uso atual de memória do contêiner do backend (docker stats), em MiB.
func memMiB(root string) float64 {
	cmd := exec.Command("docker", "compose", "stats", "--no-stream", "--format", "{{.MemUsage}}", "backend")
	cmd.Dir = root
	out, err := cmd.Output()
	if err != nil {
		return -1
	}
	f := strings.Fields(string(out))
	if len(f) == 0 {
		return -1
	}
	v := strings.TrimSuffix(f[0], "MiB")
	x, err := strconv.ParseFloat(v, 64)
	if err != nil {
		return -1
	}
	return x
}

// benchImport mede prévia, confirmação e desfazer de um CSV grande pela API e a memória do backend durante isso.
func benchImport(root string, env map[string]string, user *apiClient, rows int) error {
	var acc struct {
		ID string `json:"id"`
	}
	if _, err := user.call("POST", "/api/v1/accounts", map[string]string{"name": "Bench importação", "type": "CHECKING_ACCOUNT"}, &acc); err != nil {
		return err
	}
	if err := benchRules(user); err != nil {
		return fmt.Errorf("regras do bench: %w", err)
	}
	csv := benchCSV(rows)
	if len(csv) > 5*1024*1024 {
		return fmt.Errorf("o CSV de %d linhas passa de 5 MB (%d bytes): use --import-rows menor", rows, len(csv))
	}
	mapping := `{"delimiter":";","hasHeader":"true","dateColumn":"#1","dateFormat":"DMY_SLASH","descriptionColumn":"#2","amountColumn":"#3"}`
	fields := map[string]string{"accountId": acc.ID, "mapping": mapping}

	memBefore := memMiB(root)
	var peak float64
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			select {
			case <-stop:
				return
			case <-time.After(500 * time.Millisecond):
				if m := memMiB(root); m > peak {
					peak = m
				}
			}
		}
	}()

	t0 := time.Now()
	var prev importResp
	_, err := user.upload("/api/v1/imports", fields, "bench.csv", csv, &prev)
	tPreview := time.Since(t0)
	if err != nil {
		close(stop)
		<-done
		return fmt.Errorf("prévia de %d linhas falhou: %w", rows, err)
	}
	t1 := time.Now()
	var conf importResp
	_, err = user.call("POST", "/api/v1/imports/"+prev.ID+"/confirm", map[string]any{}, &conf)
	tConfirm := time.Since(t1)
	if err != nil {
		close(stop)
		<-done
		return fmt.Errorf("confirmação falhou: %w", err)
	}
	t2 := time.Now()
	_, err = user.call("POST", "/api/v1/imports/"+prev.ID+"/rollback", nil, nil)
	tRollback := time.Since(t2)
	close(stop)
	<-done
	if err != nil {
		return fmt.Errorf("desfazer falhou: %w", err)
	}
	if want := benchExpectedAuto(rows); prev.AutoClassified != want {
		return fmt.Errorf("o motor categorizou %d linhas sozinho, esperado %d (supermercado e posto)", prev.AutoClassified, want)
	}
	if prev.NewRows != rows || conf.ImportedRows != rows {
		return fmt.Errorf("contagens inesperadas: %d novas, %d duplicadas, %d possíveis, %d inválidas, %d importadas, esperado %d novas", prev.NewRows, prev.DuplicateRows, prev.PossibleRows, prev.InvalidRows, conf.ImportedRows, rows)
	}

	fmt.Printf("\nImportação de %d linhas (CSV de %.0f KB):\n", rows, float64(len(csv))/1024)
	fmt.Printf("  prévia      %7.2f s (com 51 regras do usuário e as do sistema; %d de %d linhas categorizadas sozinhas)\n  confirmação %7.2f s\n  desfazer    %7.2f s\n",
		tPreview.Seconds(), prev.AutoClassified, rows, tConfirm.Seconds(), tRollback.Seconds())
	fmt.Printf("  memória do backend: %.0f MiB antes, pico %.0f MiB (limite 768 MiB)\n", memBefore, peak)
	if tPreview > 10*time.Second {
		return fmt.Errorf("a prévia de %d linhas levou %.1f s (meta: menos de 10 s)", rows, tPreview.Seconds())
	}
	if peak > 700 {
		return fmt.Errorf("memória do backend chegou a %.0f MiB, perto do limite de 768", peak)
	}
	ok("prévia dentro de 10 s e memória abaixo do limite")
	return nil
}
