package dnssync

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

type fakeReg struct {
	cells []Cell
	err   error
}

func (f *fakeReg) Cells(context.Context) ([]Cell, error) { return f.cells, f.err }

type fakeDNS struct {
	mu     sync.Mutex
	rec    map[string][]string
	writes int
}

func (f *fakeDNS) A(_ context.Context, n string) ([]string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.rec[n], nil
}

func (f *fakeDNS) ReplaceA(_ context.Context, n string, _ int, ips []string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.rec[n] = ips
	f.writes++
	return nil
}

func cfg() *Config {
	c := &Config{Zone: "danhgia.vn", Cells: map[string]CellConfig{
		"CELL-SH": {VIP: map[string]string{"HOALAC": "203.0.113.21", "PHIANAM": "198.51.100.21"}},
		"CELL-HN": {VIP: map[string]string{"HOALAC": "203.0.113.22", "PHIANAM": "198.51.100.22"}},
	}}
	if err := c.Validate(); err != nil {
		panic(err)
	}
	return c
}

func quiet() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestDoiCoThiDoiBanGhi(t *testing.T) {
	reg := &fakeReg{cells: []Cell{{Code: "CELL-SH", PrimaryDc: "HOALAC"}, {Code: "CELL-HN", PrimaryDc: "HOALAC"}}}
	dns := &fakeDNS{rec: map[string][]string{}}
	s := &Syncer{Cfg: cfg(), Registry: reg, DNS: dns, Log: quiet()}
	r, err := s.Run(context.Background())
	if err != nil || r.Changed != 2 || dns.rec["cell-sh.edge.danhgia.vn."][0] != "203.0.113.21" {
		t.Fatalf("lượt đầu: %+v %v %v", r, err, dns.rec)
	}
	// Không đổi gì thì không ghi.
	if r, _ := s.Run(context.Background()); r.Changed != 0 || r.Unchanged != 2 || dns.writes != 2 {
		t.Fatalf("lượt lặp phải không ghi: %+v writes=%d", r, dns.writes)
	}
	// Chỉ huy sự cố đổi active_site của CELL-SH sang phía Nam (DR-10 bước 10).
	reg.cells[0].ActiveDc = "PHIANAM"
	r, _ = s.Run(context.Background())
	if r.Changed != 1 || dns.rec["cell-sh.edge.danhgia.vn."][0] != "198.51.100.21" || dns.rec["cell-hn.edge.danhgia.vn."][0] != "203.0.113.22" {
		t.Fatalf("đổi cờ: %+v %v", r, dns.rec)
	}
	// Quay về (DR-16).
	reg.cells[0].ActiveDc = "HOALAC"
	if s.Run(context.Background()); dns.rec["cell-sh.edge.danhgia.vn."][0] != "203.0.113.21" {
		t.Fatalf("quay về: %v", dns.rec)
	}
}

func TestKhongDoiKhiKhongChacChan(t *testing.T) {
	dns := &fakeDNS{rec: map[string][]string{"cell-sh.edge.danhgia.vn.": {"203.0.113.21"}}}
	// SVC-03 ngừng (hoặc mission-core của cell ngừng, SVC-03 vẫn trả cờ cũ): không đổi bản ghi.
	s := &Syncer{Cfg: cfg(), Registry: &fakeReg{err: errors.New("503")}, DNS: dns, Log: quiet()}
	if _, err := s.Run(context.Background()); err == nil || dns.writes != 0 {
		t.Fatalf("SVC-03 lỗi phải dừng không ghi: %v writes=%d", err, dns.writes)
	}
	// Cell vắng khỏi SVC-03, cờ trỏ TTDL chưa có VIP, mã trùng: giữ nguyên.
	for _, cells := range [][]Cell{
		{},
		{{Code: "CELL-SH", PrimaryDc: "HOALAC", ActiveDc: "TAICHO"}},
	} {
		s.Registry = &fakeReg{cells: cells}
		if r, _ := s.Run(context.Background()); r.Changed != 0 || dns.writes != 0 {
			t.Fatalf("%v: không được ghi, %+v", cells, r)
		}
	}
	s.Registry = &fakeReg{cells: []Cell{{Code: "CELL-SH", PrimaryDc: "HOALAC"}, {Code: "CELL-SH", PrimaryDc: "PHIANAM"}}}
	if _, err := s.Run(context.Background()); err == nil || dns.writes != 0 {
		t.Fatalf("mã trùng phải dừng")
	}
}

