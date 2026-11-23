package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const containersJSON = `[{"Id":"aaa","Names":["/contaforte-backend-1"],"State":"running"},{"Id":"bbb","Names":["/contaforte-redis-1"],"State":"running"}]`

const statsA = `{"cpu_stats":{"cpu_usage":{"total_usage":2500000000}},
 "memory_stats":{"usage":300000000,"limit":805306368,"stats":{"inactive_file":50000000}},
 "networks":{"eth0":{"rx_bytes":1000,"tx_bytes":500},"eth1":{"rx_bytes":24,"tx_bytes":6}}}`

func fakeDocker(t *testing.T, failID string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/containers/json"):
			if !strings.Contains(r.URL.RawQuery, "com.docker.compose.project") {
				t.Errorf("filtro de projeto ausente: %s", r.URL.RawQuery)
			}
			io.WriteString(w, containersJSON)
		case strings.HasSuffix(r.URL.Path, "/stats"):
			if failID != "" && strings.Contains(r.URL.Path, failID) {
				http.Error(w, "boom", http.StatusInternalServerError)
				return
			}
			io.WriteString(w, statsA)
		default:
			http.NotFound(w, r)
		}
	}))
}

func newCollector(srv *httptest.Server) *collector {
	return &collector{docker: newDockerClient(srv.URL, "contaforte"), now: func() time.Time { return time.Unix(1700000000, 0) }}
}

func TestWorkingSetSubtractsInactiveFile(t *testing.T) {
	if got := workingSet(300, map[string]uint64{"inactive_file": 50}); got != 250 {
		t.Errorf("cgroup v2: got %d", got)
	}
	if got := workingSet(300, map[string]uint64{"total_inactive_file": 100}); got != 200 {
		t.Errorf("cgroup v1: got %d", got)
	}
	if got := workingSet(10, map[string]uint64{"inactive_file": 50}); got != 10 {
		t.Errorf("não pode ficar negativo: got %d", got)
	}
}

func TestCollectAndRender(t *testing.T) {
	srv := fakeDocker(t, "")
	defer srv.Close()

	samples, failures, err := newCollector(srv).collect(t.Context())
	if err != nil || failures != 0 || len(samples) != 2 {
		t.Fatalf("samples=%d failures=%d err=%v", len(samples), failures, err)
	}
	var buf bytes.Buffer
	render(&buf, samples, failures, true)
	out := buf.String()
	for _, want := range []string{
		`container_cpu_usage_seconds_total{name="contaforte-backend-1"} 2.5`,
		`container_memory_working_set_bytes{name="contaforte-backend-1"} 250000000`,
		`container_spec_memory_limit_bytes{name="contaforte-redis-1"} 805306368`,
		`container_network_receive_bytes_total{name="contaforte-backend-1"} 1024`,
		`container_network_transmit_bytes_total{name="contaforte-backend-1"} 506`,
		`container_last_seen{name="contaforte-backend-1"} 1.7e+09`,
		`container_exporter_up 1`,
		`# TYPE container_cpu_usage_seconds_total counter`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("faltou %q na saída:\n%s", want, out)
		}
	}
}

func TestOneContainerFailingDoesNotBreakTheRest(t *testing.T) {
	srv := fakeDocker(t, "bbb")
	defer srv.Close()

	samples, failures, err := newCollector(srv).collect(t.Context())
	if err != nil || failures != 1 || len(samples) != 1 || samples[0].name != "contaforte-backend-1" {
		t.Fatalf("samples=%v failures=%d err=%v", samples, failures, err)
	}
}

func TestMetricsEndpointReportsDockerDown(t *testing.T) {
	srv := fakeDocker(t, "")
	url := srv.URL
	srv.Close() // Docker "fora do ar"
	c := &collector{docker: newDockerClient(url, "contaforte"), now: time.Now}

	rec := httptest.NewRecorder()
	newMux(c).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/metrics", nil))

	if !strings.Contains(rec.Body.String(), "container_exporter_up 0") {
		t.Errorf("esperava container_exporter_up 0:\n%s", rec.Body.String())
	}
}

func TestHealthz(t *testing.T) {
	rec := httptest.NewRecorder()
	newMux(&collector{}).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != 200 {
		t.Errorf("status %d", rec.Code)
	}
}
