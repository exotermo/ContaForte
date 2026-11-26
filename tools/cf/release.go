package main

// Instalação sem construir no destino (ADR-022): `cf release` monta, a partir do commit, um pacote com as imagens,
// os arquivos de execução e o cf compilado. Onde ele é extraído, o arquivo .release liga o modo "imagens prontas".

import (
	"compress/gzip"
	"crypto/sha256"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
)

const (
	releaseFile = ".release" // na raiz de um pacote extraído; guarda a versão
	versionVar  = "CONTAFORTE_VERSION"
	goImage     = "golang:1.25-alpine"
)

type appImage struct{ Service, Context string }

// appImages são as imagens construídas aqui (um teste confere com o docker-compose.yml).
var appImages = []appImage{
	{"backend", "apps/backend"},
	{"web", "apps/web"},
	{"container-exporter", "tools/container-exporter"},
}

func imageRef(service, version string) string { return "contaforte-" + service + ":" + version }

var tagRe = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$`)

// releaseVersion devolve a versão do pacote extraído em root, ou "" num repositório de desenvolvimento.
func releaseVersion(root string) (string, error) {
	b, err := os.ReadFile(filepath.Join(root, releaseFile))
	if errors.Is(err, fs.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	v := strings.TrimSpace(string(b))
	if !tagRe.MatchString(v) {
		return "", fmt.Errorf("%s inválido: %q não serve como versão de imagem", releaseFile, v)
	}
	return v, nil
}

// useRelease faz o Compose usar as imagens do pacote. A variável de ambiente vale mais que o .env.
func useRelease() (string, error) {
	root, err := findRoot()
	if err != nil {
		return "", nil // fora do projeto: o comando em si explica o erro
	}
	v, err := releaseVersion(root)
	if err != nil || v == "" {
		return "", err
	}
	return v, os.Setenv(versionVar, v)
}

// upArgs sobe a stack construindo (desenvolvimento) ou só com imagens já carregadas (pacote).
func upArgs(prebuilt bool) []string {
	mode := "--build"
	if prebuilt {
		mode = "--no-build"
	}
	return []string{"up", "-d", mode, "--wait"}
}

// requireImages avisa o que fazer quando as imagens do pacote ainda não foram carregadas.
func requireImages(version string, services ...string) error {
	for _, s := range services {
		if exec.Command("docker", "image", "inspect", imageRef(s, version)).Run() != nil {
			return fmt.Errorf("a imagem %s não está neste Docker: carregue o pacote com `gunzip -c contaforte-%s-images.tar.gz | docker load`", imageRef(s, version), version)
		}
	}
	return nil
}

func cmdRelease(args []string) error {
	fl := flag.NewFlagSet("release", flag.ExitOnError)
	out := fl.String("out", "dist", "pasta de saída")
	fl.Parse(args)
	root, err := findRoot()
	if err != nil {
		return err
	}
	if v, _ := releaseVersion(root); v != "" {
		return fmt.Errorf("isto já é um pacote instalado (%s): gere o próximo no repositório", v)
	}
	git := func(a ...string) (string, error) {
		b, err := exec.Command("git", append([]string{"-C", root}, a...)...).Output()
		return strings.TrimSpace(string(b)), err
	}
	ver, err := git("describe", "--tags", "--always", "HEAD")
	if err != nil || !tagRe.MatchString(ver) {
		return fmt.Errorf("cf release precisa de um repositório Git com ao menos um commit (versão: %q, %v)", ver, err)
	}
	if dirty, _ := git("status", "--porcelain"); dirty != "" {
		warn("há alterações não commitadas: elas NÃO entram no pacote, que é gerado do commit %s", ver)
	}

	dir, err := filepath.Abs(*out)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.MkdirTemp("", "cf-release-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	src := filepath.Join(tmp, "src")
	if err := os.Mkdir(src, 0o755); err != nil {
		return err
	}
	// tudo sai do commit: as imagens e os arquivos do pacote não podem divergir da versão que ele anuncia
	archive := filepath.Join(tmp, "src.tar")
	if _, err := git("archive", "--format=tar", "-o", archive, "HEAD"); err != nil {
		return fmt.Errorf("git archive falhou: %w", err)
	}
	if err := run(tmp, "tar", "-xf", archive, "-C", src); err != nil {
		return err
	}

	var refs []string
	for _, img := range appImages {
		ref := imageRef(img.Service, ver)
		fmt.Printf("\nConstruindo %s ...\n", ref)
		if err := run(src, "docker", "build", "--platform", "linux/amd64", "-t", ref, img.Context); err != nil {
			return fmt.Errorf("falha ao construir %s: %w", ref, err)
		}
		refs = append(refs, ref)
	}
	fmt.Println("\nCompilando o cf para linux/amd64 ...")
	if err := buildCF(filepath.Join(src, "tools", "cf"), ver); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(src, releaseFile), []byte(ver+"\n"), 0o644); err != nil {
		return err
	}

	appName, imagesName := "contaforte-"+ver+".tar.gz", "contaforte-"+ver+"-images.tar.gz"
	if err := run(src, "tar", "-czf", filepath.Join(dir, appName), "--owner=0", "--group=0", "-C", src, "."); err != nil {
		return err
	}
	fmt.Println("Exportando as imagens ...")
	if err := saveImages(filepath.Join(dir, imagesName), refs); err != nil {
		return err
	}
	if err := writeSums(dir, appName, imagesName); err != nil {
		return err
	}
	fmt.Println()
	for _, name := range []string{appName, imagesName, "SHA256SUMS"} {
		if st, err := os.Stat(filepath.Join(dir, name)); err == nil {
			ok("%s (%.1f MB)", filepath.Join(dir, name), float64(st.Size())/1e6)
		}
	}
	fmt.Println("\nNada foi publicado. Copie os três arquivos para o destino e siga o guia de operação (Instalação por pacote).")
	return nil
}

func run(dir, name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	return cmd.Run()
}

// buildCF compila o cf dentro do pacote, com o Go local ou num container.
func buildCF(dir, version string) error {
	if err := os.MkdirAll(filepath.Join(dir, "bin"), 0o755); err != nil {
		return err
	}
	build := []string{"build", "-trimpath", "-buildvcs=false", "-ldflags", "-s -w -X main.version=" + version, "-o", "bin/cf", "."}
	if _, err := exec.LookPath("go"); err == nil {
		cmd := exec.Command("go", build...)
		cmd.Dir = dir
		cmd.Env = append(os.Environ(), "CGO_ENABLED=0", "GOOS=linux", "GOARCH=amd64")
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		return cmd.Run()
	}
	args := []string{"run", "--rm", "-u", fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid()),
		"-e", "CGO_ENABLED=0", "-e", "GOOS=linux", "-e", "GOARCH=amd64", "-e", "GOCACHE=/tmp/gocache",
		"-v", dir + ":/src:Z", "-w", "/src", goImage, "go"}
	return run(dir, "docker", append(args, build...)...)
}

func saveImages(path string, refs []string) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	gz := gzip.NewWriter(f)
	cmd := exec.Command("docker", append([]string{"save"}, refs...)...)
	cmd.Stdout, cmd.Stderr = gz, os.Stderr
	if err := cmd.Run(); err != nil {
		os.Remove(path)
		return fmt.Errorf("docker save falhou: %w", err)
	}
	if err := gz.Close(); err != nil {
		return err
	}
	return f.Close()
}

// writeSums grava um SHA256SUMS no formato do sha256sum, com nomes sem caminho.
func writeSums(dir string, names ...string) error {
	var b strings.Builder
	for _, name := range names {
		f, err := os.Open(filepath.Join(dir, name))
		if err != nil {
			return err
		}
		h := sha256.New()
		_, err = io.Copy(h, f)
		f.Close()
		if err != nil {
			return err
		}
		fmt.Fprintf(&b, "%x  %s\n", h.Sum(nil), name)
	}
	return os.WriteFile(filepath.Join(dir, "SHA256SUMS"), []byte(b.String()), 0o644)
}
