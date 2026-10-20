package contract

import (
	"path/filepath"
	"runtime"
	"testing"
)

func spec(t *testing.T) *Spec {
	t.Helper()
	_, f, _, _ := runtime.Caller(0)
	s, err := Load(filepath.Join(filepath.Dir(f), "..", "..", "..", "..", "contracts", "openapi"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestValidateCatchesDeviations(t *testing.T) {
	s := spec(t)
	ok := `{"tenantId":"0192f1a2-3b4c-7d5e-8f60-718293a4b5c6","tenantCode":"dukcqtw","status":"DANG_VAN_HANH","tenancyModel":"BRIDGE","cell":{"id":"0192f1a2-0000-7c00-8a00-00000000c001","code":"CELL-HN","apiBaseUrl":"https://api.cell-hn.okrkpi.vn"},"cacheTtlSeconds":300}`
	if err := s.Validate("modules/platform.yaml", "/registry/resolve", "get", 200, "application/json", []byte(ok)); err != nil {
		t.Fatalf("phản hồi đúng bị từ chối: %v", err)
	}
	bad := `{"tenantId":"x","tenantCode":"DUK","status":"VO_LY","tenancyModel":"BRIDGE","cell":{"id":"0192f1a2-0000-7c00-8a00-00000000c001","code":"CELL-HN"},"cacheTtlSeconds":300}`
	if err := s.Validate("modules/platform.yaml", "/registry/resolve", "get", 200, "application/json", []byte(bad)); err == nil {
		t.Fatal("phản hồi sai lược đồ phải bị bắt")
	}
	if err := s.Validate("modules/platform.yaml", "/registry/nope", "get", 200, "application/json", []byte(ok)); err == nil {
		t.Fatal("đường dẫn ngoài hợp đồng phải bị bắt")
	}
	prob := `{"type":"https://okrkpi.vn/problems/tenant-moved","title":"x","status":421,"code":"REGISTRY-421-TENANT-MOVED"}`
	if err := s.Validate("modules/platform.yaml", "/registry/resolve", "get", 421, "application/problem+json", []byte(prob)); err != nil {
		t.Fatal(err)
	}
	if err := s.Validate("modules/platform.yaml", "/registry/tenants/{id}", "get", 404, "application/problem+json", []byte(prob)); err != nil {
		t.Fatal(err)
	}
	if err := s.Validate("modules/config.yaml", "/config/tenants/{id}", "delete", 204, "", nil); err != nil {
		t.Fatal(err)
	}
	if err := s.Validate("modules/platform.yaml", "/registry/cells", "get", 201, "application/json", []byte(`{}`)); err == nil {
		t.Fatal("mã trạng thái không khai phải bị bắt")
	}
}
