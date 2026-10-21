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

	"mission/services-go/ledger/internal/config"
	"mission/services-go/ledger/internal/partition"
	"mission/services-go/ledger/internal/server"
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

	go partition.RunPeriodic(ctx, pool, cfg.PartitionSyncInterval, log)

	srv := server.New(server.Deps{
		Pool:         pool,
		ReadOnlyPool: roPool,
		Log:          log,
		OTel:         otelProvider,
	})

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
