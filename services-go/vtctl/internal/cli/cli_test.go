package cli_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"vn.viettel.okrkpi/services-go/vtctl/internal/audit"
	"vn.viettel.okrkpi/services-go/vtctl/internal/cli"
)

// jwt dựng token không ký (vtctl chỉ đọc claim; máy chủ mới kiểm chữ ký).
func jwt(claims map[string]any) string {
	h := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none"}`))
	b, _ := json.Marshal(claims)
	return h + "." + base64.RawURLEncoding.EncodeToString(b) + ".x"
}

// oidc là Keycloak vận hành giả: luồng thiết bị trả token ngay ở lần hỏi đầu.
type oidc struct {
	srv      *httptest.Server
	mu       sync.Mutex
	user     string
	acrNext  string
	deviceQ  []string // tham số acr_values của mỗi lần bắt đầu đăng nhập
	authTime time.Time
}

func newOIDC(t *testing.T, user string) *oidc {
	o := &oidc{user: user, acrNext: "aal1", authTime: time.Now()}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /.well-known/openid-configuration", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{"device_authorization_endpoint": o.srv.URL + "/device", "token_endpoint": o.srv.URL + "/token"})
	})
	mux.HandleFunc("POST /device", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		o.mu.Lock()
		o.deviceQ = append(o.deviceQ, r.Form.Get("acr_values"))
		if r.Form.Get("acr_values") == "aal2" {
			o.acrNext = "aal2"
		}
		o.mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{"device_code": "dc", "user_code": "ABCD-EFGH", "verification_uri": o.srv.URL + "/v", "expires_in": 60, "interval": 0})
	})
	mux.HandleFunc("POST /token", func(w http.ResponseWriter, r *http.Request) {
		o.mu.Lock()
		defer o.mu.Unlock()
		tok := jwt(map[string]any{"sub": "sub-" + o.user, "preferred_username": o.user, "acr": o.acrNext,
			"auth_time": o.authTime.Unix(), "exp": time.Now().Add(time.Hour).Unix(), "realm_access": map[string]any{"roles": []string{"platform_admin"}}})
		_ = json.NewEncoder(w).Encode(map[string]any{"access_token": tok, "refresh_token": "rt", "expires_in": 3600})
	})
	o.srv = httptest.NewServer(mux)
	t.Cleanup(o.srv.Close)
	return o
}

// call là một lời gọi API ghi nhận được.
type call struct {
	Method, Path, IfMatch, Key, ACR string
	Body                            map[string]any
}

type apiSrv struct {
	srv   *httptest.Server
	mu    sync.Mutex
	calls []call
	// stepUpOnce: lần POST đầu với token chưa aal2 mới trả 401 insufficient_user_authentication.
	h func(w http.ResponseWriter, r *http.Request, c call)
}

func newAPI(t *testing.T, h func(w http.ResponseWriter, r *http.Request, c call)) *apiSrv {
	s := &apiSrv{h: h}
	s.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c := call{Method: r.Method, Path: r.URL.RequestURI(), IfMatch: r.Header.Get("If-Match"), Key: r.Header.Get("Idempotency-Key")}
		if parts := strings.Split(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), "."); len(parts) == 3 {
			raw, _ := base64.RawURLEncoding.DecodeString(parts[1])
			var cl map[string]any
			_ = json.Unmarshal(raw, &cl)
			c.ACR, _ = cl["acr"].(string)
		}
		if r.Body != nil {
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &c.Body)
		}
		s.mu.Lock()
		s.calls = append(s.calls, c)
		s.mu.Unlock()
		s.h(w, r, c)
	}))
	t.Cleanup(s.srv.Close)
	return s
}

func (s *apiSrv) writes() []call {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []call
	for _, c := range s.calls {
		if c.Method != http.MethodGet {
			out = append(out, c)
		}
	}
	return out
}

func problem(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"status": status, "title": http.StatusText(status), "code": code})
}

// fakeRunner thay kubectl, helm: ghi lại lệnh, trả kết quả theo hàm đáp.
type fakeRunner struct {
	mu    sync.Mutex
	runs  []string
	stdin [][]byte
	reply func(cmd string) (string, error)
}

