package provision

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestRetryable(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{errors.New("mạng"), true},
		{&HTTPError{Status: 503}, true},
		{&HTTPError{Status: 429}, true},
		{&HTTPError{Status: 400}, false},
		{&HTTPError{Status: 403}, false},
		{Permanent("x", nil), false},
		{Transient("x", &HTTPError{Status: 502}), true},
		{&PackNotReadyError{Pack: "a"}, true}, // bước gói tự bọc thành Permanent
	}
	for i, c := range cases {
		if got := retryable(c.err); got != c.want {
			t.Errorf("ca %d: %v → %v, muốn %v", i, c.err, got, c.want)
		}
	}
}

func TestTemplateResolver(t *testing.T) {
	r := TemplateResolver{KeycloakURL: "https://sso.{cell}.okr.viettel.vn/", CoreURL: "http://mission-core.{cell}.svc:8080", OrgURL: "http://org.{cell}.svc"}
	ep, err := r.Endpoints("CELL-G2", "https://api.cell-g2.okrkpi.vn/api/v1")
	if err != nil {
		t.Fatal(err)
	}
	if ep.KeycloakURL != "https://sso.cell-g2.okr.viettel.vn" || ep.Realm != "mission" ||
		ep.TokenURL != "https://sso.cell-g2.okr.viettel.vn/realms/mission/protocol/openid-connect/token" ||
		ep.CoreURL != "http://mission-core.cell-g2.svc:8080" || ep.EdgeHost != "api.cell-g2.okrkpi.vn" {
		t.Fatalf("%+v", ep)
	}
	if _, err := r.Endpoints("CELL-X", "::"); err == nil {
		t.Fatal("URL cell sai phải lỗi")
	}
}

func TestAPIRateLimitAndNames(t *testing.T) {
	if APIRateLimit(500) != 20 || APIRateLimit(30000) != 600 || APIRateLimit(1001) != 21 {
		t.Fatal("hạn mức API sai (TL-01: 2 rps mỗi 100 tài khoản, tối thiểu 20)")
	}
	if f, l := splitName("Trần Thị Hồng"); f != "Hồng" || l != "Trần Thị" {
		t.Fatalf("%q %q", f, l)
	}
	if KeycloakAlias("thai_nguyen", "vneid") != "thai-nguyen-vneid" || CertName("thai_nguyen") != "tenant-thai-nguyen-tls" {
		t.Fatal("tên sinh sai")
	}
	if reasonOf(context.DeadlineExceeded) == "" || reasonOf(errors.New("chi tiết nội bộ")) != "Hệ thống liên quan chưa sẵn sàng" {
		t.Fatal("lý do hiển thị không được lộ chi tiết kỹ thuật")
	}
}

func TestPowerDNSOutsideZone(t *testing.T) {
	p := &PowerDNS{Zone: "okr.viettel.vn", Resolver: fakeResolver{"okr.tinh.gov.vn": "api.cell-g1.okrkpi.vn."}}
	if p.managed("okr.tinh.gov.vn") || !p.managed("thainguyen.okr.viettel.vn") {
		t.Fatal("phân biệt vùng quản lý sai")
	}
	ctx := context.Background()
	if err := p.EnsureCNAME(ctx, "okr.tinh.gov.vn", "api.cell-g1.okrkpi.vn"); err != nil {
		t.Fatalf("tên miền riêng đã trỏ đúng: %v", err)
	}
	var se *StepError
	if err := p.EnsureCNAME(ctx, "okr.tinh.gov.vn", "api.cell-g2.okrkpi.vn"); !errors.As(err, &se) || se.Permanent {
		t.Fatalf("trỏ sai phải là lỗi tạm thời (khách hàng sửa DNS): %v", err)
	}
	if err := p.RemoveCNAME(ctx, "okr.tinh.gov.vn"); err != nil {
		t.Fatal(err)
	}
}

type fakeResolver map[string]string

func (f fakeResolver) LookupCNAME(_ context.Context, h string) (string, error) {
	if v, ok := f[h]; ok {
		return v, nil
	}
	return "", errors.New("không có")
}

func TestClientCredentialsCache(t *testing.T) {
	n := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n++
		if r.FormValue("client_secret") != "s" {
			w.WriteHeader(401)
			_, _ = w.Write([]byte(`{"error":"invalid_client","secret":"không được lộ"}`))
			return
		}
		_, _ = w.Write([]byte(`{"access_token":"a","expires_in":300}`))
	}))
	defer srv.Close()
	c := &ClientCredentials{ClientID: "x", ClientSecret: "s", HTTP: &http.Client{Timeout: time.Second}}
	for range 3 {
		if tok, err := c.Token(context.Background(), srv.URL); err != nil || tok != "a" {
			t.Fatal(tok, err)
		}
	}
	if n != 1 {
		t.Fatalf("phải đệm token: %d lần gọi", n)
	}
	bad := &ClientCredentials{ClientID: "x", ClientSecret: "sai"}
	_, err := bad.Token(context.Background(), srv.URL)
	var he *HTTPError
	if !errors.As(err, &he) || he.Status != 401 || he.Body != "" {
		t.Fatalf("lỗi cấp token không được mang thân phản hồi: %v", err)
	}
}
