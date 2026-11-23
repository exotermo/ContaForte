// container-exporter expõe métricas por container (CPU, memória, rede) lendo a API do Docker
// por um proxy somente-leitura. Substitui o cAdvisor sem exigir o docker.sock/containerd no container.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"
)

func newMux(c *collector) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		defer cancel()
		samples, failures, err := c.collect(ctx)
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		if err != nil {
			log.Printf("coleta falhou: %v", err)
			render(w, nil, 0, false)
			return
		}
		render(w, samples, failures, true)
	})
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { fmt.Fprintln(w, "ok") })
	return mux
}

func main() {
	addr := flag.String("listen", ":9338", "endereço de escuta")
	docker := flag.String("docker", envOr("DOCKER_HOST_URL", "http://docker-proxy:2375"), "URL da API do Docker (proxy somente-leitura)")
	project := flag.String("project", envOr("COMPOSE_PROJECT", "contaforte"), "projeto do compose a monitorar")
	health := flag.Bool("healthcheck", false, "faz uma checagem de saúde local e sai (para o HEALTHCHECK do container)")
	flag.Parse()

	if *health {
		c := http.Client{Timeout: 2 * time.Second}
		resp, err := c.Get("http://127.0.0.1" + *addr + "/healthz")
		if err != nil || resp.StatusCode != http.StatusOK {
			os.Exit(1)
		}
		return
	}

	c := &collector{docker: newDockerClient(*docker, *project), now: time.Now}
	log.Printf("container-exporter em %s (docker=%s, projeto=%s)", *addr, *docker, *project)
	srv := &http.Server{Addr: *addr, Handler: newMux(c), ReadHeaderTimeout: 5 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
