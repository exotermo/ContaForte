package main

import (
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

	fmt.Println()
	if problems > 0 {
		return fmt.Errorf("%d problema(s) encontrado(s)", problems)
	}
	fmt.Println("Tudo certo.")
	return nil
}