func (f *fakeRunner) Run(_ context.Context, stdin []byte, name string, args ...string) ([]byte, error) {
	cmd := name + " " + strings.Join(args, " ")
	f.mu.Lock()
	f.runs = append(f.runs, cmd)
	f.stdin = append(f.stdin, stdin)
	f.mu.Unlock()
	if f.reply == nil {
		return nil, nil
	}
	out, err := f.reply(cmd)
	return []byte(out), err
}

func (f *fakeRunner) has(sub string) bool {
	for _, r := range f.runs {
		if strings.Contains(r, sub) {
			return true
		}
	}
	return false
}

type env struct {
	t        *testing.T
	dir, cfg string
	oidc     *oidc
	reg      *apiSrv
	cellAPI  *apiSrv
	degrade  *apiSrv
	run      *fakeRunner
}

func repoRoot(t *testing.T) string {
	d, _ := os.Getwd()
	for !strings.HasSuffix(d, "/services-go/vtctl") {
		d = filepath.Dir(d)
		if d == "/" {
			t.Fatal("không thấy gốc module")
		}
	}
	return filepath.Dir(filepath.Dir(d))
}

func newEnv(t *testing.T, reg, cellAPI, degrade func(w http.ResponseWriter, r *http.Request, c call)) *env {
	e := &env{t: t, dir: t.TempDir(), oidc: newOIDC(t, "ops.truc-ca-1"), run: &fakeRunner{}}
	ok := func(w http.ResponseWriter, r *http.Request, c call) { w.WriteHeader(404) }
	if reg == nil {
		reg = ok
	}
	if cellAPI == nil {
		cellAPI = ok
	}
	if degrade == nil {
		degrade = ok
	}
	e.reg, e.cellAPI, e.degrade = newAPI(t, reg), newAPI(t, cellAPI), newAPI(t, degrade)
	root := repoRoot(t)
	e.cfg = filepath.Join(e.dir, "config.yaml")
	cfg := fmt.Sprintf(`cellsDir: %s/deploy/cells
chartsDir: %s/deploy/charts
auditLog: audit.jsonl
tokenDir: tokens
environments:
  staging:
    issuer: %s
    clientId: vtctl
    registryURL: %s
    cells:
      hn:
        apiURL: %s
        kubeContext: staging-hn
        namespaces: {jobs: okr-ops, org: okr-org}
        degradeURL: %s
        kafka: {bootstrap: ["127.0.0.1:1"], plaintext: true}
      nb:
        kubeContext: staging-nb
        kafka: {bootstrap: ["127.0.0.1:1"], plaintext: true}
`, root, root, e.oidc.srv.URL, e.reg.srv.URL, e.cellAPI.srv.URL, e.degrade.srv.URL)
	if err := os.WriteFile(e.cfg, []byte(cfg), 0o600); err != nil {
		t.Fatal(err)
	}
	return e
}

func (e *env) vtctl(args ...string) (int, string, string) {
	var out, errb bytes.Buffer
	a := &cli.App{Out: &out, Err: &errb, Runner: e.run, Interactive: true}
	code := cli.Execute(context.Background(), a, append([]string{"--config", e.cfg, "--env", "staging"}, args...))
	return code, out.String(), errb.String()
}

func (e *env) audits() []audit.Record {
	rs, err := audit.Read(filepath.Join(e.dir, "audit.jsonl"))
	if err != nil {
		e.t.Fatal(err)
	}
	return rs
}

const (
	tenantID = "0192f1a2-3b4c-7d5e-8f60-718293a4b5c6"
	cellID   = "0192f1a2-0000-7c00-8a00-00000000c001"
	moveID   = "0192f1b0-11aa-7b22-9c33-44d55e66f701"
	periodID = "0192f300-0000-7000-8000-0000000000e1"
)

