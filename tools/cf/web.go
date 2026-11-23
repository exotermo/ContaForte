package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

const (
	nodeImage       = "node:22-alpine"
	playwrightImage = "mcr.microsoft.com/playwright:v1.65.1-noble" // deve casar com @playwright/test em apps/web/e2e
)

func cmdTest(args []string) error {
	target := "all"
	if len(args) > 0 {
		target = args[0]
	}
	switch target {
	case "backend":
		return cmdTestBackend()
	case "web":
		return cmdTestWeb()
	case "go":
		return cmdTestGo()
	case "all":
		if err := cmdTestGo(); err != nil {
			return fmt.Errorf("go: %w", err)
		}
		if err := cmdTestBackend(); err != nil {
			return fmt.Errorf("backend: %w", err)
		}
		if err := cmdTestWeb(); err != nil {
			return fmt.Errorf("web: %w", err)
		}
		return nil
	default:
		return fmt.Errorf("alvo desconhecido %q (use backend, web, go ou all)", target)
	}
}

func runDocker(args ...string) error {
	cmd := exec.Command("docker", args...)
	cmd.Stdout, cmd.Stderr, cmd.Stdin = os.Stdout, os.Stderr, os.Stdin
	return cmd.Run()
}

// cmdTestWeb copia o código para dentro do container (sem sujar o host com node_modules)
// e roda lint, checagem de tipos e testes unitários/de componente.
func cmdTestWeb() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	script := "cp -r /src /work && cd /work && rm -rf node_modules .next && " +
		"npm ci --no-audit --no-fund --loglevel=error && npm run lint && npm run typecheck && npm test"
	return runDocker("run", "--rm",
		"-v", filepath.Join(root, "apps", "web")+":/src:ro",
		"-v", "contaforte-npm-cache:/root/.npm",
		nodeImage, "sh", "-c", script)
}

// cmdE2E roda o Playwright num container com os navegadores, apontando para o gateway local.
// Usa --network host para alcançar 127.0.0.1:<porta>; credenciais do admin vêm do .env.
func cmdE2E() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	env, err := parseEnv(filepath.Join(root, ".env"))
	if err != nil {
		return fmt.Errorf(".env não encontrado: rode ./cf init primeiro")
	}
	port := env["GATEWAY_PORT"]
	if port == "" {
		port = "8080"
	}
	if !httpOK("http://127.0.0.1:" + port + "/healthz") {
		return fmt.Errorf("o gateway não responde em 127.0.0.1:%s: suba a stack com ./cf up", port)
	}
	script := "cp -r /src /work && cd /work && npm ci --no-audit --no-fund --loglevel=error && npx playwright test"
	runErr := runDocker("run", "--rm", "--network", "host", "--ipc=host",
		"-e", "BASE_URL=http://127.0.0.1:"+port,
		"-e", "ADMIN_EMAIL="+env["ADMIN_EMAIL"],
		"-e", "ADMIN_PASSWORD="+env["ADMIN_PASSWORD"],
		"-v", filepath.Join(root, "apps", "web", "e2e")+":/src:ro",
		"-v", "contaforte-npm-cache:/root/.npm",
		playwrightImage, "sh", "-c", script)
	// os testes criam contas e transações com o prefixo "e2e-": remove sempre, passando ou falhando
	if err := cleanupE2E(root, env); err != nil {
		fmt.Fprintln(os.Stderr, "aviso: não consegui limpar os dados do E2E:", err)
	} else {
		fmt.Println("dados de teste do E2E (prefixo e2e-) removidos")
	}
	return runErr
}

// e2ePrefix marca tudo o que os testes E2E criam; a limpeza só toca nisso.
const e2ePrefix = "e2e-"

// e2eCleanupSQL apaga as transações e as contas de teste (as contas de teste são identificadas só pelo prefixo).
const e2eCleanupSQL = "delete from transactions where account_id in (select id from accounts where name like 'e2e-%'); " +
	"delete from accounts where name like 'e2e-%';"

func cleanupE2E(root string, env map[string]string) error {
	cmd := exec.Command("docker", "compose", "exec", "-T", "postgres",
		"psql", "-U", env["POSTGRES_USER"], "-d", env["POSTGRES_DB"], "-v", "ON_ERROR_STOP=1", "-q", "-c", e2eCleanupSQL)
	cmd.Dir = root
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// cmdTestGo roda vet e testes dos módulos Go (cf e container-exporter) num container, sem exigir Go no host.
func cmdTestGo() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	script := "set -e; for m in tools/cf tools/container-exporter; do echo \"== $m\"; (cd $m && gofmt -l . | (! grep .) && go vet ./... && go test ./...); done"
	return runDocker("run", "--rm",
		"-e", "GOCACHE=/tmp/gocache", "-e", "GOFLAGS=-buildvcs=false",
		"-v", root+":/repo:ro", "-w", "/repo",
		"golang:1.25-alpine", "sh", "-c", script)
}
