// cf é o CLI de configuração e operação do ContaForte.
package main

import (
	"fmt"
	"os"
)

var version = "dev"

const usage = `cf — CLI do ContaForte

Uso: cf <comando> [opções]

Configuração
  init [--force] [--admin-email E]   Verifica pré-requisitos e gera o .env com segredos
Ciclo de vida
  build [serviço...]                 Constrói as imagens
  up                                 Sobe a stack e espera ficar saudável
  down                               Derruba a stack (mantém os volumes)
  reset                              Derruba a stack E apaga os volumes (pede confirmação)
Operação
  test [backend|web|go|all]          Roda os testes em container (backend: Gradle+Testcontainers; web: lint, tipos e Vitest; go: cf e container-exporter)
  e2e                                Testes E2E do navegador (Playwright) contra a stack em execução
  status                             Estado, saúde e consumo de cada serviço
  doctor                             Diagnóstico: isolamento de rede, portas, segredos, RAM
  logs [serviço]                     Acompanha os logs
  open [app|grafana]                 Abre o app (ou o Grafana) no navegador
  seed [--reset] [--with-imports]    Carrega dados FICTÍCIOS (90 dias) e regras/favorecidos de demonstração num usuário demo; com --with-imports, importa também extratos OFX sintéticos
  mem [--for 60s]                    Memória por contêiner (uso + swap), limite e mortes por falta de memória
  bench [--import-rows N]            Mede a latência (p95) das telas com 10 mil transações (k6) e a importação de N linhas (padrão 20000)
  backup                             pg_dump comprimido em backups/
  restore <arquivo.sql.gz>           Restaura um backup (pede confirmação)
  version                            Mostra a versão
`

func main() {
	if len(os.Args) < 2 {
		fmt.Print(usage)
		os.Exit(2)
	}
	cmd, args := os.Args[1], os.Args[2:]
	var err error
	switch cmd {
	case "init":
		err = cmdInit(args)
	case "build":
		err = cmdCompose(append([]string{"build"}, args...))
	case "up":
		err = cmdCompose([]string{"up", "-d", "--build", "--wait"})
	case "down":
		err = cmdCompose([]string{"down"})
	case "reset":
		err = cmdReset()
	case "logs":
		err = cmdCompose(append([]string{"logs", "-f", "--tail=100"}, args...))
	case "test":
		err = cmdTest(args)
	case "e2e":
		err = cmdE2E()
	case "status":
		err = cmdStatus()
	case "doctor":
		err = cmdDoctor()
	case "seed":
		err = cmdSeed(args)
	case "mem":
		err = cmdMem(args)
	case "bench":
		err = cmdBench(args)
	case "open":
		err = cmdOpen(args)
	case "backup":
		err = cmdBackup()
	case "restore":
		err = cmdRestore(args)
	case "version", "--version":
		fmt.Println("cf", version)
	case "help", "-h", "--help":
		fmt.Print(usage)
	default:
		fmt.Fprintf(os.Stderr, "comando desconhecido: %s\n\n%s", cmd, usage)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "erro:", err)
		os.Exit(1)
	}
}
