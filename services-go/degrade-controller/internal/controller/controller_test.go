package controller

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"vn.viettel.okrkpi/services-go/degrade-controller/internal/actuate"
	"vn.viettel.okrkpi/services-go/degrade-controller/internal/engine"
	"vn.viettel.okrkpi/services-go/degrade-controller/internal/kube"
)

// fakeKube là API server giả: giữ đối tượng theo đường dẫn, lọc theo nhãn (khớp đúng hoặc chỉ có khoá),
// áp merge-patch (null xoá khoá) và ghi lại thứ tự lệnh vá.
type fakeKube struct {
	mu      sync.Mutex
	objs    map[string]map[string]any // "/apis/g/v/namespaces/ns/plural" → name → obj
	patches []string
	failing bool
}

func newFakeKube() *fakeKube { return &fakeKube{objs: map[string]map[string]any{}} }

func (f *fakeKube) add(r kube.Resource, ns, name string, labels, ann map[string]string) {
	key := "/apis/" + r.Group + "/" + r.Version + "/namespaces/" + ns + "/" + r.Plural
	if f.objs[key] == nil {
		f.objs[key] = map[string]any{}
	}
	md := map[string]any{"name": name, "namespace": ns, "labels": toAny(labels), "annotations": toAny(ann)}
	f.objs[key][name] = map[string]any{"metadata": md, "spec": map[string]any{}}
}

func toAny(m map[string]string) map[string]any {
	out := map[string]any{}
	for k, v := range m {
		out[k] = v
	}
	return out
}

func (f *fakeKube) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failing {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	switch r.Method {
	case http.MethodGet:
		sel := r.URL.Query().Get("labelSelector")
		k, v, exact := strings.Cut(sel, "=")
		var items []any
		for _, o := range f.objs[r.URL.Path] {
			labels := o.(map[string]any)["metadata"].(map[string]any)["labels"].(map[string]any)
			got, ok := labels[k]
			if ok && (!exact || got == v) {
				items = append(items, o)
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"items": items})
	case http.MethodPatch:
		dir, name := filepath.Dir(r.URL.Path), filepath.Base(r.URL.Path)
		o, ok := f.objs[dir][name]
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		var patch map[string]any
		_ = json.NewDecoder(r.Body).Decode(&patch)
		merge(o.(map[string]any), patch)
		f.patches = append(f.patches, filepath.Base(dir)+"/"+name)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("{}"))
	}
}

func merge(dst, patch map[string]any) {
	for k, v := range patch {
		switch pv := v.(type) {
		case nil:
			delete(dst, k)
		case map[string]any:
			sub, ok := dst[k].(map[string]any)
			if !ok {
				sub = map[string]any{}
				dst[k] = sub
			}
			merge(sub, pv)
		default:
			dst[k] = v
		}
	}
}

func (f *fakeKube) meta(r kube.Resource, ns, name, field string) map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := "/apis/" + r.Group + "/" + r.Version + "/namespaces/" + ns + "/" + r.Plural
	return f.objs[key][name].(map[string]any)["metadata"].(map[string]any)[field].(map[string]any)
}

func (f *fakeKube) plugins(ns, name string) any {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := "/apis/apisix.apache.org/v2/namespaces/" + ns + "/apisixpluginconfigs"
	return f.objs[key][name].(map[string]any)["spec"].(map[string]any)["plugins"]
}

func (f *fakeKube) takePatches() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := f.patches
	f.patches = nil
	return p
}

type fakeNotifier struct {
	mu   sync.Mutex
	sent []Notice
}

func (n *fakeNotifier) Notify(_ context.Context, msg Notice) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.sent = append(n.sent, msg)
	return nil
}

type harness struct {
	t     *testing.T
	kube  *fakeKube
	ctl   *Controller
	srv   *httptest.Server
	now   time.Time
	notes *fakeNotifier
	gw    *actuate.Gateway
}

const token = "tok-kiem-thu"

