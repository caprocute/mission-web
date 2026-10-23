package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/config"
	"mission/services-go/ledger/internal/seal"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

// buildSealer dựng job niêm phong và job neo. Production phải có khoá niêm phong (SoftHSM/HSM qua
// esign-service, DV-10-03) và TSA_URL; dev thiếu thì dùng khoá và TSA nội bộ tạm thời.
func buildSealer(cfg *config.Config, pool *pgxpool.Pool, prov *otel.Provider, log *slog.Logger) (*seal.Sealer, *seal.Anchorer, error) {
	prod := os.Getenv("APP_ENV") == "production"
	var signer seal.Signer
	switch {
	case cfg.SealKeyFile != "" && cfg.SealCertFile != "":
		ks, err := seal.LoadKeySigner(cfg.SealKeyFile, cfg.SealCertFile, cfg.SealKeyID)
		if err != nil {
			return nil, nil, err
		}
		signer = ks
	case prod:
		return nil, nil, errors.New("production cần SEAL_KEY_FILE và SEAL_CERT_FILE cho khoá niêm phong")
	default:
		k, c, err := seal.SelfSigned("Khoá niêm phong tạm "+cfg.CellCode, false)
		if err != nil {
			return nil, nil, err
		}
		log.Warn("dùng khoá niêm phong tạm thời trong tiến trình (chỉ dev)")
		signer = &seal.KeySigner{Key: k, Cert: c, KeyID: cfg.SealKeyID}
	}
	var tsa seal.TSA
	switch {
	case cfg.TSAURL != "":
		tsa = &seal.HTTPTSA{URL: cfg.TSAURL}
	case prod:
		return nil, nil, errors.New("production cần TSA_URL cho dấu thời gian RFC 3161")
	default:
		local, err := seal.NewLocalTSA()
		if err != nil {
			return nil, nil, err
		}
		log.Warn("dùng TSA nội bộ tạm thời (chỉ dev)")
		tsa = local
	}
	sched := seal.DefaultSchedule(cfg.SealPeriodEnds)
	s := &seal.Sealer{Pool: pool, Signer: signer, TSA: tsa, Cell: cfg.CellCode, Schedule: &sched, OTel: prov, Log: log}
	a := &seal.Anchorer{Pool: pool, Cell: cfg.CellCode}
	if cfg.AnchorDir != "" {
		a.Store = seal.DirStore{Dir: cfg.AnchorDir}
	}
	return s, a, nil
}

type sealerFactory func() (*seal.Sealer, *seal.Anchorer, error)

// runSealAdmin chạy niêm phong đột xuất (idempotent theo mốc cuối cửa sổ) hoặc neo một ngày.
func runSealAdmin(ctx context.Context, build sealerFactory, args []string, log *slog.Logger) int {
	fs := flag.NewFlagSet(args[0], flag.ContinueOnError)
	windowEnd := fs.String("window-end", "", "mốc cuối cửa sổ RFC 3339; mặc định là bây giờ")
	kind := fs.String("kind", seal.KindAdHoc, "DINH_KY hoặc DOT_XUAT")
	day := fs.String("day", "", "ngày neo YYYY-MM-DD")
	if err := fs.Parse(args[1:]); err != nil {
		return 2
	}
	s, a, err := build()
	if err != nil {
		log.Error("không dựng được job niêm phong", "err", err.Error())
		return 1
	}
	if args[0] == "anchor" {
		d, err := time.ParseInLocation(time.DateOnly, *day, time.FixedZone("ICT", 7*3600))
		if err != nil {
			fmt.Fprintln(os.Stderr, "--day phải có dạng YYYY-MM-DD")
			return 2
		}
		doc, err := a.AnchorDay(ctx, d)
		if err != nil {
			log.Error("neo sổ cái thất bại", "day", *day, "err", err.Error())
			return 1
		}
		fmt.Printf("cell_seal_id=%s cell_root=%s\n", doc.CellSealID, doc.CellRoot)
		return 0
	}
	if *kind != seal.KindAdHoc && *kind != seal.KindPeriodic {
		fmt.Fprintln(os.Stderr, "--kind phải là DINH_KY hoặc DOT_XUAT")
		return 2
	}
	end := time.Now()
	if *windowEnd != "" {
		if end, err = time.Parse(time.RFC3339Nano, *windowEnd); err != nil {
			fmt.Fprintln(os.Stderr, "--window-end phải theo RFC 3339")
			return 2
		}
	}
	res, err := s.SealWindow(ctx, end, *kind)
	if err != nil {
		log.Error("niêm phong đột xuất thất bại", "err", err.Error())
		return 1
	}
	fmt.Printf("cell_seal_id=%s tenants=%d existing=%v late=%v\n", res.CellSealID, len(res.Tenants), res.Existing, res.Late)
	return 0
}
