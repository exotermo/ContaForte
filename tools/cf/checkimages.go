package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"sort"
	"strings"
)

// imageProbes executa, em cada imagem, o programa que o serviço vai rodar. Um processador antigo que não conheça uma
// instrução usada por ele derruba o programa aqui, antes de haver stack ou dados no caminho.
var imageProbes = map[string][]string{
	"backend":     {"java", "-XX:SharedArchiveFile=/app/app.jsa", "-version"},
	"web":         {"node", "-e", "require('crypto').createHash('sha256').update('x').digest('hex')"},
	"postgres":    {"postgres", "--version"},
	"redis":       {"redis-server", "--version"},
	"gateway":     {"nginx", "-v"},
	"cloudflared": {"cloudflared", "--version"},
}

// probeArgs monta o `docker run` de uma checagem: sem rede, e nunca baixando uma imagem construída por nós.
func probeArgs(service, image string) []string {
	probe := imageProbes[service]
	args := []string{"run", "--rm", "--network", "none"}
	for _, own := range appImages {
		if own.Service == service {
			args = append(args, "--pull", "never")
		}
	}
	return append(append(args, "--entrypoint", probe[0], image), probe[1:]...)
}

// probeVerdict explica a falha. 132 = instrução ilegal, 139 = falha de segmentação.
func probeVerdict(code int) string {
	if code == 132 || code == 139 {
		return fmt.Sprintf("o processador não executa esta imagem (saída %d, instrução não suportada ou falha de memória)", code)
	}
	return fmt.Sprintf("saída %d", code)
}

func cmdCheckImages() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	raw, err := composeOut(root, "--profile", "tunnel", "config", "--format", "json")
	if err != nil {
		return fmt.Errorf("docker compose config falhou (rodou ./cf init?): %w", err)
	}
	var cfg struct {
		Services map[string]struct {
			Image string `json:"image"`
		} `json:"services"`
	}
	if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
		return err
	}
	names := make([]string, 0, len(imageProbes))
	for n := range imageProbes {
		names = append(names, n)
	}
	sort.Strings(names)
	fmt.Println("Executando um programa de cada imagem neste processador")
	failed := 0
	for _, n := range names {
		image := cfg.Services[n].Image
		out, err := exec.Command("docker", probeArgs(n, image)...).CombinedOutput()
		if err == nil {
			ok("%-12s %s", n, image)
			continue
		}
		failed++
		var exit *exec.ExitError
		code := -1
		if errors.As(err, &exit) {
			code = exit.ExitCode()
		}
		lines := strings.Split(strings.TrimSpace(string(out)), "\n")
		fail("%-12s %s: %s — %s", n, image, probeVerdict(code), lines[len(lines)-1])
	}
	if failed > 0 {
		return fmt.Errorf("%d imagem(ns) não rodaram", failed)
	}
	return nil
}