func newHarness(t *testing.T) *harness {
	t.Helper()
	fk := newFakeKube()
	for _, cls := range []string{"1", "2"} {
		fk.add(kube.ApisixPluginConfig, "edge", "protected-api-p"+cls,
			map[string]string{actuate.ManagedSelector[:len("vt.io/degrade-managed")]: "true", actuate.ClassLabel: cls, actuate.LevelLabel: "0"}, nil)
	}
	fk.add(kube.ApisixPluginConfig, "edge", "khong-quan-ly", map[string]string{actuate.ClassLabel: "2"}, nil)
	fk.add(kube.ScaledObject, "jobs", "dong-bo-tich-hop", map[string]string{actuate.PauseFromLabel: "2"}, nil)
	fk.add(kube.ScaledObject, "jobs", "tinh-lai-hang-loat", map[string]string{actuate.PauseFromLabel: "2"},
		map[string]string{actuate.KedaPaused: "true"}) // người khác tạm dừng tay
	fk.add(kube.ScaledObject, "jobs", "cham-diem", map[string]string{"app": "scoring"}, nil)
	ks := httptest.NewServer(fk)
	t.Cleanup(ks.Close)

	dir := t.TempDir()
	for _, cls := range []string{"1", "2", "3"} {
		for lv := 0; lv <= 3; lv++ {
			body := `[{"name":"vt-degrade","enable":true,"config":{"class":` + cls + `,"level":` + string(rune('0'+lv)) + `}}]`
			if err := os.WriteFile(filepath.Join(dir, "p"+cls+"-"+string(rune('0'+lv))+".json"), []byte(body), 0o600); err != nil {
				t.Fatal(err)
			}
		}
	}
	kc := &kube.Client{Base: ks.URL, HTTP: ks.Client()}
	h := &harness{t: t, kube: fk, now: time.Date(2026, 11, 7, 8, 0, 0, 0, time.UTC), notes: &fakeNotifier{}}
	h.gw = &actuate.Gateway{Kube: kc, Namespaces: []string{"edge"}, LevelsDir: dir}
	acts := []actuate.Actuator{h.gw, &actuate.BackgroundJobs{Kube: kc, Namespaces: []string{"jobs"}}}
	eng := engine.New(engine.DefaultConfig(), 0, h.now)
	h.ctl = New(eng, acts, h.notes, Options{Cell: "cell-hn-01", Token: token, Now: func() time.Time { return h.now }})
	h.srv = httptest.NewServer(h.ctl.Handler())
	t.Cleanup(h.srv.Close)
	return h
}

