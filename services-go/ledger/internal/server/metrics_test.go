package server

import (
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

// /metrics xuất đúng tên mà luật cảnh báo của cell dùng (LedgerLagHigh, LedgerChainBroken) ở định dạng
// văn bản Prometheus; ?format=json giữ dạng cũ.
func TestMetricsPrometheus(t *testing.T) {
	prov, _ := otel.Init(otel.Config{ServiceName: "ledger", Cell: "T"})
	prov.AddCounter("ledger.verify.failures_total", 2)
	s := New(Deps{OTel: prov, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Stats:     func() map[string]float64 { return map[string]float64{"ledger.ingest.chain.07.entries_per_second": 1.5} },
		Lag:       func() ([]uint64, float64, uint64) { return []uint64{1, 3}, 4.5, 4 },
		LagBounds: []float64{1, 30},
		Gauges:    func() map[string]float64 { return map[string]float64{"ledger.verify.last_finished_seconds": 1700000000} },
	})
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest("GET", "/metrics", nil))
	body := rec.Body.String()
	for _, want := range []string{
		"ledger_verify_failures_total 2",
		`ledger_ingest_chain_entries_per_second{chain="07"} 1.5`,
		`vt_ledger_lag_seconds_bucket{le="30"} 3`,
		`vt_ledger_lag_seconds_bucket{le="+Inf"} 4`,
		"vt_ledger_lag_seconds_count 4",
		"ledger_verify_last_finished_seconds 1.7e+09",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("thiếu %q trong\n%s", want, body)
		}
	}
	if !strings.HasPrefix(rec.Header().Get("Content-Type"), "text/plain; version=0.0.4") {
		t.Fatal(rec.Header().Get("Content-Type"))
	}
	rec = httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest("GET", "/metrics?format=json", nil))
	if !strings.Contains(rec.Body.String(), `"ledger.verify.failures_total":2`) {
		t.Fatal(rec.Body.String())
	}
}