// registryFake: GET tenant trả ETag "5"; activate trả 200 lần đầu khi token aal2, ngược lại 401 bước lên.
func registryFake(w http.ResponseWriter, r *http.Request, c call) {
	switch {
	case r.Method == "GET" && (c.Path == "/api/v1/registry/tenants/"+tenantID || c.Path == "/api/v1/config/tenants/"+tenantID):
		w.Header().Set("ETag", `"5"`)
		_ = json.NewEncoder(w).Encode(map[string]any{"id": tenantID, "code": "dukcqtw", "status": "TAM_NGUNG", "rowVersion": 5})
	case r.Method == "POST" && c.ACR != "aal2":
		w.Header().Set("WWW-Authenticate", `Bearer error="insufficient_user_authentication", acr_values="aal2", max_age="300"`)
		problem(w, 401, "COMMON-401-STEP-UP")
	case r.Method == "POST" && c.Path == "/api/v1/config/tenants/"+tenantID+"/activate":
		if c.IfMatch != `"5"` {
			problem(w, 428, "COMMON-428-PRECONDITION-REQUIRED")
			return
		}
		w.Header().Set("ETag", `"6"`)
		_ = json.NewEncoder(w).Encode(map[string]any{"id": tenantID, "status": "DANG_VAN_HANH"})
	case r.Method == "GET" && c.Path == "/api/v1/registry/cells?limit=200":
		_ = json.NewEncoder(w).Encode(map[string]any{"items": []map[string]any{{"id": cellID, "code": "CELL-NB", "status": "HOAT_DONG", "apiBaseUrl": "http://cell-nb.invalid"}}})
	case r.Method == "POST" && c.Path == "/api/v1/registry/tenants/"+tenantID+"/move":
		w.Header().Set("Location", "/api/v1/registry/tenants/"+tenantID+"/move/"+moveID)
		w.WriteHeader(201)
		_ = json.NewEncoder(w).Encode(map[string]any{"id": moveID, "status": "CHO_DUYET", "rowVersion": 0})
	case r.Method == "GET" && c.Path == "/api/v1/registry/tenants/"+tenantID+"/move/"+moveID:
		w.Header().Set("ETag", `"0"`)
		_ = json.NewEncoder(w).Encode(map[string]any{"id": moveID, "status": "CHO_DUYET", "requestedBy": "sub-ops.truc-ca-1"})
	case r.Method == "POST" && c.Path == "/api/v1/registry/tenants/"+tenantID+"/move/"+moveID+"/approve":
		problem(w, 403, "REGISTRY-403-SAME-APPROVER")
	default:
		w.WriteHeader(404)
	}
}

func TestLenhGhiKhongConfirmKhongThayDoi(t *testing.T) {
	e := newEnv(t, registryFake, nil, nil)
	code, out, errs := e.vtctl("tenant", "activate", tenantID, "--reason", "QĐ-12/2026 gia hạn hợp đồng")
	if code != 0 || !strings.Contains(out, "Chạy thử: chưa thay đổi gì") || !strings.Contains(out, "TAM_NGUNG") {
		t.Fatalf("mã %d\n%s%s", code, out, errs)
	}
	if w := e.reg.writes(); len(w) != 0 {
		t.Fatalf("chạy thử đã gửi lệnh ghi: %+v", w)
	}
	if n := len(e.audits()); n != 0 {
		t.Fatalf("chạy thử không ghi kiểm toán, có %d", n)
	}
	// --dry-run gõ tường minh thắng --confirm.
	if code, out, _ := e.vtctl("tenant", "activate", tenantID, "--reason", "QĐ-12/2026", "--confirm", "--dry-run"); code != 0 || !strings.Contains(out, "Chạy thử") {
		t.Fatalf("--dry-run phải thắng: %d %s", code, out)
	}
	if len(e.reg.writes()) != 0 || len(e.audits()) != 0 {
		t.Fatal("--confirm --dry-run vẫn không được ghi")
	}
}

