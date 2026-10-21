package db

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

func TestDirectoryAndPoolRouter(t *testing.T) {
	const (
		poolTenantID      = "0195f3a0-0000-7000-8000-000000000001"
		bridgeTenantID    = "0195f3a0-0000-7000-8000-000000000002"
		otherCellTenantID = "0195f3a0-0000-7000-8000-000000000003"
		migratingTenantID = "0195f3a0-0000-7000-8000-000000000004"
	)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tid := r.URL.Query().Get("tenantId")
		w.Header().Set("Content-Type", "application/json")
		switch tid {
		case poolTenantID:
			_, _ = w.Write([]byte(`{
				"tenantId": "0195f3a0-0000-7000-8000-000000000001",
				"tenantCode": "phuong_1",
				"status": "DANG_VAN_HANH",
				"tenancyModel": "POOL",
				"cell": {"code": "cell-hcm-01"},
				"cacheTtlSeconds": 300,
				"sourceVersion": 1
			}`))
		case bridgeTenantID:
			_, _ = w.Write([]byte(`{
				"tenantId": "0195f3a0-0000-7000-8000-000000000002",
				"tenantCode": "hcm_a",
				"status": "DANG_VAN_HANH",
				"tenancyModel": "BRIDGE",
				"cell": {"code": "cell-hcm-01"},
				"databaseName": "td_hcm_a",
				"cacheTtlSeconds": 300,
				"sourceVersion": 1
			}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	resolver := NewHTTPRegistryResolver(srv.URL, 2*time.Second)
	dir := NewDirectory(resolver)
	dir.Put(TenantRoute{
		TenantID:      otherCellTenantID,
		TenantCode:    "hn_a",
		CellCode:      "cell-hn-01",
		TenancyModel:  ModelBridge,
		DatabaseName:  "td_hn_a",
		Status:        "DANG_VAN_HANH",
		SourceVersion: 1,
	})
	dir.Put(TenantRoute{
		TenantID:      migratingTenantID,
		TenantCode:    "hcm_moving",
		CellCode:      "cell-hcm-01",
		TenancyModel:  ModelBridge,
		DatabaseName:  "td_hcm_moving",
		Status:        "DANG_CHUYEN_CELL",
		SourceVersion: 1,
	})

	poolMap := map[string]*SimulatedPool{
		"td_pool":     NewSimulatedPool(1),
		"td_hcm_a":    NewSimulatedPool(1),
		"td_phuong_1": NewSimulatedPool(1),
	}
	router := NewPoolRouter(dir, "cell-hcm-01", poolMap["td_pool"], func(_ context.Context, dbName string) (TxBeginner, error) {
		if p, ok := poolMap[dbName]; ok {
			return p, nil
		}
		return NewSimulatedPool(1), nil
	})

	// 1. POOL tenant resolves to td_pool and executes isolated transaction
	ctxPool := tenant.MustWithTenantID(context.Background(), poolTenantID)
	dbName, _, err := router.ResolveDatabaseName(ctxPool)
	if err != nil || dbName != "td_pool" {
		t.Fatalf("expected td_pool, got db=%q err=%v", dbName, err)
	}
	if err := router.InRoutedTx(ctxPool, func(ctx context.Context, tx Tx) error {
		_, err := tx.Exec(ctx, "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)", "item-1", poolTenantID, "Pool row")
		return err
	}); err != nil {
		t.Fatalf("unexpected error in InRoutedTx: %v", err)
	}

	// 2. BRIDGE tenant resolves to td_hcm_a (isolated physical pool)
	ctxBridge := tenant.MustWithTenantID(context.Background(), bridgeTenantID)
	dbName, _, err = router.ResolveDatabaseName(ctxBridge)
	if err != nil || dbName != "td_hcm_a" {
		t.Fatalf("expected td_hcm_a, got db=%q err=%v", dbName, err)
	}

	// 3. ApplyEvent upgrades phuong_1 from POOL to BRIDGE dynamically
	dir.ApplyEvent(TenantChangedEvent{
		TenantCode:    "phuong_1",
		Status:        "DANG_VAN_HANH",
		TenancyModel:  "BRIDGE",
		CellCode:      "cell-hcm-01",
		DatabaseName:  "td_phuong_1",
		SourceVersion: 2,
	})
	dbName, _, err = router.ResolveDatabaseName(ctxPool)
	if err != nil || dbName != "td_phuong_1" {
		t.Fatalf("expected td_phuong_1 after upgrade event, got db=%q err=%v", dbName, err)
	}

	// 4. Wrong cell returns ErrWrongCell and HTTP 421 via middleware
	ctxOtherCell := tenant.MustWithTenantID(context.Background(), otherCellTenantID)
	if _, _, err := router.ResolveDatabaseName(ctxOtherCell); !errors.Is(err, ErrWrongCell) {
		t.Fatalf("expected ErrWrongCell, got %v", err)
	}

	mw := CellRoutingMiddleware(dir, "cell-hcm-01")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	req421 := httptest.NewRequest(http.MethodGet, "/api/v1/registry/tenants", nil).WithContext(ctxOtherCell)
	rec421 := httptest.NewRecorder()
	mw.ServeHTTP(rec421, req421)
	if rec421.Code != http.StatusMisdirectedRequest {
		t.Fatalf("expected 421, got %d", rec421.Code)
	}
	if rec421.Header().Get("X-Target-Cell") != "cell-hn-01" {
		t.Fatalf("expected X-Target-Cell=cell-hn-01, got %q", rec421.Header().Get("X-Target-Cell"))
	}

	// 5. Migrating tenant returns ErrTenantUnavailable and HTTP 503 via middleware
	ctxMigrating := tenant.MustWithTenantID(context.Background(), migratingTenantID)
	if _, _, err := router.ResolveDatabaseName(ctxMigrating); !errors.Is(err, ErrTenantUnavailable) {
		t.Fatalf("expected ErrTenantUnavailable, got %v", err)
	}
	req503 := httptest.NewRequest(http.MethodGet, "/api/v1/registry/tenants", nil).WithContext(ctxMigrating)
	rec503 := httptest.NewRecorder()
	mw.ServeHTTP(rec503, req503)
	if rec503.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", rec503.Code)
	}
}

func BenchmarkDirectoryLookup(b *testing.B) {
	dir := NewDirectory(nil)
	tenantID := "0195f3a0-0000-7000-8000-000000000101"
	dir.Put(TenantRoute{
		TenantID:      tenantID,
		TenantCode:    "hcm_a",
		CellCode:      "cell-hcm-01",
		TenancyModel:  ModelBridge,
		DatabaseName:  "td_hcm_a",
		Status:        "DANG_VAN_HANH",
		SourceVersion: 1,
	})
	ctx := context.Background()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _, _ = dir.Resolve(ctx, tenantID)
	}
}
