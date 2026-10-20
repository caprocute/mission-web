// Lệnh tenant-registry chạy SVC-03 (danh bạ tenant toàn cục, TL-01 mục SVC-03).
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/api"
	"mission/services-go/tenant-registry/internal/config"
	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/crypto"
	"mission/services-go/tenant-registry/internal/platform/logx"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/tenant"
	"mission/services-go/tenant-registry/pkg/routecookie"
)

// migrationDir là nơi Dockerfile chép migration Flyway vào ảnh.
const migrationDir = "/db/migration/registry"

func main() {
	log := logx.New("tenant-registry")
	// Lệnh phụ cho initContainer của Job migrate (ảnh distroless không có shell): chép migration ra thư mục.
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
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Error("không tạo được pool CSDL", "err", err.Error())
		os.Exit(1)
	}
	defer pool.Close()

	verifier := auth.NewJWKSVerifier(auth.JWKSConfig{JWKSURL: cfg.JWKSURL, Issuers: cfg.Issuers, Audience: cfg.Audience})
	signer, err := routecookie.NewSigner(cfg.RoutingKey, cfg.RoutingKeyPrevious)
	if err != nil {
		log.Error("khoá cookie định tuyến không hợp lệ", "err", err.Error())
		os.Exit(2)
	}
	resolver := resolve.New(&resolve.DBLoader{Pool: pool}, resolve.Options{TTL: cfg.ResolveCacheTTL, StaleMax: cfg.ResolveStaleMax})
	go resolver.Listen(ctx, pool, log)
	enc, err := crypto.NewAESGCM(cfg.C2Key)
	if err != nil {
		log.Error("khoá mã hoá C2 không hợp lệ", "err", err.Error())
		os.Exit(2)
	}
	tenants := tenant.NewService(tenant.Rules{PoolMax: cfg.PoolMax, BridgeMax: cfg.BridgeMax, SiloRequiredAbove: cfg.SiloRequiredAbove,
		SiloRequiredSectors: cfg.SiloSectors, SiloRequiredCodes: cfg.SiloCodes, CapacityThreshold: cfg.CapacityThreshold}, enc)
	tenants.DomainSuffix, tenants.TenantLimit, tenants.AccountLimit = cfg.DomainSuffix, cfg.TenantLimit, cfg.AccountLimit
	// Nguồn số tài khoản từ cell chưa có giao thức (điểm chờ): chạy với nil chỉ dọn khoá chống ghi trùng.
	go tenants.RunPeriodic(ctx, pool, nil, cfg.AccountSyncInterval, log)
	srv := api.New(api.Deps{Pool: pool, Log: log, Verifier: verifier, StepUpMaxAge: cfg.StepUpMaxAge,
		Resolver: resolver, Cookies: signer, CookieDomain: cfg.CookieDomain, Tenants: tenants})

	httpSrv := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		log.Info("tenant-registry lắng nghe", "addr", cfg.HTTPAddr)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("máy chủ HTTP dừng bất thường", "err", err.Error())
			stop()
		}
	}()
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	_ = httpSrv.Shutdown(shutdownCtx)
	srv.WaitJobs()
	log.Info("tenant-registry đã dừng")
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
