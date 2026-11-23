package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"text/tabwriter"
	"time"
)

type publisher struct {
	URL           string
	TargetPort    int
	PublishedPort int
}

type svcInfo struct {
	Name       string
	Service    string
	State      string
	Health     string
	Status     string
	Publishers []publisher
}

// parsePS lê a saída de `docker compose ps --format json` (um objeto por linha, ou um array).
func parsePS(out string) ([]svcInfo, error) {
	out = strings.TrimSpace(out)
	if out == "" {
		return nil, nil
	}
	if strings.HasPrefix(out, "[") {
		var arr []svcInfo
		return arr, json.Unmarshal([]byte(out), &arr)
	}
	var res []svcInfo
	for _, l := range trimLines(out) {
		var s svcInfo
		if err := json.Unmarshal([]byte(l), &s); err != nil {
			return nil, err
		}
		res = append(res, s)
	}
	return res, nil
}

type statsRow struct {
	Name     string
	CPUPerc  string
	MemUsage string
}

func dockerStats() map[string]statsRow {
	res := map[string]statsRow{}
	out, err := exec.Command("docker", "stats", "--no-stream", "--format", "{{json .}}").Output()
	if err != nil {
		return res
	}
	for _, l := range trimLines(string(out)) {
		var r statsRow
		if json.Unmarshal([]byte(l), &r) == nil {
			res[r.Name] = r
		}
	}
	return res
}

func cmdStatus() error {
	root, err := findRoot()
	if err != nil {
		return err
	}
	out, err := composeOut(root, "ps", "-a", "--format", "json")
	if err != nil {
		return err
	}
	svcs, err := parsePS(out)
	if err != nil {
		return err
	}
	if len(svcs) == 0 {
		fmt.Println("Nenhum serviço de pé. Rode: ./cf up")
		return nil
	}
	stats := dockerStats()
	tw := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "SERVIÇO\tESTADO\tSAÚDE\tCPU\tMEMÓRIA\tUPTIME")
	allGood := true
	for _, s := range svcs {
		health := s.Health
		if health == "" {
			health = "-"
		}
		if s.State != "running" || (s.Health != "" && s.Health != "healthy") {
			allGood = false
		}
		st := stats[s.Name]
		cpu, mem := "-", "-"
		if st.Name != "" {
			cpu = st.CPUPerc
			mem = strings.SplitN(st.MemUsage, " / ", 2)[0]
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\n", s.Service, s.State, health, cpu, mem, s.Status)
	}
	tw.Flush()

	env, _ := parseEnv(filepath.Join(root, ".env"))
	port := env["GATEWAY_PORT"]
	if port == "" {
		port = "8080"
	}
	fmt.Println()
	if httpOK("http://127.0.0.1:" + port + "/healthz") {
		ok("gateway responde em 127.0.0.1:%s", port)
	} else {
		fail("gateway não responde em 127.0.0.1:%s", port)
		allGood = false
	}
	if !allGood {
		return fmt.Errorf("há serviços com problema (veja ./cf logs <serviço> e ./cf doctor)")
	}
	return nil
}

func httpOK(url string) bool {
	c := http.Client{Timeout: 3 * time.Second}
	resp, err := c.Get(url)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode >= 200 && resp.StatusCode < 300
}
