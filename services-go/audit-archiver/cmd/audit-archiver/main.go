// Lệnh audit-archiver (INF-24):
//
//	audit-archiver            chạy dịch vụ lưu trữ nhật ký kiểm toán (Deployment)
//	audit-archiver kiem-tra   kiểm hằng ngày chữ ký, chuỗi kê khai, tệp dữ liệu và đối chiếu offset Kafka (CronJob)
//	audit-archiver khoa-cong-khai   in tệp khoá công khai của khoá ký Transit để giao cho kiểm toán viên
//
// Cấu hình qua biến môi trường, xem internal/config và ops/runbooks/audit-archiver.md.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
	"vn.viettel.okrkpi/services-go/vtplatform/logx"

	"mission/services-go/audit-archiver/internal/archiver"
	"mission/services-go/audit-archiver/internal/check"
	"mission/services-go/audit-archiver/internal/config"
	"mission/services-go/audit-archiver/internal/metrics"
	"mission/services-go/audit-archiver/internal/objstore"
	"mission/services-go/audit-archiver/internal/transit"
	"mission/services-go/audit-archiver/internal/verify"
)

func main() {
	cmd := ""
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	var err error
	switch cmd {
	case "", "chay":
		err = run(ctx)
	case "kiem-tra":
		err = daily(ctx, os.Args[2:])
	case "khoa-cong-khai":
		err = publicKeys(ctx)
	default:
		err = fmt.Errorf("lệnh không rõ %q (dùng: chay | kiem-tra | khoa-cong-khai)", cmd)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "audit-archiver:", err)
		if errors.Is(err, verify.ErrFailed) {
			os.Exit(1)
		}
		os.Exit(2)
	}
}

func logger(cell string) *slog.Logger {
	return logx.NewLogger(logx.Options{ServiceName: "audit-archiver", Cell: cell})
}

func run(ctx context.Context) error {
	cfg, err := config.Load(true, true)
	if err != nil {
		return err
	}
	log := logger(cfg.Cell)
	store, err := objstore.New(cfg.Store)
	if err != nil {
		return err
	}
	if err := store.CheckBucket(ctx); err != nil {
		return err
	}
	kopts, err := cfg.KafkaOpts()
	if err != nil {
		return err
	}
	reg := metrics.New()
	a, err := archiver.New(archiver.Config{
		Brokers: cfg.Brokers, KafkaOpts: kopts, Group: cfg.Group, Topics: cfg.Topics, Cell: cfg.Cell,
		SignKey: cfg.Transit.Key, Retention: cfg.Store.Retention, Window: cfg.Window,
		MaxRecords: cfg.MaxRecords, MaxBytes: cfg.MaxBytes,
	}, store, cfg.Transit, log, reg)
	if err != nil {
		return err
	}
	defer a.Close()

	var bucketOK atomic.Bool
	bucketOK.Store(true)
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
				bucketOK.Store(store.CheckBucket(cctx) == nil)
				cancel()
			}
		}
	}()
	mux := http.NewServeMux()
	mux.Handle("GET /metrics", reg.Handler())
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, _ *http.Request) {
		if !bucketOK.Load() {
			http.Error(w, "kho lưu trữ không sẵn sàng", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	srv := &http.Server{Addr: cfg.MgmtAddr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("máy chủ quản trị dừng", "loi", err.Error())
		}
	}()
	defer func() {
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sctx)
	}()
	log.Info("audit-archiver bắt đầu", "topic", cfg.Topics, "cua_so", cfg.Window.String(), "bucket", cfg.Store.Bucket)
	return a.Run(ctx)
}

func daily(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("kiem-tra", flag.ContinueOnError)
	window := fs.Duration("cua-so", 48*time.Hour, "kiểm các lô trong khoảng này tính tới hiện tại")
	full := fs.Bool("toan-bo", false, "kiểm toàn bộ chuỗi từ đầu")
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, err := config.Load(true, true)
	if err != nil {
		return err
	}
	log := logger(cfg.Cell)
	store, err := objstore.New(cfg.Store)
	if err != nil {
		return err
	}
	keys, err := cfg.Transit.PublicKeys(ctx)
	if err != nil {
		return err
	}
	kopts, err := cfg.KafkaOpts()
	if err != nil {
		return err
	}
	cl, err := kgo.NewClient(append([]kgo.Opt{kgo.SeedBrokers(cfg.Brokers...)}, kopts...)...)
	if err != nil {
		return err
	}
	defer cl.Close()
	adm := kadm.NewClient(cl)
	reg := metrics.New()
	failed := false
	now := time.Now()
	for _, topic := range cfg.Topics {
		vo := verify.Options{Cell: cfg.Cell, Topic: topic, Keys: keys}
		if !*full {
			vo.From = now.Add(-*window)
		}
		res, err := check.Run(ctx, store, adm, check.Options{Verify: vo, Group: cfg.Group})
		if err != nil {
			return err
		}
		check.Record(reg, topic, res, time.Now())
		out, _ := json.Marshal(res)
		if res.Kho.OK() {
			log.Info("kiểm kho lưu trữ đạt", "topic", topic, "so_tep", res.Kho.SoTep, "so_ban_ghi", res.Kho.SoBanGhi, "ket_qua", string(out))
		} else {
			failed = true
			// Mức lỗi kèm nhãn an ninh để luồng SIEM (INF-19) và cảnh báo nhận.
			log.Error("kiểm kho lưu trữ KHÔNG ĐẠT", "topic", topic, "canh_bao_an_ninh", true, "ket_qua", string(out))
		}
	}
	if err := reg.Push(ctx, cfg.PushgatewayURL, "audit-archiver-kiem-tra"); err != nil {
		log.Warn("không đẩy được chỉ số kiểm tra", "loi", err.Error())
	}
	if failed {
		return verify.ErrFailed
	}
	return nil
}

func publicKeys(ctx context.Context) error {
	cfg, err := config.Load(false, true)
	if err != nil {
		return err
	}
	keys, err := cfg.Transit.PublicKeys(ctx)
	if err != nil {
		return err
	}
	b, err := transit.EncodePublicKeys(cfg.Transit.Key, keys)
	if err != nil {
		return err
	}
	_, err = os.Stdout.Write(append(b, '\n'))
	return err
}
