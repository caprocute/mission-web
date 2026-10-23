// Lệnh provision-gia-lap là bộ giả lập các hệ ngoài của saga cấp phát (YC-PT-13) cho dev compose: PowerDNS, API
// Kubernetes của cert-manager, API nội bộ mission-core (MOD-05 Policy Pack, MOD-08 UI Pack, lịch nghỉ), đọc cây tổ
// chức và mục lệch của org-service, Prometheus (độ trễ bản sao) và điểm cấp token. Dùng chung hệ giả provisiontest
// của kiểm thử. Keycloak không giả lập: tenant-registry gọi Keycloak dev thật. Chỉ dùng ở môi trường dev.
//
// Biến môi trường: HTTP_ADDR (mặc định :8080); GIA_LAP_CO_CAY=0 để mọi tenant chưa có cây tổ chức (mặc định coi như
// đã nạp, để kích hoạt chạy được khi org-service dev chưa nối với saga).
package main

import (
	"log/slog"
	"net/http"
	"os"
	"time"

	"mission/services-go/tenant-registry/internal/provision/provisiontest"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	addr := os.Getenv("HTTP_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	cell := provisiontest.NewCellHandler()
	cell.TreeDefault = os.Getenv("GIA_LAP_CO_CAY") != "0"
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	mux.Handle("/", cell.Handler())
	log.Info("bộ giả lập hệ ngoài của saga cấp phát (chỉ dùng dev)", "addr", addr, "coCay", cell.TreeDefault)
	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	if err := srv.ListenAndServe(); err != nil {
		log.Error("dừng", "err", err.Error())
		os.Exit(1)
	}
}
