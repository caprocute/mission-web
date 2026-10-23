// Lệnh ledger-service chạy SVC-09 (Sổ cái bất biến, TL-01 §14.1, TL-03 §2.8).
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/config"
	"mission/services-go/ledger/internal/forward"
	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/partition"
	"mission/services-go/ledger/internal/seal"
	"mission/services-go/ledger/internal/server"
	"mission/services-go/ledger/internal/verify"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

const migrationDir = "/db/migration/ledger"

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("service", "ledger")

	if len(os.Args) == 3 && os.Args[1] == "copy-migrations" {
		if err := copyMigrations(migrationDir, os.Args[2]); err != nil {
			log.Error("không chép được migration", "err", err.Error())
			os.Exit(1)
		}
		return
	}

	cfg, err := config.Load()
	if err != nil {
		log.Error("cấu hình không hợp lệ", "err", err.Error())
		os.Exit(2)
	}

	otelProvider, err := otel.Init(otel.Config{
		ServiceName:    cfg.ServiceName,
		ServiceVersion: "1.0.0",
		Cell:           cfg.CellCode,
		Environment:    os.Getenv("APP_ENV"),
	})
	if err != nil {
		log.Error("khởi tạo OpenTelemetry thất bại", "err", err.Error())
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Error("không tạo được pool CSDL chính (svc_ledger)", "err", err.Error())
		os.Exit(1)
	}
	defer pool.Close()

	roPool, err := pgxpool.New(ctx, cfg.ReadOnlyDatabaseURL)
	if err != nil {
		log.Error("không tạo được pool CSDL chỉ đọc (ro_ledger)", "err", err.Error())
		os.Exit(1)
	}
	defer roPool.Close()

	if os.Getenv("AUTO_MIGRATE") == "true" || os.Getenv("AUTO_MIGRATE") == "1" {
		if err := autoMigrateDev(ctx, pool, log); err != nil {
			log.Error("không chạy được auto-migrate", "err", err.Error())
			os.Exit(1)
		}
	}

	ing := ingest.NewIngester(pool, otelProvider, log)
	sealers := func() (*seal.Sealer, *seal.Anchorer, error) { return buildSealer(cfg, pool, otelProvider, log) }
	if len(os.Args) >= 2 && os.Args[1] == "admin" {
		os.Exit(runAdmin(ctx, ing, sealers, os.Args[2:], log))
	}

	go partition.RunPeriodic(ctx, pool, cfg.PartitionSyncInterval, log)

	if len(cfg.KafkaBrokers) > 0 {
		consumer, err := ingest.NewConsumer(cfg.KafkaBrokers, cfg.KafkaTopic, cfg.KafkaGroupID, ing, log)
		if err != nil {
			log.Error("không tạo được consumer ledger-ingester", "err", err.Error())
			os.Exit(1)
		}
		defer consumer.Close()
		go func() {
			if err := consumer.Run(ctx); err != nil {
				log.Error("consumer ledger-ingester dừng", "err", err.Error())
			}
		}()
		if cfg.ForwardEnabled {
			fw, err := forward.New(cfg.KafkaBrokers, cfg.ForwardTopics, log, func(name string, n int64) {
				if otelProvider != nil && n > 0 {
					otelProvider.AddCounter(name, n)
				}
			})
			if err != nil {
				log.Error("không tạo được bộ chuyển tiếp ledger-forwarder", "err", err.Error())
				os.Exit(1)
			}
			defer fw.Close()
			go func() {
				if err := fw.Run(ctx); err != nil {
					log.Error("bộ chuyển tiếp ledger-forwarder dừng", "err", err.Error())
				}
			}()
		}
	} else {
		log.Warn("chưa đặt KAFKA_BROKERS, ledger-ingester không chạy")
	}

	verifier := &verify.Service{Pool: pool, Cell: cfg.CellCode, OTel: otelProvider, Log: log,
		Engine: &verify.Engine{Pool: pool, Workers: cfg.VerifyWorker, Certs: sealCerts}}
	verifySched := &verify.Scheduler{Service: verifier}
	var lastSeal func() int64
	if cfg.SealEnabled {
		sealer, anchorer, err := sealers()
		if err != nil {
			log.Error("không dựng được job niêm phong", "err", err.Error())
			os.Exit(1)
		}
		sealer.AfterSeal = verifySched.AfterSeal
		lastSeal = sealer.LastSealed
		go func() { _ = sealer.Run(ctx, *sealer.Schedule, cfg.SealGrace) }()
		go anchorer.Run(ctx, func(msg string, args ...any) { log.Error(msg, args...) })
	}
	go verifySched.Run(ctx)

	deps := server.Deps{
		Pool:         pool,
		ReadOnlyPool: roPool,
		Log:          log,
		OTel:         otelProvider,
		Stats:        ing.Stats,
		Verify:       verifier,
		Auditor:      ing,
		Lag:          ing.LagHistogram,
		LagBounds:    ingest.LagBounds,
		Gauges: func() map[string]float64 {
			g := map[string]float64{"ledger.verify.last_finished_seconds": float64(verifier.LastFinished())}
			if lastSeal != nil {
				g["ledger.seal.last_sealed_seconds"] = float64(lastSeal())
			}
			return g
		},
	}
	if cfg.JWKSURL != "" || strings.EqualFold(os.Getenv("AUTH_ENABLED"), "false") {
		deps.Auth = auth.NewJWKSVerifier(auth.JWKSConfig{JWKSURL: cfg.JWKSURL, Issuers: cfg.Issuers, Audience: cfg.Audience})
	} else {
		log.Warn("chưa đặt OIDC_JWKS_URL, API kiểm tra toàn vẹn chưa mở")
	}
	srv := server.New(deps)

	httpSrv := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		log.Info("ledger-service lắng nghe", "addr", cfg.HTTPAddr, "cell", cfg.CellCode)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("máy chủ HTTP dừng bất thường", "err", err.Error())
			stop()
		}
	}()

	<-ctx.Done()
	verifier.Wait()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	_ = httpSrv.Shutdown(shutdownCtx)
	_ = otelProvider.Shutdown(shutdownCtx)
	log.Info("ledger-service đã dừng")
}

