package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// dockerClient fala com a API do Docker através do docker-proxy (somente GET).
type dockerClient struct {
	base    string // ex.: http://docker-proxy:2375
	project string // projeto do compose a monitorar
	http    *http.Client
}

type containerInfo struct {
	ID    string   `json:"Id"`
	Names []string `json:"Names"`
	State string   `json:"State"`
}

type statsResponse struct {
	CPUStats struct {
		CPUUsage struct {
			TotalUsage uint64 `json:"total_usage"` // nanossegundos
		} `json:"cpu_usage"`
	} `json:"cpu_stats"`
	MemoryStats struct {
		Usage uint64            `json:"usage"`
		Limit uint64            `json:"limit"`
		Stats map[string]uint64 `json:"stats"`
	} `json:"memory_stats"`
	Networks map[string]struct {
		RxBytes uint64 `json:"rx_bytes"`
		TxBytes uint64 `json:"tx_bytes"`
	} `json:"networks"`
}

func newDockerClient(base, project string) *dockerClient {
	return &dockerClient{base: strings.TrimRight(base, "/"), project: project, http: &http.Client{Timeout: 8 * time.Second}}
}

func (d *dockerClient) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, d.base+path, nil)
	if err != nil {
		return err
	}
	resp, err := d.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("docker API %s: HTTP %d", path, resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// containers lista os containers em execução do projeto.
func (d *dockerClient) containers(ctx context.Context) ([]containerInfo, error) {
	filters, _ := json.Marshal(map[string][]string{
		"label":  {"com.docker.compose.project=" + d.project},
		"status": {"running"},
	})
	var list []containerInfo
	err := d.get(ctx, "/containers/json?filters="+url.QueryEscape(string(filters)), &list)
	return list, err
}

// stats lê uma amostra instantânea (one-shot) de um container.
func (d *dockerClient) stats(ctx context.Context, id string) (statsResponse, error) {
	var s statsResponse
	err := d.get(ctx, "/containers/"+id+"/stats?stream=false&one-shot=true", &s)
	return s, err
}