func TestCauHinh(t *testing.T) {
	for _, c := range []Config{
		{},
		{Zone: "z"},
		{Zone: "z", Cells: map[string]CellConfig{"CELL-SH": {}}},
		{Zone: "z", Cells: map[string]CellConfig{"CELL-SH": {VIP: map[string]string{"HOALAC": "không-phải-ip"}}}},
		{Zone: "z", TTL: 3600, Cells: map[string]CellConfig{"CELL-SH": {VIP: map[string]string{"HOALAC": "10.0.0.1"}}}},
	} {
		if err := c.Validate(); err == nil {
			t.Fatalf("cấu hình sai phải bị từ chối: %+v", c)
		}
	}
	c := cfg()
	if c.TTL != 60 || c.EntryName("CELL-SH") != "cell-sh.edge.danhgia.vn." {
		t.Fatalf("mặc định: %d %s", c.TTL, c.EntryName("CELL-SH"))
	}
}

// Máy khách HTTP với SVC-03, Keycloak và PowerDNS giả lập.
func TestClientsHTTP(t *testing.T) {
	var gotAuth string
	var patched map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/token":
			_ = r.ParseForm()
			if r.Form.Get("grant_type") != "client_credentials" || r.Form.Get("client_secret") != "bi-mat-thu" {
				w.WriteHeader(401)
				return
			}
			_, _ = io.WriteString(w, `{"access_token":"tok","expires_in":300}`)
		case r.URL.Path == "/api/v1/registry/cells":
			gotAuth = r.Header.Get("Authorization")
			if r.URL.Query().Get("cursor") == "" {
				_, _ = io.WriteString(w, `{"items":[{"code":"CELL-SH","primaryDc":"HOALAC","activeDc":"PHIANAM"}],"nextCursor":"c2"}`)
			} else {
				_, _ = io.WriteString(w, `{"items":[{"code":"CELL-HN","primaryDc":"HOALAC"}],"nextCursor":null}`)
			}
		case strings.HasPrefix(r.URL.Path, "/api/v1/servers/localhost/zones/danhgia.vn."):
			if r.Header.Get("X-API-Key") != "khoa-thu" {
				w.WriteHeader(401)
				return
			}
			if r.Method == http.MethodPatch {
				_ = json.NewDecoder(r.Body).Decode(&patched)
				w.WriteHeader(204)
				return
			}
			_, _ = io.WriteString(w, `{"rrsets":[{"name":"cell-sh.edge.danhgia.vn.","type":"A","records":[{"content":"203.0.113.21","disabled":false}]}]}`)
		default:
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()
	reg := &RegistryClient{BaseURL: srv.URL, Token: &ClientCredentials{TokenURL: srv.URL + "/token", ClientID: "dns-sync", ClientSecret: "bi-mat-thu"}}
	cells, err := reg.Cells(context.Background())
	if err != nil || len(cells) != 2 || cells[0].Site() != "PHIANAM" || cells[1].Site() != "HOALAC" || gotAuth != "Bearer tok" {
		t.Fatalf("listCells: %v %+v %q", err, cells, gotAuth)
	}
	pd := &PowerDNS{BaseURL: srv.URL, Zone: "danhgia.vn", APIKey: "khoa-thu"}
	s := &Syncer{Cfg: cfg(), Registry: reg, DNS: pd, Log: quiet()}
	r, err := s.Run(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	rr := patched["rrsets"].([]any)[0].(map[string]any)
	if r.Changed != 2 || rr["changetype"] != "REPLACE" || rr["ttl"].(float64) != 60 || rr["type"] != "A" {
		t.Fatalf("PATCH PowerDNS: %+v %v", r, rr)
	}
}
