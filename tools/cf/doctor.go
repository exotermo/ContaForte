package main

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// weakSecret diz se um segredo é fraco: vazio, placeholder ou curto demais.
func weakSecret(v string) bool {
	l := strings.ToLower(v)
	return len(v) < 24 || l == "change-me" || l == "changeme" || l == "password" || l == "admin"
}

// publishedOutsideLoopback devolve as portas publicadas que não estão restritas a 127.0.0.1.
func publishedOutsideLoopback(svcs []svcInfo) []string {
	var bad []string
	for _, s := range svcs {
		for _, p := range s.Publishers {
			if p.PublishedPort == 0 {
				continue // exposta só na rede Docker, não no host
			}
			if p.URL != "127.0.0.1" && p.URL != "::1" {
				bad = append(bad, fmt.Sprintf("%s:%d (bind %s)", s.Service, p.PublishedPort, p.URL))
			}
		}
	}
	return bad
}

// jwtKeyBits valida a chave JWT do .env (PKCS#8 RSA em Base64) e devolve o tamanho em bits.
func jwtKeyBits(b64 string) (int, error) {
	der, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return 0, err
	}
	key, err := x509.ParsePKCS8PrivateKey(der)
	if err != nil {
		return 0, err
	}
	rk, ok := key.(*rsa.PrivateKey)
	if !ok {
		return 0, fmt.Errorf("a chave não é RSA")
	}
	return rk.N.BitLen(), nil
}

func cmdDoctor() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	problems := 0
	check := func(good bool, okMsg, failMsg string) {
		if good {
			ok("%s", okMsg)
		} else {
			fail("%s", failMsg)
			problems++
		}
	}

	fmt.Println("Segredos")
	envPath := filepath.Join(root, ".env")
	env, err := parseEnv(envPath)
	if err != nil {
		fail(".env ausente: rode ./cf init")
		return fmt.Errorf("diagnóstico interrompido")
	}
	if st, err := os.Stat(envPath); err == nil {
		check(st.Mode().Perm() == 0o600, ".env com permissão 600", fmt.Sprintf(".env com permissão %o (esperado 600)", st.Mode().Perm()))
	}
	var weak []string
	for _, k := range secretKeys {
		if v, present := env[k]; present && weakSecret(v) {
			weak = append(weak, k)
		}
	}
	check(len(weak) == 0, "segredos fortes", "segredos fracos ou placeholder: "+strings.Join(weak, ", "))
	if bits, err := jwtKeyBits(env[jwtKeyName]); err != nil {
		check(false, "", "chave JWT ausente ou inválida: rode ./cf init")
	} else {
		check(bits >= 2048, fmt.Sprintf("chave JWT RSA de %d bits", bits), fmt.Sprintf("chave JWT fraca (%d bits)", bits))
	}
	if _, err := exec.LookPath("git"); err == nil {
		ignored := exec.Command("git", "-C", root, "check-ignore", "-q", ".env").Run() == nil
		check(ignored, ".env ignorado pelo Git", ".env NÃO está no .gitignore")
	}

	fmt.Println("\nRecursos")
	if mb, err := availRAMMB(); err == nil {
		if mb < 2048 {
			warn("RAM disponível %d MB (< 2048): a stack de monitoramento pode ser morta por OOM", mb)
		} else {
			ok("RAM disponível %d MB", mb)
		}
	}

	fmt.Println("\nRede e exposição")
	project := env["COMPOSE_PROJECT_NAME"]
	if project == "" {
		project = "contaforte"
	}
	for _, n := range []string{"app", "obs"} {
		out, err := exec.Command("docker", "network", "inspect", project+"_"+n, "--format", "{{.Internal}}").Output()
		if err != nil {
			warn("rede %s ainda não existe (stack parada ou sem serviços nela)", n)
			continue
		}
		check(strings.TrimSpace(string(out)) == "true", "rede "+n+" é internal (sem saída à Internet)", "rede "+n+" NÃO é internal")
	}

	psOut, err := composeOut(root, "ps", "--format", "json")
	svcs, perr := parsePS(psOut)
	if err != nil || perr != nil || len(svcs) == 0 {
		warn("stack parada: pulando verificações de portas e egress (rode ./cf up)")
	} else {
		bad := publishedOutsideLoopback(svcs)
		check(len(bad) == 0, "nenhuma porta exposta fora de 127.0.0.1", "portas expostas fora do loopback: "+strings.Join(bad, ", "))
		for _, s := range svcs {
			if s.Service != "postgres" {
				continue
			}
			_, e := composeOut(root, "exec", "-T", "postgres", "wget", "-T", "3", "-q", "-O", "/dev/null", "https://example.com")
			check(e != nil, "postgres não alcança a Internet", "postgres ALCANÇA a Internet: isolamento quebrado")
		}
		unhealthy := 0
		for _, s := range svcs {
			if s.State != "running" || (s.Health != "" && s.Health != "healthy") {
				unhealthy++
			}
		}
		check(unhealthy == 0, "todos os serviços saudáveis", fmt.Sprintf("%d serviço(s) com problema (veja ./cf status)", unhealthy))
	}

	problems += doctorMonitoring(root)

	fmt.Println()
	if problems > 0 {
		return fmt.Errorf("%d problema(s) encontrado(s)", problems)
	}
	fmt.Println("Tudo certo.")
	return nil
}

// promTargets interpreta /api/v1/targets do Prometheus e devolve os alvos que NÃO estão "up".
func promTargets(raw string) (total int, down []string, err error) {
	var r struct {
		Data struct {
			ActiveTargets []struct {
				Labels    map[string]string `json:"labels"`
				Health    string            `json:"health"`
				LastError string            `json:"lastError"`
			} `json:"activeTargets"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &r); err != nil {
		return 0, nil, err
	}
	for _, t := range r.Data.ActiveTargets {
		if t.Health != "up" {
			down = append(down, t.Labels["job"])
		}
	}
	return len(r.Data.ActiveTargets), down, nil
}

// doctorMonitoring confere o monitoramento: alvos do Prometheus e logs chegando ao Loki.
func doctorMonitoring(root string) (problems int) {
	fmt.Println("\nMonitoramento")
	raw, err := composeOut(root, "exec", "-T", "prometheus", "wget", "-qO-", "http://127.0.0.1:9090/api/v1/targets")
	if err != nil {
		warn("Prometheus indisponível (stack parada?): pulando checagens de monitoramento")
		return 0
	}
	total, down, err := promTargets(raw)
	switch {
	case err != nil:
		fail("resposta inválida do Prometheus: %v", err)
		problems++
	case len(down) > 0:
		fail("%d de %d alvos fora do ar: %s", len(down), total, strings.Join(down, ", "))
		problems++
	default:
		ok("todos os %d alvos do Prometheus estão no ar", total)
	}
	labels, err := composeOut(root, "exec", "-T", "grafana", "wget", "-qO-", "http://loki:3100/loki/api/v1/label/service/values")
	if err != nil || !strings.Contains(labels, `"data":["`) {
		fail("nenhum log chegou ao Loki (veja ./cf logs alloy)")
		problems++
	} else {
		ok("logs chegando ao Loki")
	}
	return problems
}
