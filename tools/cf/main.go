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
  test [backend|web|all]             Roda os testes em container (backend: Gradle+Testcontainers; web: lint, tipos e Vitest)
  e2e                                Testes E2E do navegador (Playwright) contra a stack em execução
  status                             Estado, saúde e consumo de cada serviço
  doctor                             Diagnóstico: isolamento de rede, portas, segredos, RAM
  logs [serviço]                     Acompanha os logs
  open                               Abre o app no navegador
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
	case "open":
		err = cmdOpen()
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
