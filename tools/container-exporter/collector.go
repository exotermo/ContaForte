package main

import (
	"context"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"
)

// sample é a leitura de um container já convertida para as unidades das métricas.
type sample struct {
	name             string
	cpuSeconds       float64
	memWorkingSet    uint64
	memLimit         uint64
	netRx, netTx     uint64
	lastSeenUnixSecs float64
}

type collector struct {
	docker *dockerClient
	now    func() time.Time
}

// workingSet segue a definição do cAdvisor/kubelet: uso total menos o cache inativo.
func workingSet(usage uint64, stats map[string]uint64) uint64 {
	inactive := stats["inactive_file"]
	if inactive == 0 {
		inactive = stats["total_inactive_file"] // cgroup v1
	}
	if inactive > usage {
		return usage
	}
	return usage - inactive
}

func toSample(name string, s statsResponse, now time.Time) sample {
	out := sample{
		name:             name,
		cpuSeconds:       float64(s.CPUStats.CPUUsage.TotalUsage) / 1e9,
		memWorkingSet:    workingSet(s.MemoryStats.Usage, s.MemoryStats.Stats),
		memLimit:         s.MemoryStats.Limit,
		lastSeenUnixSecs: float64(now.Unix()),
	}
	for _, n := range s.Networks {
		out.netRx += n.RxBytes
		out.netTx += n.TxBytes
	}
	return out
}

// collect consulta todos os containers em paralelo. Falhas individuais são contadas, não derrubam a coleta.
func (c *collector) collect(ctx context.Context) (samples []sample, failures int, err error) {
	list, err := c.docker.containers(ctx)
	if err != nil {
		return nil, 0, err
	}
	var (
		mu sync.Mutex
		wg sync.WaitGroup
	)
	for _, ci := range list {
		wg.Add(1)
		go func(ci containerInfo) {
			defer wg.Done()
			name := strings.TrimPrefix(firstOr(ci.Names, ci.ID), "/")
			st, e := c.docker.stats(ctx, ci.ID)
			mu.Lock()
			defer mu.Unlock()
			if e != nil {
				failures++
				return
			}
			samples = append(samples, toSample(name, st, c.now()))
		}(ci)
	}
	wg.Wait()
	sort.Slice(samples, func(i, j int) bool { return samples[i].name < samples[j].name })
	return samples, failures, nil
}

func firstOr(s []string, def string) string {
	if len(s) > 0 {
		return s[0]
	}
	return def
}

// render escreve as métricas no formato de texto do Prometheus, com os mesmos nomes do cAdvisor
// para que os dashboards sirvam para os dois.
func render(w io.Writer, samples []sample, failures int, up bool) {
	type series struct {
		name, help, typ string
		value           func(sample) string
	}
	defs := []series{
		{"container_last_seen", "Última vez que o container foi visto (unix).", "gauge", func(s sample) string { return fmt.Sprintf("%g", s.lastSeenUnixSecs) }},
		{"container_cpu_usage_seconds_total", "Tempo de CPU consumido, em segundos.", "counter", func(s sample) string { return fmt.Sprintf("%g", s.cpuSeconds) }},
		{"container_memory_working_set_bytes", "Memória em uso (sem cache inativo).", "gauge", func(s sample) string { return fmt.Sprintf("%d", s.memWorkingSet) }},
		{"container_spec_memory_limit_bytes", "Limite de memória do container.", "gauge", func(s sample) string { return fmt.Sprintf("%d", s.memLimit) }},
		{"container_network_receive_bytes_total", "Bytes recebidos pela rede.", "counter", func(s sample) string { return fmt.Sprintf("%d", s.netRx) }},
		{"container_network_transmit_bytes_total", "Bytes enviados pela rede.", "counter", func(s sample) string { return fmt.Sprintf("%d", s.netTx) }},
	}
	for _, d := range defs {
		fmt.Fprintf(w, "# HELP %s %s\n# TYPE %s %s\n", d.name, d.help, d.name, d.typ)
		for _, s := range samples {
			fmt.Fprintf(w, "%s{name=%q} %s\n", d.name, s.name, d.value(s))
		}
	}
	upv := 0
	if up {
		upv = 1
	}
	fmt.Fprintf(w, "# HELP container_exporter_up 1 se a API do Docker respondeu.\n# TYPE container_exporter_up gauge\ncontainer_exporter_up %d\n", upv)
	fmt.Fprintf(w, "# HELP container_exporter_scrape_failures Containers cujas estatísticas falharam nesta coleta.\n# TYPE container_exporter_scrape_failures gauge\ncontainer_exporter_scrape_failures %d\n", failures)
}
