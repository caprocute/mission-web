// Command edge-health phục vụ /healthz/edge của cell (INF-20). Chạy trong cụm cell, sau APISIX; cấu hình qua biến
// môi trường: LISTEN (mặc định :8080), WORKLOAD_PATH, PG_PATH, CACHE_FOR (mặc định 2s), K8S_API.
package main

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	"vn.viettel.okrkpi/services-go/dns-sync/internal/edgehealth"
)

const sa = "/var/run/secrets/kubernetes.io/serviceaccount"

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	tok, err := os.ReadFile(sa + "/token")
	if err != nil {
		log.Error("edge_health_failed", "error", err.Error())
		os.Exit(1)
	}
	pool := x509.NewCertPool()
	if ca, err := os.ReadFile(sa + "/ca.crt"); err == nil {
		pool.AppendCertsFromPEM(ca)
	}
	cache, err := time.ParseDuration(env("CACHE_FOR", "2s"))
	if err != nil {
		log.Error("edge_health_failed", "error", err.Error())
		os.Exit(1)
	}
	c := &edgehealth.Checker{
		Kube: &edgehealth.InCluster{Base: env("K8S_API", "https://kubernetes.default.svc"), Token: strings.TrimSpace(string(tok)),
			HTTP: &http.Client{Timeout: 2 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}}}},
		WorkloadPath: env("WORKLOAD_PATH", "/apis/argoproj.io/v1alpha1/namespaces/core/rollouts/mission-core"),
		PGPath:       env("PG_PATH", "/apis/postgresql.cnpg.io/v1/namespaces/data-pg/clusters/pg-core"),
		CacheFor:     cache,
	}
	mux := http.NewServeMux()
	mux.Handle("/healthz/edge", edgehealth.Handler(c, func(why string) { log.Warn("edge_health_fail", "reason", why) }))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok")) })
	srv := &http.Server{Addr: env("LISTEN", ":8080"), Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("edge_health_failed", "error", err.Error())
		os.Exit(1)
	}
}
