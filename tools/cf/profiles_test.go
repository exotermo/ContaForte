package main

import (
	"os"
	"regexp"
	"slices"
	"strings"
	"testing"
)

// composeProfiles lê, do docker-compose.yml real, quais serviços pertencem a qual perfil e quais giram os logs.
func composeProfiles(t *testing.T) (byService map[string]string, withLogRotation map[string]bool) {
	t.Helper()
	raw, err := os.ReadFile("../../docker-compose.yml")
	if err != nil {
		t.Skip("docker-compose.yml fora do alcance (teste isolado do módulo)")
	}
	_, body, found := strings.Cut(string(raw), "\nservices:\n")
	if !found {
		t.Fatal("seção services não encontrada")
	}
	svcRe := regexp.MustCompile(`^  ([a-z][a-z-]*):\s*$`)
	profRe := regexp.MustCompile(`^    profiles: \[([a-z]+)\]`)
	byService, withLogRotation = map[string]string{}, map[string]bool{}
	current := ""
	for _, line := range strings.Split(body, "\n") {
		if m := svcRe.FindStringSubmatch(line); m != nil {
			current = m[1]
			byService[current] = ""
		} else if m := profRe.FindStringSubmatch(line); m != nil {
			byService[current] = m[1]
		} else if line == "    logging: *logging" {
			withLogRotation[current] = true
		}
	}
	return byService, withLogRotation
}

func TestPerfisDoCompose(t *testing.T) {
	byService, rotation := composeProfiles(t)
	var obs, core []string
	for svc, profile := range byService {
		switch profile {
		case "obs":
			obs = append(obs, svc)
		case "":
			core = append(core, svc)
		}
		if !rotation[svc] {
			t.Errorf("%s sem rotação de logs", svc)
		}
	}
	slices.Sort(obs)
	slices.Sort(core)
	if !slices.Equal(obs, obsServices) {
		t.Errorf("perfil obs no compose = %v, no cf = %v", obs, obsServices)
	}
	if want := []string{"backend", "gateway", "postgres", "redis", "web"}; !slices.Equal(core, want) {
		t.Errorf("núcleo = %v, esperado %v: um serviço novo precisa caber no 1 GB ou ir para um perfil", core, want)
	}
	if byService["mqtt"] != "iot" || byService["cloudflared"] != "tunnel" {
		t.Errorf("perfis errados: mqtt=%q cloudflared=%q", byService["mqtt"], byService["cloudflared"])
	}
}

func TestProfileEnabledEObsRunning(t *testing.T) {
	env := map[string]string{"COMPOSE_PROFILES": "tunnel, obs"}
	if !profileEnabled(env, "obs") || !profileEnabled(env, "tunnel") || profileEnabled(env, "iot") || profileEnabled(map[string]string{}, "obs") {
		t.Fatal("leitura de COMPOSE_PROFILES errada")
	}
	if obsRunning([]svcInfo{{Service: "backend"}, {Service: "mqtt"}}) || !obsRunning([]svcInfo{{Service: "backend"}, {Service: "loki"}}) {
		t.Fatal("detecção do monitoramento errada")
	}
}

func TestObsEDownArgs(t *testing.T) {
	up, err := obsArgs("up")
	if err != nil || up[0] != "--profile" || up[1] != "obs" || slices.Contains(up, "backend") || !slices.Contains(up, "grafana") {
		t.Fatalf("up = %v, %v", up, err)
	}
	down, err := obsArgs("down")
	if err != nil || slices.Contains(down, "-v") || slices.Contains(down, "down") || !slices.Contains(down, "rm") {
		t.Fatalf("desligar o monitoramento não pode derrubar a stack nem apagar volumes: %v, %v", down, err)
	}
	if _, err := obsArgs("restart"); err == nil {
		t.Fatal("ação desconhecida deveria falhar")
	}

	got, err := downArgs(nil)
	if err != nil || strings.Join(got, " ") != "--profile * down" {
		t.Fatalf("down = %v, %v", got, err)
	}
	if _, err := downArgs([]string{"-v"}); err == nil || !strings.Contains(err.Error(), "cf reset") {
		t.Fatalf("down -v deveria ser recusado indicando o reset: %v", err)
	}
}

func TestFreePort(t *testing.T) {
	busy := func(p int) bool { return p < 8083 }
	if got := freePort(8081, busy); got != 8083 {
		t.Fatalf("porta livre = %d", got)
	}
	if got := freePort(9000, func(int) bool { return true }); got != 9000 {
		t.Fatalf("sem porta livre = %d", got)
	}
}
