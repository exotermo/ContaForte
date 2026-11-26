package main

import (
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
)

func TestReleaseVersion(t *testing.T) {
	dir := t.TempDir()
	if v, err := releaseVersion(dir); v != "" || err != nil {
		t.Fatalf("sem .release é modo de desenvolvimento: %q %v", v, err)
	}
	write := func(s string) {
		if err := os.WriteFile(filepath.Join(dir, releaseFile), []byte(s), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write("v0.5.0-4-gc29cdfe\n")
	if v, err := releaseVersion(dir); v != "v0.5.0-4-gc29cdfe" || err != nil {
		t.Fatalf("versão = %q %v", v, err)
	}
	for _, bad := range []string{"", "v1 v2", "-x", "a/b", "v1:latest"} {
		write(bad)
		if _, err := releaseVersion(dir); err == nil {
			t.Errorf("%q deveria ser recusada", bad)
		}
	}
}

func TestUpEObsNuncaConstroemNoPacote(t *testing.T) {
	if got := strings.Join(upArgs(false), " "); got != "up -d --build --wait" {
		t.Fatalf("desenvolvimento: %s", got)
	}
	if got := strings.Join(upArgs(true), " "); got != "up -d --no-build --wait" {
		t.Fatalf("pacote: %s", got)
	}
	obs, err := obsArgs("up", true)
	if err != nil || slices.Contains(obs, "--build") || !slices.Contains(obs, "--no-build") {
		t.Fatalf("obs no pacote = %v %v", obs, err)
	}
}

// TestImagensDoCompose trava o que impede o Docker de baixar, de um registro público, uma imagem com o nome das nossas.
func TestImagensDoCompose(t *testing.T) {
	raw, err := os.ReadFile("../../docker-compose.yml")
	if err != nil {
		t.Skip("docker-compose.yml fora do alcance (teste isolado do módulo)")
	}
	_, body, _ := strings.Cut(string(raw), "\nservices:\n")
	svcRe := regexp.MustCompile(`^  ([a-z][a-z-]*):\s*$`)
	blocks, current := map[string]string{}, ""
	for _, line := range strings.Split(body, "\n") {
		if m := svcRe.FindStringSubmatch(line); m != nil {
			current = m[1]
			continue
		}
		blocks[current] += line + "\n"
	}
	var built []string
	for svc, block := range blocks {
		if strings.Contains(block, "\n    build: ") || strings.HasPrefix(block, "    build: ") {
			built = append(built, svc)
		}
	}
	var listed []string
	for _, img := range appImages {
		listed = append(listed, img.Service)
		block := blocks[img.Service]
		for _, want := range []string{
			"    build: ./" + img.Context + "\n",
			"    image: contaforte-" + img.Service + ":${CONTAFORTE_VERSION:-dev}\n",
			"    pull_policy: never\n",
		} {
			if !strings.Contains(block, want) {
				t.Errorf("%s: falta %q no compose", img.Service, strings.TrimSpace(want))
			}
		}
	}
	slices.Sort(built)
	slices.Sort(listed)
	if !slices.Equal(built, listed) {
		t.Errorf("serviços construídos no compose = %v, no cf release = %v", built, listed)
	}
	byService, _ := composeProfiles(t)
	for svc, profile := range byService {
		if _, probed := imageProbes[svc]; (profile == "" || profile == "tunnel") && !probed {
			t.Errorf("%s fica sempre ligado e não tem checagem no cf check-images", svc)
		}
	}
}

func TestProbeArgs(t *testing.T) {
	own := strings.Join(probeArgs("backend", "contaforte-backend:v1"), " ")
	if !strings.Contains(own, "--pull never") || !strings.Contains(own, "--network none") || !strings.HasSuffix(own, "--entrypoint java contaforte-backend:v1 -XX:SharedArchiveFile=/app/app.jsa -version") {
		t.Fatalf("imagem nossa: %s", own)
	}
	if third := strings.Join(probeArgs("redis", "redis:7-alpine"), " "); strings.Contains(third, "--pull") {
		t.Fatalf("imagem de terceiro pode ser baixada: %s", third)
	}
	if !strings.Contains(probeVerdict(132), "processador") || probeVerdict(1) != "saída 1" {
		t.Fatal("explicação errada")
	}
}

func TestWriteSums(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "a"), []byte("abc"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := writeSums(dir, "a"); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(filepath.Join(dir, "SHA256SUMS"))
	if string(got) != "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  a\n" {
		t.Fatalf("SHA256SUMS = %q", got)
	}
}
