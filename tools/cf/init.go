package main

import (
	"bufio"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"flag"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// abaixo de minRAMMB sobra pouco para o sistema ao lado da stack (e quase nada com o monitoramento ligado)
const (
	minRAMMB   = 2048
	jwtKeyName = "JWT_PRIVATE_KEY_B64"
)

// secretKeys são gerados aleatoriamente quando ausentes (ou com --force).
var secretKeys = []string{"POSTGRES_PASSWORD", "REDIS_PASSWORD", "MQTT_PASSWORD", "ADMIN_PASSWORD", "GRAFANA_PASSWORD"}

// defaults não são segredos.
var defaults = map[string]string{
	"COMPOSE_PROJECT_NAME": "contaforte",
	"GATEWAY_PORT":         "8080",
	"POSTGRES_DB":          "contaforte",
	"POSTGRES_USER":        "contaforte",
	"MQTT_USER":            "contaforte",
	"ADMIN_EMAIL":          "admin@contaforte.local",
}

// buildEnv completa o mapa com defaults e segredos, sem sobrescrever o que já existe
// (a menos que force=true para os segredos). Devolve as chaves que foram geradas.
func buildEnv(current map[string]string, force bool, gen func(int) (string, error), genKey func() (string, error)) (map[string]string, []string, error) {
	env := map[string]string{}
	for k, v := range current {
		env[k] = v
	}
	var created []string
	for k, v := range defaults {
		if env[k] == "" {
			env[k] = v
			created = append(created, k)
		}
	}
	for _, k := range secretKeys {
		if env[k] == "" || env[k] == "change-me" || force {
			s, err := gen(24)
			if err != nil {
				return nil, nil, err
			}
			env[k] = s
			created = append(created, k)
		}
	}
	// chave RSA do JWT: gerada uma vez e preservada (regenerá-la derruba todas as sessões)
	if env[jwtKeyName] == "" || env[jwtKeyName] == "change-me" || force {
		k, err := genKey()
		if err != nil {
			return nil, nil, err
		}
		env[jwtKeyName] = k
		created = append(created, jwtKeyName)
	}
	return env, created, nil
}

// genJWTKey cria uma chave RSA de 3072 bits em PKCS#8/DER, em Base64 (uma linha, própria para .env).
func genJWTKey() (string, error) {
	k, err := rsa.GenerateKey(rand.Reader, 3072)
	if err != nil {
		return "", err
	}
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(der), nil
}

func cmdInit(args []string) error {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	force := fs.Bool("force", false, "regenera TODOS os segredos (invalida volumes existentes)")
	adminEmail := fs.String("admin-email", "", "e-mail do administrador inicial")
	gatewayPort := fs.Int("port", 0, "porta local do gateway (padrão 8080; use outra se estiver ocupada)")
	fs.Parse(args)
	if *gatewayPort != 0 && (*gatewayPort < 1024 || *gatewayPort > 65535) {
		return fmt.Errorf("--port deve estar entre 1024 e 65535")
	}

	root, err := findRoot()
	if err != nil {
		return err
	}
	fmt.Println("Verificando pré-requisitos")
	bad := false
	if _, err := exec.LookPath("docker"); err != nil {
		fail("docker não encontrado")
		bad = true
	} else if out, err := composeOut(root, "version", "--short"); err != nil {
		fail("docker compose indisponível: %v", err)
		bad = true
	} else {
		ok("docker compose %s", strings.TrimSpace(out))
	}
	if mb, err := totalRAMMB(); err == nil {
		if mb < minRAMMB {
			warn("RAM total %d MB (< %d MB): evite ligar o monitoramento nesta máquina", mb, minRAMMB)
		} else {
			ok("RAM total %d MB", mb)
		}
	}

	envPath := filepath.Join(root, ".env")
	current := map[string]string{}
	if _, err := os.Stat(envPath); err == nil {
		if *force {
			fmt.Print("--force regenera os segredos e invalida volumes existentes. Continuar? [s/N] ")
			if !confirm() {
				return fmt.Errorf("cancelado")
			}
		}
		if current, err = parseEnv(envPath); err != nil {
			return err
		}
	}
	env, created, err := buildEnv(current, *force, randomHex, genJWTKey)
	if err != nil {
		return err
	}
	if *adminEmail != "" {
		env["ADMIN_EMAIL"] = *adminEmail
	}
	if *gatewayPort != 0 {
		env["GATEWAY_PORT"] = strconv.Itoa(*gatewayPort)
	}
	if err := writeEnv(envPath, env); err != nil {
		return err
	}
	if len(created) == 0 {
		ok(".env já completo (nenhum segredo alterado)")
	} else {
		ok(".env atualizado (%d chaves geradas), permissão 600", len(created))
	}

	port, _ := strconv.Atoi(env["GATEWAY_PORT"])
	if portBusy(port) && !stackRunning(root) {
		fail("porta 127.0.0.1:%d ocupada: rode ./cf init --port %d", port, freePort(port+1, portBusy))
		bad = true
	} else {
		ok("porta do gateway: 127.0.0.1:%d", port)
	}
	if _, err := composeOut(root, "config", "-q"); err != nil {
		fail("docker-compose.yml inválido: %v", err)
		bad = true
	} else {
		ok("docker-compose.yml válido")
	}
	if bad {
		return fmt.Errorf("pré-requisitos não atendidos")
	}
	fmt.Printf("\nPronto. Próximo passo: ./cf up\nAdmin inicial: %s (senha em .env → ADMIN_PASSWORD)\n", env["ADMIN_EMAIL"])
	return nil
}

func confirm() bool {
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	l := strings.ToLower(strings.TrimSpace(line))
	return l == "s" || l == "y" || l == "sim" || l == "yes"
}

func portBusy(port int) bool {
	l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return true
	}
	l.Close()
	return false
}

// freePort devolve a primeira porta livre a partir de from (ou from, se nenhuma das 100 seguintes estiver).
func freePort(from int, busy func(int) bool) int {
	for p := from; p < from+100 && p <= 65535; p++ {
		if !busy(p) {
			return p
		}
	}
	return from
}

func stackRunning(root string) bool {
	out, err := composeOut(root, "ps", "-q")
	return err == nil && strings.TrimSpace(out) != ""
}

func totalRAMMB() (int, error) {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0, err
	}
	return memField(string(b), "MemTotal")
}

func availRAMMB() (int, error) {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0, err
	}
	return memField(string(b), "MemAvailable")
}

// memField extrai um campo de /proc/meminfo (em kB) e devolve em MB.
func memField(meminfo, name string) (int, error) {
	for _, line := range strings.Split(meminfo, "\n") {
		if strings.HasPrefix(line, name+":") {
			f := strings.Fields(line)
			if len(f) >= 2 {
				kb, err := strconv.Atoi(f[1])
				return kb / 1024, err
			}
		}
	}
	return 0, fmt.Errorf("campo %s não encontrado", name)
}
