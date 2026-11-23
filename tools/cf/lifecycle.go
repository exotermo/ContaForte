package main

import (
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

func cmdCompose(args []string) error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	if _, err := os.Stat(filepath.Join(root, ".env")); err != nil {
		return fmt.Errorf(".env não encontrado: rode ./cf init primeiro")
	}
	return compose(root, args...)
}

func cmdReset() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	fmt.Print("Isso APAGA os volumes (bancos, Redis, MQTT). Continuar? [s/N] ")
	if !confirm() {
		return fmt.Errorf("cancelado")
	}
	return compose(root, "down", "-v", "--remove-orphans")
}

func cmdOpen(args []string) error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	env, _ := parseEnv(filepath.Join(root, ".env"))
	port := env["GATEWAY_PORT"]
	if port == "" {
		port = "8080"
	}
	url := "http://127.0.0.1:" + port
	if len(args) > 0 {
		switch args[0] {
		case "grafana":
			url += "/grafana/"
		case "app":
		default:
			return fmt.Errorf("alvo desconhecido %q (use app ou grafana)", args[0])
		}
	}
	opener := "xdg-open"
	if runtime.GOOS == "darwin" {
		opener = "open"
	}
	fmt.Println(url)
	return exec.Command(opener, url).Start()
}

func cmdBackup() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	env, err := parseEnv(filepath.Join(root, ".env"))
	if err != nil {
		return fmt.Errorf(".env não encontrado: rode ./cf init primeiro")
	}
	dir := filepath.Join(root, "backups")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	path := filepath.Join(dir, "contaforte-"+time.Now().Format("20060102-150405")+".sql.gz")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	gz := gzip.NewWriter(f)
	cmd := exec.Command("docker", "compose", "exec", "-T", "postgres",
		"pg_dump", "-U", env["POSTGRES_USER"], "-d", env["POSTGRES_DB"], "--clean", "--if-exists")
	cmd.Dir = root
	cmd.Stdout, cmd.Stderr = gz, os.Stderr
	if err := cmd.Run(); err != nil {
		os.Remove(path)
		return fmt.Errorf("pg_dump falhou (a stack está de pé?): %w", err)
	}
	if err := gz.Close(); err != nil {
		return err
	}
	fmt.Println("backup salvo em", path)
	return nil
}

func cmdRestore(args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("uso: cf restore <arquivo.sql.gz>")
	}
	root, err := findRoot()
	if err != nil {
		return err
	}
	env, err := parseEnv(filepath.Join(root, ".env"))
	if err != nil {
		return fmt.Errorf(".env não encontrado: rode ./cf init primeiro")
	}
	f, err := os.Open(args[0])
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return fmt.Errorf("arquivo inválido: %w", err)
	}
	fmt.Printf("Restaurar %s sobrescreve o banco %s. Continuar? [s/N] ", filepath.Base(args[0]), env["POSTGRES_DB"])
	if !confirm() {
		return fmt.Errorf("cancelado")
	}
	cmd := exec.Command("docker", "compose", "exec", "-T", "postgres",
		"psql", "-U", env["POSTGRES_USER"], "-d", env["POSTGRES_DB"], "-v", "ON_ERROR_STOP=1", "-q")
	cmd.Dir = root
	cmd.Stdin, cmd.Stdout, cmd.Stderr = io.Reader(gz), os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("restore falhou: %w", err)
	}
	fmt.Println("restore concluído")
	return nil
}

// trimLines é usado por status/doctor para ignorar linhas vazias.
func trimLines(s string) []string {
	var out []string
	for _, l := range strings.Split(s, "\n") {
		if strings.TrimSpace(l) != "" {
			out = append(out, l)
		}
	}
	return out
}

// cmdTestBackend roda os testes do backend num container Gradle/JDK 21. O socket do Docker é montado
// para o Testcontainers subir Postgres "irmãos"; --network host permite alcançar as portas mapeadas.
// Atenção: montar o docker.sock equivale a dar acesso root ao daemon — uso restrito a desenvolvimento.
func cmdTestBackend() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	sock := "/var/run/docker.sock"
	st, err := os.Stat(sock)
	if err != nil {
		return fmt.Errorf("docker.sock não encontrado em %s: %w", sock, err)
	}
	gid := dockerSockGID(st)
	args := []string{
		"run", "--rm", "--network", "host",
		"-u", fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid()),
		"--group-add", gid,
		"-e", "GRADLE_USER_HOME=/home/gradle/.gradle",
		"-e", "TESTCONTAINERS_HOST_OVERRIDE=localhost",
		"-v", sock + ":" + sock,
		"-v", "contaforte-gradle-cache:/home/gradle/.gradle",
		"-v", filepath.Join(root, "apps", "backend") + ":/app",
		"-w", "/app",
		"gradle:8.14.3-jdk21",
		"gradle", "test", "--no-daemon", "--console=plain",
	}
	cmd := exec.Command("docker", args...)
	cmd.Stdout, cmd.Stderr, cmd.Stdin = os.Stdout, os.Stderr, os.Stdin
	return cmd.Run()
}
