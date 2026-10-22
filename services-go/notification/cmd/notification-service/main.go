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
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"mission/services-go/notification/internal/api"
	"mission/services-go/notification/internal/bulk"
	"mission/services-go/notification/internal/channel"
	"mission/services-go/notification/internal/config"
	"mission/services-go/notification/internal/consume"
	"mission/services-go/notification/internal/crypt"
	"mission/services-go/notification/internal/deliver"
	"mission/services-go/notification/internal/prefs"
	"mission/services-go/notification/internal/ratelimit"
	"mission/services-go/notification/internal/rt"
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

	// Luồng SSE qua Valkey Streams (DV-12-02); một địa chỉ là nút đơn, nhiều địa chỉ là cluster.
	var hub *rt.Hub
	var notifier *rt.Notifier
	var rdb redis.UniversalClient
	if len(cfg.ValkeyAddrs) > 0 {
		rdb = redis.NewUniversalClient(&redis.UniversalOptions{Addrs: cfg.ValkeyAddrs,
			Password: os.Getenv("VALKEY_PASSWORD"), PoolSize: 4 * rt.BusShards})
		defer rdb.Close()
		hub = rt.NewHub(rdb, log)
		hubCtx, hubStop := context.WithCancel(context.Background())
		defer hubStop()
		go hub.Run(hubCtx)
		notifier = &rt.Notifier{Pub: rt.Publisher{R: rdb}, Reader: store.Reader{Pool: pool}, Log: log}
	} else {
		log.Warn("chưa cấu hình VALKEY_ADDRS, tắt luồng thông báo thời gian thực")
	}

	var prefStore api.Prefs
	var prefImpl *prefs.Store
	if cfg.CryptoLocalKey != "" {
		key, err := base64.StdEncoding.DecodeString(cfg.CryptoLocalKey)
		if err == nil {
			var kr *crypt.LocalKeyring
			if kr, err = crypt.NewLocalKeyring(key); err == nil {
				prefImpl = &prefs.Store{Pool: pool, Field: crypt.Field{Keys: kr}}
				prefStore = *prefImpl
			}
		}
		if err != nil {
			log.Error("CRYPTO_LOCAL_KEY không hợp lệ", "err", err.Error())
			os.Exit(2)
		}
	} else {
		log.Warn("chưa cấu hình khoá mã hoá trường C2, tắt API thiết bị và tuỳ chọn kênh")
	}

	// Worker kênh ngoài (DV-12-03); cần khoá trường C2 để giải mã thiết bị và tính chỉ mục mù địa chỉ.
	external := externalChannels()
	var worker *deliver.Worker
	if len(external) > 0 {
		if prefImpl == nil {
			log.Error("NOTIFY_CHANNELS cần CRYPTO_LOCAL_KEY")
			os.Exit(2)
		}
		hc := httpClient()
		worker = &deliver.Worker{Pool: pool, Prefs: *prefImpl, Field: prefImpl.Field, Log: log,
			Counter: prov.AddCounter}
		for _, ch := range external {
			snd, err := buildSender(ch, hc)
			if err != nil {
				log.Error("cấu hình kênh không hợp lệ", "channel", ch, "err", err.Error())
				os.Exit(2)
			}
			if ch != channel.Push && worker.Directory == nil {
				if worker.Directory, err = buildDirectory(hc); err != nil {
					log.Error("cấu hình danh bạ không hợp lệ", "err", err.Error())
					os.Exit(2)
				}
			}
			worker.Lanes = append(worker.Lanes, deliver.Lane{Channel: ch, Sender: snd, Concurrency: concurrency(ch),
				Breaker: channel.NewBreaker(5, 30*time.Second)})
		}
		// Giới hạn tốc độ đợt hàng loạt theo kênh (DV-12-04): dùng chung qua Valkey, chưa có Valkey thì trong pod.
		limits := bulkLimits()
		worker.Limits = limits
		if rdb != nil {
			worker.Limiter = ratelimit.Valkey{R: rdb, Limits: limits}
		} else {
			worker.Limiter = &ratelimit.Memory{Limits: limits}
		}
		go worker.Run(ctx)
		log.Info("bật kênh ngoài", "channels", strings.Join(external, ","))
	}

	if len(cfg.KafkaBrokers) > 0 {
		proc, err := consume.NewProcessor(store.Beginner{Pool: pool}, log)
		if err != nil {
			log.Error("không tạo được bộ xử lý sự kiện", "err", err.Error())
			os.Exit(1)
		}
		proc.Writer.External = external
		proc.BulkThreshold = envInt("BULK_THRESHOLD", 500)
		c, err := consume.NewConsumer(cfg.KafkaBrokers, proc, log)
		if err != nil {
			log.Error("không tạo được consumer Kafka", "err", err.Error())
			os.Exit(1)
		}
		c.OTel = prov
		if notifier != nil {
			proc.Created = func(ctx context.Context, tenantID string, items []consume.Created) {
				byUser := map[string][]string{}
				for _, it := range items {
					byUser[it.RecipientID] = append(byUser[it.RecipientID], it.NotificationID)
				}
				notifier.Notify(ctx, tenantID, byUser)
			}
		}
		defer c.Close()
		go func() { _ = c.Run(ctx) }()
		x := &bulk.Expander{Pool: pool, Writer: proc.Writer, Chunk: envInt("BULK_CHUNK", 1000), Log: log,
			Created: proc.Created}
		go x.Run(ctx)
	} else {
		log.Warn("chưa cấu hình KAFKA_BROKERS, không chạy consumer")
	}

	deps := api.Deps{
		Inbox:     store.Reader{Pool: pool},
		Prefs:     prefStore,
		Ready:     func(ctx context.Context) error { return pool.Ping(ctx) },
		Log:       log,
		OTel:      prov,
		MetricSet: append(append([]string{}, consume.MetricNames...), deliver.MetricNames...),
		Gauges:    map[string]func() int64{},
	}
	if worker != nil {
		deps.Gauges = worker.Gauges()
	}
	if hub != nil {
		deps.Stream = hub
		deps.Unread = notifier.Unread
		deps.Gauges["notify_sse_connections"] = hub.Connections
	}
	srv := &http.Server{
		Addr: cfg.HTTPAddr,
		// Đọc hộp thông báo dùng pool chính vì đánh dấu đã đọc cần ghi; pool đọc dành cho bản sao (suy luận).
		Handler:           api.Handler(deps),
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
	if hub != nil {
		// Ngừng nhận kết nối SSE mới, đóng dần kết nối hiện có trong 30 giây để client nối sang pod khác.
		go hub.Drain(30 * time.Second)
	}
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
