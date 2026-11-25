package main

// Medição de memória por contêiner direto nos cgroups v2 do host. O `docker stats` não mostra o que foi para o swap,
// e é justamente isso que faz a stack parecer menor do que é numa máquina apertada.

import (
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const cgroupRoot = "/sys/fs/cgroup"

// memUse é a memória de um contêiner num instante, em bytes.
type memUse struct {
	Working int64 // memory.current menos o cache de arquivo inativo (a mesma conta do docker stats)
	Swap    int64
	Limit   int64 // 0 = sem limite
	OOMKill int64 // mortes por falta de memória desde a criação do cgroup
}

// Total é o que o contêiner de fato ocupa: o residente mais o que foi para o swap.
func (m memUse) Total() int64 { return m.Working + m.Swap }

type container struct{ ID, Service string }

// cgroupDir acha o diretório do cgroup de um contêiner nos dois layouts do Docker (driver systemd e cgroupfs).
func cgroupDir(base, id string) (string, error) {
	for _, p := range []string{filepath.Join(base, "system.slice", "docker-"+id+".scope"), filepath.Join(base, "docker", id)} {
		if _, err := os.Stat(filepath.Join(p, "memory.current")); err == nil {
			return p, nil
		}
	}
	return "", fmt.Errorf("cgroup do contêiner %.12s não encontrado em %s (exige Linux com cgroup v2)", id, base)
}

// statValue lê o valor de uma chave num arquivo "chave valor" por linha (memory.stat, memory.events).
func statValue(raw, key string) int64 {
	for _, line := range strings.Split(raw, "\n") {
		if f := strings.Fields(line); len(f) == 2 && f[0] == key {
			v, _ := strconv.ParseInt(f[1], 10, 64)
			return v
		}
	}
	return 0
}

func readMemUse(dir string) (memUse, error) {
	read := func(name string) string {
		b, _ := os.ReadFile(filepath.Join(dir, name))
		return strings.TrimSpace(string(b))
	}
	current, err := strconv.ParseInt(read("memory.current"), 10, 64)
	if err != nil {
		return memUse{}, fmt.Errorf("memory.current ilegível em %s: %w", dir, err)
	}
	m := memUse{Working: max(current-statValue(read("memory.stat"), "inactive_file"), 0)}
	m.Swap, _ = strconv.ParseInt(read("memory.swap.current"), 10, 64) // ausente quando o host não tem swap
	m.Limit, _ = strconv.ParseInt(read("memory.max"), 10, 64)         // "max" (sem limite) vira 0
	m.OOMKill = statValue(read("memory.events"), "oom_kill")
	return m, nil
}

// projectContainers lista os contêineres em execução do projeto Compose.
func projectContainers(root string) ([]container, error) {
	ids, err := composeOut(root, "ps", "-q")
	if err != nil {
		return nil, err
	}
	list := strings.Fields(ids)
	if len(list) == 0 {
		return nil, fmt.Errorf("nenhum contêiner em execução: suba a stack com ./cf up")
	}
	args := append([]string{"inspect", "--format", `{{.Id}} {{index .Config.Labels "com.docker.compose.service"}}`}, list...)
	out, err := exec.Command("docker", args...).Output()
	if err != nil {
		return nil, fmt.Errorf("docker inspect: %w", err)
	}
	var cs []container
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		if f := strings.Fields(line); len(f) == 2 {
			cs = append(cs, container{ID: f[0], Service: f[1]})
		}
	}
	return cs, nil
}

// restartCounts devolve quantas vezes o Docker reiniciou cada contêiner.
func restartCounts(cs []container) map[string]string {
	out := map[string]string{}
	for _, c := range cs {
		if b, err := exec.Command("docker", "inspect", "--format", "{{.RestartCount}}", c.ID).Output(); err == nil {
			out[c.Service] = strings.TrimSpace(string(b))
		}
	}
	return out
}

// changed lista, em ordem, os serviços cujo contador mudou ou que sumiram.
func changed(before, after map[string]string) []string {
	var out []string
	for svc, v := range before {
		if after[svc] != v {
			out = append(out, svc)
		}
	}
	sort.Strings(out)
	return out
}

// memPeaks acumula o maior valor visto por serviço e o maior total simultâneo.
type memPeaks struct {
	By      map[string]memUse // Working e Swap são os do instante de maior Total
	SumPeak int64
	// Restarted são os serviços que reiniciaram durante a medição (o contador de OOM zera junto com o cgroup, e o
	// ExitOnOutOfMemoryError da JVM nem passa por ele).
	Restarted []string
}

func (p *memPeaks) add(sample map[string]memUse) {
	if p.By == nil {
		p.By = map[string]memUse{}
	}
	var sum int64
	for svc, m := range sample {
		sum += m.Total()
		best, seen := p.By[svc]
		oom := max(best.OOMKill, m.OOMKill)
		if !seen || m.Total() > best.Total() {
			best = m
		}
		best.OOMKill = oom
		p.By[svc] = best
	}
	p.SumPeak = max(p.SumPeak, sum)
}

// memSampler mede a stack em segundo plano até Stop.
type memSampler struct {
	stop, done chan struct{}
	containers []container
	restarts   map[string]string
	mu         sync.Mutex
	peaks      memPeaks
}