func copyMigrations(src, dst string) error {
	entries, err := os.ReadDir(src)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if e.IsDir() || filepath.Ext(e.Name()) != ".sql" {
			continue
		}
		b, err := os.ReadFile(filepath.Join(src, e.Name()))
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dst, e.Name()), b, 0o444); err != nil {
			return err
		}
	}
	return nil
}

func autoMigrateDev(ctx context.Context, pool *pgxpool.Pool, log *slog.Logger) error {
	var regClass *string
	if err := pool.QueryRow(ctx, "SELECT to_regclass('ledger.ledger_entry')::text").Scan(&regClass); err != nil {
		return err
	}
	if regClass != nil {
		return nil
	}
	migRoot := os.Getenv("MIGRATION_DIR")
	if migRoot == "" {
		for _, cand := range []string{"db/migration/ledger", "services-go/ledger/db/migration/ledger", migrationDir} {
			if st, err := os.Stat(cand); err == nil && st.IsDir() {
				migRoot = cand
				break
			}
		}
	}
	if migRoot == "" {
		return nil
	}
	entries, err := os.ReadDir(migRoot)
	if err != nil {
		return err
	}
	var files []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".sql") && strings.HasPrefix(e.Name(), "V") {
			files = append(files, filepath.Join(migRoot, e.Name()))
		}
	}
	sort.Strings(files)
	for _, f := range files {
		sqlBytes, err := os.ReadFile(f)
		if err != nil {
			return err
		}
		log.Info("áp migration ledger", "file", filepath.Base(f))
		if _, err := pool.Exec(ctx, string(sqlBytes)); err != nil {
			return err
		}
	}
	return nil
}
