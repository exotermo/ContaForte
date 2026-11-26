package main

import (
	"fmt"
	"slices"
	"strings"
)

// obsServices são os serviços do perfil "obs" (um teste confere com o docker-compose.yml).
var obsServices = []string{"alloy", "container-exporter", "docker-proxy", "grafana", "loki", "postgres-exporter", "prometheus", "redis-exporter"}

// allProfiles vale para derrubar a stack: o `down` do Compose ignora serviços de perfis inativos, que ficariam de pé.
var allProfiles = []string{"--profile", "*"}

// profileEnabled informa se o perfil está ativo no COMPOSE_PROFILES do .env.
func profileEnabled(env map[string]string, name string) bool {
	for _, p := range strings.Split(env["COMPOSE_PROFILES"], ",") {
		if strings.TrimSpace(p) == name {
			return true
		}
	}
	return false
}

// obsRunning diz se algum serviço de monitoramento tem contêiner no projeto.
func obsRunning(svcs []svcInfo) bool {
	return slices.ContainsFunc(svcs, func(s svcInfo) bool { return slices.Contains(obsServices, s.Service) })
}

// obsArgs monta o comando do Compose que liga ou desliga só o monitoramento, sem mexer no núcleo nem no .env.
func obsArgs(action string, prebuilt bool) ([]string, error) {
	base := []string{"--profile", "obs"}
	switch action {
	case "up":
		return append(append(base, upArgs(prebuilt)...), obsServices...), nil
	case "down":
		return append(append(base, "rm", "--stop", "--force"), obsServices...), nil // os volumes (histórico) ficam
	}
	return nil, fmt.Errorf("uso: cf obs up|down")
}

// cmdObs recebe a versão do pacote instalado, ou "" num repositório de desenvolvimento.
func cmdObs(prebuilt string, args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("uso: cf obs up|down")
	}
	composeArgs, err := obsArgs(args[0], prebuilt != "")
	if err != nil {
		return err
	}
	if prebuilt != "" && args[0] == "up" {
		if err := requireImages(prebuilt, "container-exporter"); err != nil {
			return err
		}
	}
	if err := cmdCompose(composeArgs); err != nil {
		return err
	}
	if args[0] == "up" {
		fmt.Println("\nMonitoramento ligado (usa cerca de 600 MiB além do núcleo). Painéis: ./cf open grafana")
		fmt.Println("Desligue com ./cf obs down. Para ligar sempre, ponha COMPOSE_PROFILES=obs no .env.")
	} else {
		fmt.Println("\nMonitoramento desligado. O histórico ficou nos volumes.")
	}
	return nil
}

// downArgs recusa opções em vez de ignorá-las: antes, um `cf down -v` parecia apagar os volumes e não apagava.
func downArgs(args []string) ([]string, error) {
	if len(args) > 0 {
		return nil, fmt.Errorf("cf down não aceita opções (%s). Ele mantém os dados; para apagar os volumes use ./cf reset", strings.Join(args, " "))
	}
	return append(slices.Clone(allProfiles), "down"), nil
}
