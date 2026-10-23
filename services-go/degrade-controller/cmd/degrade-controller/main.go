// Command degrade-controller là bộ điều khiển suy giảm ba mức của cell (INF-25; TL-04 "Hành vi khi bão hoà",
// OPS-13, OPS-14). Cấu hình qua biến môi trường, xem README.md.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"vn.viettel.okrkpi/services-go/degrade-controller/internal/actuate"
	"vn.viettel.okrkpi/services-go/degrade-controller/internal/controller"
	"vn.viettel.okrkpi/services-go/degrade-controller/internal/engine"
	"vn.viettel.okrkpi/services-go/degrade-controller/internal/kube"
)

const saDir = "/var/run/secrets/kubernetes.io/serviceaccount"

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(log); err != nil {
		log.Error("degrade_controller_failed", "error", err.Error())
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg := engine.DefaultConfig()
	var err error
	if cfg.Base, err = intEnv("BASE_LEVEL", 0); err != nil {
		return err
	}
	for name, dst := range map[string]*time.Duration{
		"HOLD_DOWN": &cfg.HoldDown, "PIN_DEFAULT_TTL": &cfg.DefaultPinTTL, "PIN_MAX_TTL": &cfg.MaxPinTTL,
		"ALERT_STALE_AFTER": &cfg.StaleAfter, "ESCALATE_AFTER": &cfg.EscalateAfter,
	} {
		if err := durEnv(name, dst); err != nil {
			return err
		}
	}
	reconcile, tick := time.Minute, 15*time.Second
	if err := durEnv("RECONCILE_INTERVAL", &reconcile); err != nil {
		return err
	}
	if err := durEnv("TICK_INTERVAL", &tick); err != nil {
		return err
	}

	kc, err := kube.InCluster(os.Getenv("K8S_API"), envOr("K8S_TOKEN_FILE", saDir+"/token"), envOr("K8S_CA_FILE", saDir+"/ca.crt"))
	if err != nil {
		return err
	}
	gw := &actuate.Gateway{Kube: kc, Namespaces: list(envOr("GATEWAY_NAMESPACES", "edge")), LevelsDir: envOr("LEVELS_DIR", "/etc/vt-degrade")}
	acts := []actuate.Actuator{gw}
	if ns := list(os.Getenv("JOB_NAMESPACES")); len(ns) > 0 {
		acts = append(acts, &actuate.BackgroundJobs{Kube: kc, Namespaces: ns})
	}

	initial, found := gw.CurrentLevel(ctx)
	if !found {
		initial = cfg.Base
	}
	eng := engine.New(cfg, initial, time.Now())

	var notifier controller.Notifier
	if u := os.Getenv("NOTIFY_URL"); u != "" {
		notifier = &controller.WebhookNotifier{URL: u, Token: readFile(os.Getenv("NOTIFY_TOKEN_FILE"))}
	}
	alertLevels, err := parseAlertLevels(os.Getenv("ALERT_LEVELS"))
	if err != nil {
		return err
	}
	token := readFile(envOr("WEBHOOK_TOKEN_FILE", "/var/run/vt-degrade/token"))
	if token == "" {
		log.Warn("degrade_webhook_token_missing")
	}
	ctl := controller.New(eng, acts, notifier, controller.Options{
		Cell: os.Getenv("CELL"), Token: token, AlertLevels: alertLevels, Reconcile: reconcile, Log: log,
	})
	if err := ctl.Start(ctx); err != nil {
		log.Error("degrade_apply_failed", "phase", "start", "error", err.Error())
	}

	go func() {
		t := time.NewTicker(tick)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				_ = ctl.Tick(ctx)
			}
		}
	}()

	srv := &http.Server{Addr: ":" + envOr("PORT", "8080"), Handler: ctl.Handler(), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sctx)
	}()
	log.Info("degrade_controller_started", "port", srv.Addr, "level", initial, "base", cfg.Base)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func envOr(k, d string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return d
}

func list(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func intEnv(k string, d int) (int, error) {
	v := strings.TrimSpace(os.Getenv(k))
	if v == "" {
		return d, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 || n > engine.MaxLevel {
		return 0, fmt.Errorf("%s phải là 0..3", k)
	}
	return n, nil
}

func durEnv(k string, dst *time.Duration) error {
	v := strings.TrimSpace(os.Getenv(k))
	if v == "" {
		return nil
	}
	d, err := time.ParseDuration(v)
	if err != nil || d <= 0 {
		return fmt.Errorf("%s không phải khoảng thời gian hợp lệ", k)
	}
	*dst = d
	return nil
}

func readFile(p string) string {
	if p == "" {
		return ""
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

// parseAlertLevels đọc "TenCanhBao=1,TenKhac=3".
func parseAlertLevels(s string) (map[string]int, error) {
	out := map[string]int{}
	for _, p := range list(s) {
		name, lv, ok := strings.Cut(p, "=")
		n, err := strconv.Atoi(strings.TrimSpace(lv))
		if !ok || err != nil || n < 1 || n > engine.MaxLevel || strings.TrimSpace(name) == "" {
			return nil, fmt.Errorf("ALERT_LEVELS sai tại %q (dạng TenCanhBao=1..3)", p)
		}
		out[strings.TrimSpace(name)] = n
	}
	return out, nil
}
