package site

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParse(t *testing.T) {
	for in, want := range map[string]Role{"": Primary, "primary": Primary, " Standby ": Standby} {
		if got, err := Parse(in); err != nil || got != want {
			t.Fatalf("Parse(%q) = %v, %v", in, got, err)
		}
	}
	if _, err := Parse("hl"); err == nil {
		t.Fatal("giá trị lạ phải lỗi")
	}
}

func TestFromEnvVaActive(t *testing.T) {
	t.Setenv("VT_SITE_ROLE", "standby")
	t.Setenv("VT_SITE", "sn")
	i, err := FromEnv()
	if err != nil || !i.Standby() || i.Site != "sn" {
		t.Fatalf("FromEnv = %+v, %v", i, err)
	}
	if i.Active(nil, "kafka-consumer") {
		t.Fatal("TTDL chờ không được chạy consumer")
	}
	if !(Info{Role: Primary}).Active(nil, "kafka-consumer") {
		t.Fatal("primary phải chạy consumer")
	}
}

func TestHandler(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusTeapot) })
	h := Info{Role: Standby, Site: "sn"}.Wrap(next)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, Path, nil))
	var got struct {
		Status, Role, Site string
		Paused             []string
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil || rec.Code != 200 {
		t.Fatalf("health: %d %s", rec.Code, rec.Body)
	}
	if got.Status != "UP" || got.Role != "standby" || got.Site != "sn" || len(got.Paused) != 3 {
		t.Fatalf("health = %+v", got)
	}

	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/x", nil))
	if rec.Code != http.StatusTeapot {
		t.Fatalf("đường dẫn khác phải chuyển cho next, nhận %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	Info{Role: Primary}.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, Path, nil))
	if want := `{"status":"UP","role":"primary","site":"","paused":[]}`; rec.Body.String() != want {
		t.Fatalf("primary = %s", rec.Body)
	}
}