func (h *harness) post(path, body, tok string) (int, map[string]any) {
	h.t.Helper()
	req, _ := http.NewRequest(http.MethodPost, h.srv.URL+path, strings.NewReader(body))
	if tok != "" {
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	res, err := h.srv.Client().Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

func am(status, name, level, fp string) string {
	return `{"groupKey":"{}:{cell=\"cell-hn-01\"}","alerts":[{"status":"` + status + `","fingerprint":"` + fp +
		`","labels":{"alertname":"` + name + `","degrade_level":"` + level + `"}}]}`
}

// Đầu cuối: webhook mức 3 → cổng mức 3, tác vụ nền tạm dừng (sau cổng); hết cảnh báo 10 phút → về 0,
// gỡ tạm dừng trước rồi mới mở cổng; tạm dừng tay của người khác giữ nguyên.
func TestWebhookMuc3VaHaMuc(t *testing.T) {
	h := newHarness(t)
	code, st := h.post("/alertmanager", am("firing", "CNPGPrimaryCPUSaturated", "3", "fp-cpu"), token)
	if code != http.StatusOK || st["level"].(float64) != 3 {
		t.Fatalf("webhook mức 3: %d %v", code, st)
	}
	if got := h.kube.meta(kube.ApisixPluginConfig, "edge", "protected-api-p2", "labels")[actuate.LevelLabel]; got != "3" {
		t.Fatalf("nhãn mức trên cổng = %v", got)
	}
	pl, _ := json.Marshal(h.kube.plugins("edge", "protected-api-p1"))
	if string(pl) != `[{"config":{"class":1,"level":3},"enable":true,"name":"vt-degrade"}]` {
		t.Fatalf("bộ plugin lớp 1 mức 3 sai: %s", pl)
	}
	if h.kube.plugins("edge", "khong-quan-ly") != nil {
		t.Fatal("không được vá ApisixPluginConfig ngoài nhãn quản lý")
	}
	if a := h.kube.meta(kube.ScaledObject, "jobs", "dong-bo-tich-hop", "annotations"); a[actuate.KedaPaused] != "true" || a[actuate.PausedBy] != "degrade-controller" {
		t.Fatalf("tác vụ nền không gấp phải tạm dừng ở mức ≥ 2: %v", a)
	}
	if a := h.kube.meta(kube.ScaledObject, "jobs", "cham-diem", "annotations"); len(a) != 0 {
		t.Fatalf("ScaledObject không gắn nhãn không được đụng: %v", a)
	}
	up := h.kube.takePatches()
	if len(up) != 3 || !strings.HasPrefix(up[0], "apisixpluginconfigs/") || up[2] != "scaledobjects/dong-bo-tich-hop" {
		t.Fatalf("tăng mức phải vá cổng trước, tác vụ nền sau: %v", up)
	}

	h.now = h.now.Add(time.Minute)
	if _, st = h.post("/alertmanager", am("resolved", "CNPGPrimaryCPUSaturated", "3", "fp-cpu"), token); st["level"].(float64) != 3 {
		t.Fatalf("hết cảnh báo không được hạ ngay: %v", st)
	}
	h.now = h.now.Add(10 * time.Minute)
	if err := h.ctl.Tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := h.kube.meta(kube.ApisixPluginConfig, "edge", "protected-api-p1", "labels")[actuate.LevelLabel]; got != "0" {
		t.Fatalf("sau 10 phút phải về mức 0, nhãn %v", got)
	}
	if a := h.kube.meta(kube.ScaledObject, "jobs", "dong-bo-tich-hop", "annotations"); len(a) != 0 {
		t.Fatalf("hạ mức phải gỡ tạm dừng do bộ điều khiển đặt: %v", a)
	}
	if a := h.kube.meta(kube.ScaledObject, "jobs", "tinh-lai-hang-loat", "annotations"); a[actuate.KedaPaused] != "true" {
		t.Fatalf("không được gỡ tạm dừng tay của người khác: %v", a)
	}
	down := h.kube.takePatches()
	if len(down) != 3 || down[0] != "scaledobjects/dong-bo-tich-hop" || !strings.HasPrefix(down[2], "apisixpluginconfigs/") {
		t.Fatalf("hạ mức phải gỡ theo thứ tự ngược: %v", down)
	}

	h.notes.mu.Lock()
	defer h.notes.mu.Unlock()
	if len(h.notes.sent) != 2 || h.notes.sent[0].Level != 3 || h.notes.sent[1].Source != "hold_down" || h.notes.sent[1].Cell != "cell-hn-01" {
		t.Fatalf("thông báo kênh trực sai: %+v", h.notes.sent)
	}
}

// Ghim tay có thời hạn qua POST /level (vtctl degrade pin), ưu tiên hơn cảnh báo; bỏ ghim bằng null.
func TestGhimTayQuaAPI(t *testing.T) {
	h := newHarness(t)
	h.post("/alertmanager", am("firing", "Http5xxHigh", "2", "fp-5xx"), token)
	code, st := h.post("/level", `{"level":1,"ttl":"30m","reason":"OPS-14 nguyen.van.a: thử"}`, token)
	if code != http.StatusOK || st["level"].(float64) != 1 || st["manual"].(float64) != 1 {
		t.Fatalf("ghim mức 1: %d %v", code, st)
	}
	if st["pin"].(map[string]any)["until"] != h.now.Add(30*time.Minute).Format(time.RFC3339) {
		t.Fatalf("hạn ghim sai: %v", st["pin"])
	}
	h.now = h.now.Add(31 * time.Minute)
	_ = h.ctl.Tick(context.Background())
	res, _ := h.srv.Client().Get(h.srv.URL + "/level")
	var s engine.State
	_ = json.NewDecoder(res.Body).Decode(&s)
	res.Body.Close()
	if s.Level != 2 || s.Manual != nil {
		t.Fatalf("hết hạn ghim phải về mức theo cảnh báo: %+v", s)
	}

	for _, c := range []struct {
		body string
		code int
	}{
		{`{"level":4}`, 422}, {`{"level":"a"}`, 422}, {`{"level":1,"ttl":"25h"}`, 422}, {`{"level":1,"ttl":"-1m"}`, 422}, {`x`, 400},
	} {
		if code, _ := h.post("/level", c.body, token); code != c.code {
			t.Fatalf("%s: mã %d, muốn %d", c.body, code, c.code)
		}
	}
	if code, _ := h.post("/level", `{"level":null}`, token); code != http.StatusOK {
		t.Fatalf("bỏ ghim: %d", code)
	}
}

func TestXacThuc(t *testing.T) {
	h := newHarness(t)
	for _, tok := range []string{"", "sai"} {
		if code, _ := h.post("/alertmanager", am("firing", "X", "3", "f"), tok); code != http.StatusUnauthorized {
			t.Fatalf("token %q: %d", tok, code)
		}
		if code, _ := h.post("/level", `{"level":3}`, tok); code != http.StatusUnauthorized {
			t.Fatalf("token %q: %d", tok, code)
		}
	}
	if got := h.kube.meta(kube.ApisixPluginConfig, "edge", "protected-api-p1", "labels")[actuate.LevelLabel]; got != "0" {
		t.Fatal("yêu cầu không xác thực không được đổi mức")
	}
}

// Không ghi được ra cụm: trả 502, đếm lỗi, và Tick sau ghi lại khi API server hồi phục.
func TestLoiGhiVaThuLai(t *testing.T) {
	h := newHarness(t)
	h.kube.failing = true
	if code, _ := h.post("/level", `{"level":2}`, token); code != http.StatusBadGateway {
		t.Fatalf("muốn 502, nhận %d", code)
	}
	h.kube.mu.Lock()
	h.kube.failing = false
	h.kube.mu.Unlock()
	h.now = h.now.Add(time.Minute)
	if err := h.ctl.Tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := h.kube.meta(kube.ApisixPluginConfig, "edge", "protected-api-p1", "labels")[actuate.LevelLabel]; got != "2" {
		t.Fatalf("Tick phải ghi lại mức 2, nhãn %v", got)
	}
	res, _ := h.srv.Client().Get(h.srv.URL + "/metrics")
	raw, _ := io.ReadAll(res.Body)
	res.Body.Close()
	for _, want := range []string{"vt_degrade_level 2\n", "vt_degrade_pinned 1\n", "vt_degrade_errors_total 1\n", "vt_degrade_transitions_total 1\n"} {
		if !strings.Contains(string(raw), want) {
			t.Fatalf("metric thiếu %q:\n%s", want, raw)
		}
	}
}

// Mức ≥ 2 quá 15 phút: thông báo nâng SEV-2 một lần.
func TestNangSEV2(t *testing.T) {
	h := newHarness(t)
	h.post("/alertmanager", am("firing", "Http5xxHigh", "2", "fp"), token)
	for i := 0; i < 20; i++ {
		h.now = h.now.Add(time.Minute)
		h.post("/alertmanager", am("firing", "Http5xxHigh", "2", "fp"), token)
		_ = h.ctl.Tick(context.Background())
	}
	h.notes.mu.Lock()
	defer h.notes.mu.Unlock()
	n := 0
	for _, s := range h.notes.sent {
		if s.Event == "degrade_escalate_sev2" {
			n++
			if s.Severity != "SEV-2" || s.Level != 2 {
				t.Fatalf("thông báo SEV-2 sai: %+v", s)
			}
		}
	}
	if n != 1 {
		t.Fatalf("muốn đúng một thông báo SEV-2, có %d", n)
	}
}

// Ánh xạ theo tên cảnh báo khi luật không mang nhãn degrade_level; cảnh báo không liên quan bị bỏ qua.
func TestAnhXaTenCanhBao(t *testing.T) {
	h := newHarness(t)
	h.ctl.opt.AlertLevels = map[string]int{"MissionCoreSaturated": 1}
	body := `{"alerts":[{"status":"firing","fingerprint":"a","labels":{"alertname":"MissionCoreSaturated"}},` +
		`{"status":"firing","fingerprint":"b","labels":{"alertname":"DLQNotEmpty"}}]}`
	if _, st := h.post("/alertmanager", body, token); st["level"].(float64) != 1 {
		t.Fatalf("muốn mức 1: %v", st)
	}
}

func TestDocMucDangGhi(t *testing.T) {
	h := newHarness(t)
	h.post("/level", `{"level":2}`, token)
	if lv, ok := h.gw.CurrentLevel(context.Background()); !ok || lv != 2 {
		t.Fatalf("CurrentLevel = %d %v", lv, ok)
	}
}