func TestKichHoatLaiTenantBuocLenVaKiemToan(t *testing.T) {
	e := newEnv(t, registryFake, nil, nil)
	code, out, errs := e.vtctl("tenant", "activate", tenantID, "--reason", "QĐ-12/2026 gia hạn hợp đồng", "--confirm")
	if code != 0 || !strings.Contains(out, "Đã thực hiện") || !strings.Contains(out, "DANG_VAN_HANH") {
		t.Fatalf("mã %d\n%s%s", code, out, errs)
	}
	w := e.reg.writes()
	if len(w) != 1 || w[0].IfMatch != `"5"` || w[0].Key == "" || w[0].ACR != "aal2" || w[0].Body["reason"] != "QĐ-12/2026 gia hạn hợp đồng" {
		t.Fatalf("lời gọi ghi: %+v", w)
	}
	if !strings.Contains(errs, "Xác thực bước lên (MFA)") || e.oidc.deviceQ[len(e.oidc.deviceQ)-1] != "aal2" {
		t.Fatalf("phải xin bước lên aal2: %q %v", errs, e.oidc.deviceQ)
	}
	rs := e.audits()
	if len(rs) != 1 || rs[0].Actor != "ops.truc-ca-1" || rs[0].Command != "tenant activate" || rs[0].Outcome != audit.OutcomeOK ||
		rs[0].IdempotencyKey != w[0].Key || rs[0].Args["arg1"] != tenantID || rs[0].Env != "staging" {
		t.Fatalf("kiểm toán: %+v", rs)
	}
	// Token lưu ở tệp quyền 0600.
	st, err := os.Stat(filepath.Join(e.dir, "tokens", "staging.json"))
	if err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("tệp token: %v %v", st, err)
	}
}

func TestBuocLenKhiMayChuDoi(t *testing.T) {
	// Token đã aal2 nhưng máy chủ vẫn đòi bước lên (auth_time quá cũ theo máy chủ): xin lại một lần, gửi lại cùng khoá.
	var n int
	var mu sync.Mutex
	reg := func(w http.ResponseWriter, r *http.Request, c call) {
		if r.Method == "POST" {
			mu.Lock()
			n++
			first := n == 1
			mu.Unlock()
			if first {
				w.Header().Set("WWW-Authenticate", `Bearer error="insufficient_user_authentication"`)
				problem(w, 401, "COMMON-401-STEP-UP")
				return
			}
		}
		registryFake(w, r, c)
	}
	e := newEnv(t, reg, nil, nil)
	e.oidc.acrNext = "aal2"
	if code, out, errs := e.vtctl("tenant", "activate", tenantID, "--reason", "QĐ-12/2026", "--confirm"); code != 0 {
		t.Fatalf("mã %d\n%s%s", code, out, errs)
	}
	w := e.reg.writes()
	if len(w) != 2 || w[0].Key != w[1].Key {
		t.Fatalf("phải gửi lại đúng một lần với cùng Idempotency-Key: %+v", w)
	}
	if len(e.audits()) != 1 {
		t.Fatal("một lệnh, một bản ghi kiểm toán")
	}
}

func TestChuyenCellDeNghiVaDuyetBonMat(t *testing.T) {
	e := newEnv(t, registryFake, nil, nil)
	code, out, errs := e.vtctl("cell", "move", "request", tenantID, "--target-cell", cellID, "--reason", "Vượt 50.000 tài khoản, ADR-29",
		"--planned-at", "2026-11-07T22:00:00+07:00", "--confirm")
	if code != 0 || !strings.Contains(out, "CHO_DUYET") {
		t.Fatalf("đề nghị: %d\n%s%s", code, out, errs)
	}
	w := e.reg.writes()
	if w[len(w)-1].IfMatch != `"5"` || w[len(w)-1].Body["targetCellId"] != cellID || w[len(w)-1].Body["plannedAt"] != "2026-11-07T22:00:00+07:00" {
		t.Fatalf("đề nghị gửi sai: %+v", w[len(w)-1])
	}
	// Cùng người duyệt: máy chủ từ chối (bốn mắt ở máy chủ), vtctl báo lỗi và vẫn ghi kiểm toán THAT_BAI.
	code, _, errs = e.vtctl("cell", "move", "approve", tenantID, moveID, "--confirm")
	if code != 1 || !strings.Contains(errs, "REGISTRY-403-SAME-APPROVER") {
		t.Fatalf("duyệt: %d %s", code, errs)
	}
	rs := e.audits()
	if len(rs) != 2 || rs[1].Outcome != audit.OutcomeFailed || rs[1].Command != "cell move approve" || !strings.Contains(rs[1].Error, "SAME-APPROVER") {
		t.Fatalf("kiểm toán: %+v", rs)
	}
	if last := e.reg.writes(); last[len(last)-1].IfMatch != `"0"` {
		t.Fatalf("duyệt phải gửi If-Match của lệnh chuyển: %+v", last[len(last)-1])
	}
}

