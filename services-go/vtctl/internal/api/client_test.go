package api

import (
	"encoding/json"
	"strings"
	"testing"
)

// Lỗi 422 kiểm tra tổng thể phải in từng mục chưa đạt để người trực biết việc cần làm (INF-26).
func TestProblemErrorInMucChuaDat(t *testing.T) {
	var p Problem
	body := `{"status":422,"title":"Chưa đạt kiểm tra tổng thể","code":"CONFIG-422-READINESS-FAILED",
		"errors":[{"field":"DOMAIN","message":"Chưa khai báo tên miền cho tenant"},{"field":"IDP","message":"Chưa cấu hình IdP"}]}`
	if err := json.Unmarshal([]byte(body), &p); err != nil {
		t.Fatal(err)
	}
	got := p.Error()
	for _, w := range []string{"HTTP 422", "CONFIG-422-READINESS-FAILED", "\n  - DOMAIN: Chưa khai báo tên miền cho tenant", "\n  - IDP: Chưa cấu hình IdP"} {
		if !strings.Contains(got, w) {
			t.Errorf("thiếu %q trong %q", w, got)
		}
	}
}