func startMemSampler(root, base string, every time.Duration) (*memSampler, error) {
	cs, err := projectContainers(root)
	if err != nil {
		return nil, err
	}
	dirs := map[string]string{}
	for _, c := range cs {
		dir, err := cgroupDir(base, c.ID)
		if err != nil {
			return nil, err
		}
		dirs[c.Service] = dir
	}
	s := &memSampler{stop: make(chan struct{}), done: make(chan struct{}), containers: cs, restarts: restartCounts(cs)}
	sample := func() {
		now := map[string]memUse{}
		for svc, dir := range dirs {
			if m, err := readMemUse(dir); err == nil {
				now[svc] = m
			}
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		s.peaks.add(now)
	}
	sample()
	go func() {
		defer close(s.done)
		t := time.NewTicker(every)
		defer t.Stop()
		for {
			select {
			case <-s.stop:
				sample()
				return
			case <-t.C:
				sample()
			}
		}
	}()
	return s, nil
}

// snapshot devolve uma cópia dos picos vistos até agora.
func (s *memSampler) snapshot() memPeaks {
	s.mu.Lock()
	defer s.mu.Unlock()
	by := make(map[string]memUse, len(s.peaks.By))
	for k, v := range s.peaks.By {
		by[k] = v
	}
	return memPeaks{By: by, SumPeak: s.peaks.SumPeak}
}

// Stop encerra a medição e devolve os picos. Pode ser chamado mais de uma vez.
func (s *memSampler) Stop() memPeaks {
	select {
	case <-s.done:
	default:
		close(s.stop)
		<-s.done
		s.peaks.Restarted = changed(s.restarts, restartCounts(s.containers))
	}
	return s.peaks
}

func mib(b int64) float64 { return float64(b) / (1 << 20) }

// writeMemTable imprime uma linha por serviço, do maior para o menor, e os totais.
func writeMemTable(w io.Writer, p memPeaks) {
	names := make([]string, 0, len(p.By))
	for n := range p.By {
		names = append(names, n)
	}
	sort.Slice(names, func(i, j int) bool {
		a, b := p.By[names[i]].Total(), p.By[names[j]].Total()
		if a != b {
			return a > b
		}
		return names[i] < names[j]
	})
	fmt.Fprintf(w, "  %-20s %9s %9s %9s %9s\n", "serviço", "uso MiB", "swap MiB", "total", "limite")
	var limits int64
	unlimited := false
	for _, n := range names {
		m := p.By[n]
		limit := "sem"
		if m.Limit > 0 {
			limit = fmt.Sprintf("%.0f", mib(m.Limit))
			limits += m.Limit
		} else {
			unlimited = true
		}
		note := ""
		if m.OOMKill > 0 {
			note = fmt.Sprintf("  OOM x%d", m.OOMKill)
		}
		fmt.Fprintf(w, "  %-20s %9.0f %9.0f %9.0f %9s%s\n", n, mib(m.Working), mib(m.Swap), mib(m.Total()), limit, note)
	}
	fmt.Fprintf(w, "  maior total simultâneo: %.0f MiB | soma dos limites: %.0f MiB", mib(p.SumPeak), mib(limits))
	if unlimited {
		fmt.Fprint(w, " (há serviço sem limite)")
	}
	fmt.Fprintln(w)
	if len(p.Restarted) > 0 {
		fmt.Fprintf(w, "  reiniciaram durante a medição: %s\n", strings.Join(p.Restarted, ", "))
	}
}

// oomKilled devolve os serviços que sofreram morte por falta de memória.
func oomKilled(p memPeaks) []string {
	var out []string
	for n, m := range p.By {
		if m.OOMKill > 0 {
			out = append(out, n)
		}
	}
	sort.Strings(out)
	return out
}

// checkMemory reprova a medição se algum serviço morreu por falta de memória ou se o backend ficou perto do limite.
// O swap conta: um backend que só cabe empurrando páginas para o swap não cabe.
func checkMemory(p memPeaks) error {
	if dead := oomKilled(p); len(dead) > 0 {
		return fmt.Errorf("morte por falta de memória (OOM) em: %s", strings.Join(dead, ", "))
	}
	if len(p.Restarted) > 0 {
		return fmt.Errorf("contêiner reiniciado durante a medição: %s", strings.Join(p.Restarted, ", "))
	}
	if b := p.By["backend"]; b.Limit > 0 && float64(b.Total()) > 0.92*float64(b.Limit) {
		return fmt.Errorf("memória do backend chegou a %.0f MiB, perto do limite de %.0f", mib(b.Total()), mib(b.Limit))
	}
	return nil
}

func cmdMem(args []string) error {
	fs := flag.NewFlagSet("mem", flag.ExitOnError)
	window := fs.Duration("for", 0, "mede durante este tempo e mostra os picos (ex.: 60s); 0 = um retrato de agora")
	fs.Parse(args)
	root, err := findRoot()
	if err != nil {
		return err
	}
	s, err := startMemSampler(root, cgroupRoot, 500*time.Millisecond)
	if err != nil {
		return err
	}
	if *window > 0 {
		fmt.Printf("Medindo por %s ...\n", *window)
		time.Sleep(*window)
	}
	writeMemTable(os.Stdout, s.Stop())
	return nil
}