func TestKhoaKyQuaCellApiTuRegistry(t *testing.T) {
	var lockCalls []call
	var mu sync.Mutex
	cell := func(w http.ResponseWriter, r *http.Request, c call) {
		mu.Lock()
		lockCalls = append(lockCalls, c)
		mu.Unlock()
		switch {
		case r.Method == "GET" && c.Path == "/api/v1/periods/"+periodID+"/lock":
			w.Header().Set("ETag", `"9"`)
			_ = json.NewEncoder(w).Encode(map[string]any{"periodId": periodID, "sagaStatus": "DANG_CHAY", "pauseRequested": false,
				"totals": map[string]any{"profiles": 100, "scored": 40, "failed": 2}})
		case r.Method == "POST" && c.Path == "/api/v1/periods/"+periodID+"/lock/pause" && c.IfMatch == `"9"` && c.ACR == "aal2":
			_ = json.NewEncoder(w).Encode(map[string]any{"periodId": periodID, "sagaStatus": "DANG_CHAY", "pauseRequested": true})
		case r.Method == "POST" && c.Path == "/api/v1/periods/"+periodID+"/lock/retry-failed" && c.IfMatch == "" && c.Key != "":
			w.Header().Set("Location", "/api/v1/jobs/0192")
			w.WriteHeader(202)
		default:
			problem(w, 400, "TEST-400")
		}
	}
	e := newEnv(t, registryFake, cell, nil)
	if code, out, errs := e.vtctl("--cell", "hn", "period-lock", "status", periodID); code != 0 || !strings.Contains(out, "DANG_CHAY") {
		t.Fatalf("status: %d %s %s", code, out, errs)
	}
	if code, out, errs := e.vtctl("--cell", "hn", "period-lock", "pause", periodID, "--comment", "Chờ sửa Policy Pack", "--confirm"); code != 0 || !strings.Contains(out, "pauseRequested: true") {
		t.Fatalf("pause: %d %s %s", code, out, errs)
	}
	key := "0192f400-0000-7000-8000-000000000001"
	if code, out, errs := e.vtctl("--cell", "hn", "period-lock", "retry-failed", periodID, "--profile", tenantID, "--idempotency-key", key, "--confirm"); code != 0 || !strings.Contains(out, "location: /api/v1/jobs/0192") {
		t.Fatalf("retry: %d %s %s", code, out, errs)
	}
	last := lockCalls[len(lockCalls)-1]
	if last.Key != key || fmt.Sprint(last.Body["profileIds"]) != "["+tenantID+"]" {
		t.Fatalf("retry gửi sai: %+v", last)
	}
	if n := len(e.audits()); n != 2 {
		t.Fatalf("hai lệnh ghi, %d bản ghi kiểm toán", n)
	}
	// Cell nb không có apiURL: lấy apiBaseUrl từ registry theo displayName CELL-NB của deploy/cells/nb/cell.yaml.
	_, _, errs := e.vtctl("--cell", "nb", "period-lock", "status", periodID)
	if !strings.Contains(errs, "cell-nb.invalid") {
		t.Fatalf("phải gọi apiBaseUrl của CELL-NB: %s", errs)
	}
}

func TestCellKhongCoTrongDeployCells(t *testing.T) {
	e := newEnv(t, registryFake, nil, nil)
	code, _, errs := e.vtctl("--cell", "khong-co", "period-lock", "status", periodID)
	if code == 0 || !strings.Contains(errs, "không có trong") {
		t.Fatalf("%d %s", code, errs)
	}
}

