// Command dns-sync đồng bộ bản ghi A điểm vào của cell theo cờ TTDL đang hoạt động trong SVC-03 (INF-20). Chạy vòng
// lặp với INTERVAL (mặc định 15s); INTERVAL=0 chạy một lượt rồi thoát. Cấu hình qua biến môi trường, xem README.md.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"vn.viettel.okrkpi/services-go/dns-sync/internal/dnssync"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(log); err != nil {
		log.Error("dns_sync_failed", "error", err.Error())
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	s, interval, err := build(os.Getenv, log)
	if err != nil {
		return err
	}
	if interval == 0 {
		r, err := s.Run(ctx)
		log.Info("dns_sync_done", "changed", r.Changed, "unchanged", r.Unchanged, "skipped", r.Skipped)
		return err
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		if r, err := s.Run(ctx); err != nil {
			log.Error("dns_sync_round_failed", "error", err.Error())
		} else if r.Changed > 0 || r.Skipped > 0 {
			log.Info("dns_sync_round", "changed", r.Changed, "unchanged", r.Unchanged, "skipped", r.Skipped)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
		}
	}
}

func build(env func(string) string, log *slog.Logger) (*dnssync.Syncer, time.Duration, error) {
	need := func(k string) (string, error) {
		if v := env(k); v != "" {
			return v, nil
		}
		return "", fmt.Errorf("thiếu biến %s", k)
	}
	file := func(k string) (string, error) {
		p, err := need(k)
		if err != nil {
			return "", err
		}
		b, err := os.ReadFile(p)
		return strings.TrimSpace(string(b)), err
	}
	cfgPath, err := need("CONFIG_FILE")
	if err != nil {
		return nil, 0, err
	}
	b, err := os.ReadFile(cfgPath)
	if err != nil {
		return nil, 0, err
	}
	var cfg dnssync.Config
	if err := json.Unmarshal(b, &cfg); err != nil {
		return nil, 0, fmt.Errorf("CONFIG_FILE: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, 0, err
	}
	interval := 15 * time.Second
	if v := env("INTERVAL"); v != "" {
		if interval, err = time.ParseDuration(v); err != nil {
			return nil, 0, fmt.Errorf("INTERVAL: %w", err)
		}
	}
	hc := &http.Client{Timeout: 10 * time.Second}
	reg := &dnssync.RegistryClient{HTTP: hc}
	if reg.BaseURL, err = need("REGISTRY_URL"); err != nil {
		return nil, 0, err
	}
	if tu := env("TOKEN_URL"); tu != "" {
		cc := &dnssync.ClientCredentials{TokenURL: tu, ClientID: env("CLIENT_ID"), HTTP: hc}
		if cc.ClientSecret, err = file("CLIENT_SECRET_FILE"); err != nil {
			return nil, 0, err
		}
		reg.Token = cc
	}
	pd := &dnssync.PowerDNS{Zone: cfg.Zone, ServerID: env("PDNS_SERVER_ID"), HTTP: hc}
	if pd.BaseURL, err = need("PDNS_URL"); err != nil {
		return nil, 0, err
	}
	if pd.APIKey, err = file("PDNS_API_KEY_FILE"); err != nil {
		return nil, 0, err
	}
	return &dnssync.Syncer{Cfg: &cfg, Registry: reg, DNS: pd, Log: log}, interval, nil
}
