// Lệnh notification-service chạy SVC-12 (TL-01 "SVC-12 notification-service", TL-03 "Schema notify"):
// consumer EVT-21, EVT-04, EVT-07, EVT-11, EVT-20 ghi hộp thông báo, API hộp thông báo của SCR-0002 và job
// duy trì phân vùng ngày giữ 90 ngày. Lệnh phụ `seed-templates <tệp>` nạp mẫu chung bằng vai trò mig_notify.
package main

import (
	"context"
	"encoding/base64"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/notification/internal/api"
	"mission/services-go/notification/internal/config"
	"mission/services-go/notification/internal/consume"
	"mission/services-go/notification/internal/crypt"
	"mission/services-go/notification/internal/prefs"
	"mission/services-go/notification/internal/store"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("service", "notification")

	if len(os.Args) == 3 && os.Args[1] == "seed-templates" {
		if err := seedTemplates(os.Args[2]); err != nil {
			log.Error("không nạp được mẫu thông báo chung", "err", err.Error())
			os.Exit(1)
		}
		return
	}

	cfg, err := config.Load()
	if err != nil {
		log.Error("cấu hình không hợp lệ", "err", err.Error())
		os.Exit(2)
	}
	prov, err := otel.Init(otel.Config{ServiceName: cfg.ServiceName, ServiceVersion: "1.0.0", Cell: cfg.CellCode,
		Environment: os.Getenv("APP_ENV")})
	if err != nil {
		log.Error("khởi tạo OpenTelemetry thất bại", "err", err.Error())
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Error("không tạo được pool CSDL (svc_notify)", "err", err.Error())
		os.Exit(1)
	}
	defer pool.Close()
	roPool, err := pgxpool.New(ctx, cfg.ReadOnlyDatabaseURL)
	if err != nil {
		log.Error("không tạo được pool CSDL đọc", "err", err.Error())
		os.Exit(1)
	}
	defer roPool.Close()

	go maintain(ctx, pool, cfg.MaintainInterval, log)

	if len(cfg.KafkaBrokers) > 0 {
		proc, err := consume.NewProcessor(store.Beginner{Pool: pool}, log)
		if err != nil {
			log.Error("không tạo được bộ xử lý sự kiện", "err", err.Error())
			os.Exit(1)
		}
		c, err := consume.NewConsumer(cfg.KafkaBrokers, proc, log)
		if err != nil {
			log.Error("không tạo được consumer Kafka", "err", err.Error())
			os.Exit(1)
		}
		c.OTel = prov
		defer c.Close()
		go func() { _ = c.Run(ctx) }()
	} else {
		log.Warn("chưa cấu hình KAFKA_BROKERS, không chạy consumer")
	}

	var prefStore api.Prefs
	if cfg.CryptoLocalKey != "" {
		key, err := base64.StdEncoding.DecodeString(cfg.CryptoLocalKey)
		if err == nil {
			var kr *crypt.LocalKeyring
			if kr, err = crypt.NewLocalKeyring(key); err == nil {
				prefStore = prefs.Store{Pool: pool, Field: crypt.Field{Keys: kr}}
			}
		}
		if err != nil {
			log.Error("CRYPTO_LOCAL_KEY không hợp lệ", "err", err.Error())
			os.Exit(2)
		}
	} else {
		log.Warn("chưa cấu hình khoá mã hoá trường C2, tắt API thiết bị và tuỳ chọn kênh")
	}

	srv := &http.Server{
		Addr: cfg.HTTPAddr,
		// Đọc hộp thông báo dùng pool chính vì đánh dấu đã đọc cần ghi; pool đọc dành cho bản sao (suy luận).
		Handler: api.Handler(api.Deps{
			Inbox:     store.Reader{Pool: pool},
			Prefs:     prefStore,
			Ready:     func(ctx context.Context) error { return pool.Ping(ctx) },
			Log:       log,
			OTel:      prov,
			MetricSet: consume.MetricNames,
		}),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		log.Info("notification-service lắng nghe", "addr", cfg.HTTPAddr, "cell", cfg.CellCode)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("máy chủ HTTP dừng bất thường", "err", err.Error())
			stop()
		}
	}()

	<-ctx.Done()
	sctx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	_ = srv.Shutdown(sctx)
	_ = prov.Shutdown(sctx)
	log.Info("notification-service đã dừng")
}

func maintain(ctx context.Context, pool *pgxpool.Pool, every time.Duration, log *slog.Logger) {
	for {
		created, dropped, err := store.Maintain(ctx, pool, time.Now())
		if err != nil {
			log.Error("duy trì phân vùng thất bại", "err", err.Error())
		} else if created > 0 || dropped > 0 {
			log.Info("đã duy trì phân vùng", "created", created, "dropped", dropped)
		}
		if _, err := pool.Exec(ctx, `SELECT notify.fn_purge_inbox_processed()`); err != nil && ctx.Err() == nil {
			log.Error("dọn inbox thất bại", "err", err.Error())
		}
		if n, err := prefs.RevokeStale(ctx, pool); err != nil && ctx.Err() == nil {
			log.Error("thu hồi thiết bị lâu không dùng thất bại", "err", err.Error())
		} else if n > 0 {
			log.Info("đã thu hồi thiết bị lâu không dùng", "count", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(every):
		}
	}
}

func seedTemplates(path string) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	f, err := store.ParseSeed(b)
	if err != nil {
		return err
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, os.Getenv("MIGRATION_DATABASE_URL"))
	if err != nil {
		return err
	}
	defer conn.Close(ctx)
	return store.UpsertGlobalTemplates(ctx, conn, f)
}