func TestKhoiDongLaiConsumerCanQuyen(t *testing.T) {
	e := newEnv(t, nil, nil, nil)
	allowed := false
	e.run.reply = func(cmd string) (string, error) {
		switch {
		case strings.Contains(cmd, "auth can-i patch deployments"):
			if allowed {
				return "yes", nil
			}
			return "no", fmt.Errorf("exit status 1")
		case strings.Contains(cmd, "get deployment/scoring-consumer"):
			return "3/3", nil
		}
		return "", nil
	}
	code, _, errs := e.vtctl("--cell", "hn", "consumer", "restart", "--deployment", "scoring-consumer", "-n", "okr-scoring", "--reason", "INC-2026-1023 lag", "--confirm")
	if code != 1 || !strings.Contains(errs, "PAM") || e.run.has("rollout restart") {
		t.Fatalf("thiếu quyền phải dừng: %d %s %v", code, errs, e.run.runs)
	}
	allowed = true
	code, out, _ := e.vtctl("--cell", "hn", "consumer", "restart", "--deployment", "scoring-consumer", "-n", "okr-scoring", "--reason", "INC-2026-1023 lag")
	if code != 0 || !strings.Contains(out, "3/3") || e.run.has("rollout restart") {
		t.Fatalf("chạy thử: %d %s %v", code, out, e.run.runs)
	}
	code, out, errs = e.vtctl("--cell", "hn", "consumer", "restart", "--deployment", "scoring-consumer", "-n", "okr-scoring", "--reason", "INC-2026-1023 lag", "--confirm")
	if code != 0 || !e.run.has("kubectl --context staging-hn -n okr-scoring rollout restart deployment/scoring-consumer") ||
		!e.run.has("rollout status deployment/scoring-consumer --timeout=5m0s") {
		t.Fatalf("restart: %d %s %s %v", code, out, errs, e.run.runs)
	}
	if rs := e.audits(); len(rs) != 1 || rs[0].Command != "consumer restart" || rs[0].Cell != "hn" {
		t.Fatalf("kiểm toán: %+v", rs)
	}
}

func TestPhatLaiOutboxQuaJob(t *testing.T) {
	e := newEnv(t, nil, nil, nil)
	e.run.reply = func(cmd string) (string, error) {
		switch {
		case strings.Contains(cmd, "auth can-i create jobs"):
			return "yes", nil
		case strings.HasPrefix(cmd, "helm template"):
			return "kind: Job\n", nil
		case strings.Contains(cmd, "create -f -"):
			return "job.batch/outbox-replay-x7k2p", nil
		}
		return "", nil
	}
	args := []string{"--cell", "hn", "outbox", "replay", "--schema", "task", "--tenant", tenantID, "--from", "2026-10-20T08:00:00Z",
		"--to", "2026-10-20T10:30:00Z", "--consumer", "scoring-service", "--reason", "INC-2026-1020 mất slot, phiếu 17"}
	code, out, errs := e.vtctl(args...)
	if code != 0 || !e.run.has("--dry-run=server") || !e.run.has("--set outboxReplay.confirm=false") || len(e.audits()) != 0 {
		t.Fatalf("chạy thử: %d %s %s %v", code, out, errs, e.run.runs)
	}
	if !e.run.has(`--set-string reason=INC-2026-1020 mất slot\, phiếu 17`) || !e.run.has("-n okr-ops -s templates/outbox-replay-job.yaml") ||
		!e.run.has("--set-string operator=ops.truc-ca-1") || !e.run.has("deploy/charts/vtctl-jobs") {
		t.Fatalf("tham số helm: %v", e.run.runs)
	}
	e.run.runs = nil
	code, out, errs = e.vtctl(append(args, "--confirm")...)
	if code != 0 || e.run.has("--dry-run=server") || !e.run.has("--set outboxReplay.confirm=true") || !strings.Contains(out, "outbox-replay-x7k2p") {
		t.Fatalf("chạy thật: %d %s %s %v", code, out, errs, e.run.runs)
	}
	if rs := e.audits(); len(rs) != 1 || rs[0].Result["job"] != "job.batch/outbox-replay-x7k2p" {
		t.Fatalf("kiểm toán: %+v", rs)
	}
}

func TestPhatLaiToChucQuaJob(t *testing.T) {
	e := newEnv(t, nil, nil, nil)
	e.run.reply = func(cmd string) (string, error) {
		switch {
		case strings.Contains(cmd, "auth can-i"):
			return "yes", nil
		case strings.Contains(cmd, "create -f -"):
			return "job.batch/org-service-republish-abcde", nil
		}
		return "kind: Job\n", nil
	}
	code, out, errs := e.vtctl("--cell", "hn", "org", "republish", "--tenant", tenantID, "--entity", "person", "--bucket", "17", "--bucket", "203",
		"--reason", "INC-2026-1022 lệch bản sao", "--confirm")
	if code != 0 || !e.run.has("--set republish.dryRun=false") || !e.run.has("--set republish.entities={person}") ||
		!e.run.has("--set republish.buckets={17,203}") || !e.run.has("-n okr-org -s templates/republish-job.yaml") {
		t.Fatalf("%d %s %s %v", code, out, errs, e.run.runs)
	}
	if code, _, _ := e.vtctl("--cell", "hn", "org", "republish", "--tenant", tenantID, "--bucket", "300", "--reason", "INC-1"); code != 2 {
		t.Fatal("bucket ngoài 0..255 phải là lỗi tham số")
	}
}

