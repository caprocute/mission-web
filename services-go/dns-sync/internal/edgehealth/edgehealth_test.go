package edgehealth

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type fakeKube struct {
	res   map[string]string
	calls int
}

func (f *fakeKube) Get(_ context.Context, p string) (map[string]any, error) {
	f.calls++
	s, ok := f.res[p]
	if !ok {
		return nil, errors.New("HTTP 404")
	}
	var m map[string]any
	return m, json.Unmarshal([]byte(s), &m)
}

const (
	wl = "/wl"
	pg = "/pg"
)

func checker(w, p string) (*Checker, *fakeKube) {
	k := &fakeKube{res: map[string]string{wl: w, pg: p}}
	return &Checker{Kube: k, WorkloadPath: wl, PGPath: pg}, k
}

const pgOK = `{"spec":{},"status":{"currentPrimary":"pg-core-1","readyInstances":3}}`

func TestDieuKien(t *testing.T) {
	cases := []struct {
		ten, w, p string
		ok        bool
	}{
		{"đủ", `{"spec":{"replicas":6},"status":{"readyReplicas":6}}`, pgOK, true},
		{"đúng một nửa", `{"spec":{"replicas":6},"status":{"readyReplicas":3}}`, pgOK, true},
		{"dưới một nửa", `{"spec":{"replicas":6},"status":{"readyReplicas":2}}`, pgOK, false},
		{"mission-core tắt", `{"spec":{"replicas":0},"status":{}}`, pgOK, false},
		{"replica cluster", `{"spec":{"replicas":3},"status":{"readyReplicas":3}}`, `{"spec":{"replica":{"enabled":true}},"status":{"currentPrimary":"x","readyInstances":1}}`, false},
		{"topology phân tán, primary ở nơi khác", `{"spec":{"replicas":3},"status":{"readyReplicas":3}}`, `{"spec":{"replica":{"primary":"pg-core-hl","self":"pg-core-sn"}},"status":{"currentPrimary":"x","readyInstances":1}}`, false},
		{"topology phân tán, primary ở đây", `{"spec":{"replicas":3},"status":{"readyReplicas":3}}`, `{"spec":{"replica":{"primary":"pg-core-hl","self":"pg-core-hl"}},"status":{"currentPrimary":"x","readyInstances":1}}`, true},
		{"chưa có primary", `{"spec":{"replicas":3},"status":{"readyReplicas":3}}`, `{"spec":{},"status":{"readyInstances":0}}`, false},
	}
	for _, c := range cases {
		ch, _ := checker(c.w, c.p)
		if ok, why := ch.Check(context.Background()); ok != c.ok {
			t.Errorf("%s: muốn %v, có %v (%s)", c.ten, c.ok, ok, why)
		}
	}
	ch, _ := checker(`{"spec":{"replicas":3},"status":{"readyReplicas":3}}`, pgOK)
	delete(ch.Kube.(*fakeKube).res, pg)
	if ok, _ := ch.Check(context.Background()); ok {
		t.Error("không đọc được pg-core phải không đạt")
	}
}

func TestHandlerVaDem(t *testing.T) {
	ch, k := checker(`{"spec":{"replicas":3},"status":{"readyReplicas":3}}`, pgOK)
	now := time.Unix(0, 0)
	ch.CacheFor, ch.Now = 2*time.Second, func() time.Time { return now }
	h := Handler(ch, nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz/edge", nil))
	if rec.Code != 200 || rec.Body.String() != "OK" {
		t.Fatalf("đạt: %d %q", rec.Code, rec.Body.String())
	}
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/healthz/edge", nil))
	if k.calls != 2 {
		t.Fatalf("trong 2 giây phải dùng đệm, gọi API %d lần", k.calls)
	}
	k.res[wl] = `{"spec":{"replicas":3},"status":{"readyReplicas":1}}`
	now = now.Add(3 * time.Second)
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz/edge", nil))
	if rec.Code != 503 || rec.Body.String() != "FAIL" {
		t.Fatalf("không đạt: %d %q", rec.Code, rec.Body.String())
	}
}

func TestInCluster(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer t" {
			w.WriteHeader(401)
			return
		}
		_, _ = w.Write([]byte(`{"spec":{"replicas":2}}`))
	}))
	defer srv.Close()
	k := &InCluster{Base: srv.URL, Token: "t", HTTP: srv.Client()}
	m, err := k.Get(context.Background(), "/x")
	if err != nil || num(m, "spec", "replicas") != 2 {
		t.Fatalf("%v %v", m, err)
	}
	k.Token = "sai"
	if _, err := k.Get(context.Background(), "/x"); err == nil {
		t.Fatal("401 phải lỗi")
	}
}