func TestMucSuyGiam(t *testing.T) {
	var got []call
	var mu sync.Mutex
	var auth []string
	deg := func(w http.ResponseWriter, r *http.Request, c call) {
		mu.Lock()
		got = append(got, c)
		auth = append(auth, r.Header.Get("Authorization"))
		mu.Unlock()
		if r.Method == "GET" {
			_ = json.NewEncoder(w).Encode(map[string]any{"level": 0, "manual": nil})
			return
		}
		if r.Header.Get("Authorization") != "Bearer tok-webhook" {
			w.WriteHeader(401)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"level": c.Body["level"]})
	}
	e := newEnv(t, nil, nil, deg)
	e.run.reply = func(cmd string) (string, error) {
		switch {
		case strings.Contains(cmd, "auth can-i get secrets/apisix-degrade-webhook"):
			return "yes", nil
		case strings.Contains(cmd, "get secret apisix-degrade-webhook"):
			return base64.StdEncoding.EncodeToString([]byte("tok-webhook")), nil
		}
		return "", nil
	}
	if code, _, _ := e.vtctl("--cell", "hn", "degrade", "set", "4", "--reason", "thử sai"); code != 2 {
		t.Fatal("mức 4 phải bị từ chối")
	}
	code, out, errs := e.vtctl("--cell", "hn", "degrade", "set", "3", "--reason", "CPU primary 95% từ 09:10", "--confirm")
	if code != 0 || !strings.Contains(out, "level: 3") {
		t.Fatalf("%d %s %s", code, out, errs)
	}
	post := got[len(got)-1]
	if post.Method != "POST" || post.Body["level"] != float64(3) || !strings.Contains(post.Body["reason"].(string), "ops.truc-ca-1") {
		t.Fatalf("gọi bộ điều khiển: %+v", post)
	}
	if strings.Contains(out+errs, "tok-webhook") {
		t.Fatal("không được in token webhook")
	}
	if code, _, errs := e.vtctl("--cell", "hn", "degrade", "clear", "--reason", "CPU primary về 60%", "--confirm"); code != 0 {
		t.Fatalf("clear: %s", errs)
	}
	if got[len(got)-1].Body["level"] != nil {
		t.Fatalf("clear phải gửi level null: %+v", got[len(got)-1])
	}
	rs := e.audits()
	if len(rs) != 2 || rs[0].Command != "degrade set" || rs[1].Command != "degrade clear" {
		t.Fatalf("kiểm toán: %+v", rs)
	}
}

func TestKhongDangNhapKhiKhongTuongTac(t *testing.T) {
	e := newEnv(t, registryFake, nil, nil)
	var out, errb bytes.Buffer
	a := &cli.App{Out: &out, Err: &errb, Runner: e.run, Interactive: false}
	code := cli.Execute(context.Background(), a, []string{"--config", e.cfg, "--env", "staging", "tenant", "status", tenantID})
	if code != 1 || !strings.Contains(errb.String(), "vtctl login") {
		t.Fatalf("%d %s", code, errb.String())
	}
}

func TestThieuThamSo(t *testing.T) {
	e := newEnv(t, nil, nil, nil)
	for _, args := range [][]string{
		{"tenant", "activate", "khong-phai-uuid", "--reason", "abcdef"},
		{"tenant", "activate", tenantID},
		{"--cell", "hn", "dlq", "replay", "--topic", "t.dlq.c", "--consumer", "c", "--from", "0", "--to", "1", "--reason", "abcdef"},
		{"--cell", "hn", "dlq", "skip", "--topic", "t", "--consumer", "c", "--partition", "0", "--to", "1", "--reason", "abcdef"},
	} {
		if code, _, errs := e.vtctl(args...); code != 2 {
			t.Fatalf("%v: mã %d %s", args, code, errs)
		}
	}
}
