package db

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

func TestInTx_ContextMissingTenant(t *testing.T) {
	pool := NewSimulatedPool(1)
	err := InTx(context.Background(), pool, func(ctx context.Context, tx Tx) error {
		return nil
	})
	if !errors.Is(err, tenant.ErrMissingTenant) {
		t.Fatalf("expected tenant.ErrMissingTenant, got %v", err)
	}
}

func TestInTx_PgBouncerSinglePhysicalConn_ZeroCrossTenantLeak(t *testing.T) {
	// default_pool_size = 1 physical connection multiplexed across transactions
	pool := NewSimulatedPool(1)
	tenantA := "11111111-1111-4111-8111-111111111111"
	tenantB := "22222222-2222-4222-8222-222222222222"

	ctxA := tenant.MustWithTenantID(context.Background(), tenantA)
	ctxB := tenant.MustWithTenantID(context.Background(), tenantB)

	// Seed 25 rows for tenant A and 25 rows for tenant B over single physical connection
	for i := 0; i < 25; i++ {
		idx := i
		if err := InTx(ctxA, pool, func(ctx context.Context, tx Tx) error {
			_, err := tx.Exec(ctx, "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)",
				fmt.Sprintf("a-%d", idx), tenantA, fmt.Sprintf("title-a-%d", idx))
			return err
		}); err != nil {
			t.Fatalf("seed tenantA %d failed: %v", i, err)
		}
		if err := InTx(ctxB, pool, func(ctx context.Context, tx Tx) error {
			_, err := tx.Exec(ctx, "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)",
				fmt.Sprintf("b-%d", idx), tenantB, fmt.Sprintf("title-b-%d", idx))
			return err
		}); err != nil {
			t.Fatalf("seed tenantB %d failed: %v", i, err)
		}
	}

	// Interleave 200 concurrent transactions across tenant A and tenant B over default_pool_size=1
	var wg sync.WaitGroup
	errCh := make(chan error, 200)
	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func(iter int) {
			defer wg.Done()
			wantTenant := tenantA
			ctx := ctxA
			if iter%2 == 1 {
				wantTenant = tenantB
				ctx = ctxB
			}
			err := InTx(ctx, pool, func(ctx context.Context, tx Tx) error {
				var currentGUC string
				if err := tx.QueryRow(ctx, "SELECT current_setting('app.tenant_id', true)").Scan(&currentGUC); err != nil {
					return err
				}
				if currentGUC != wantTenant {
					return fmt.Errorf("GUC leak: got %q, want %q", currentGUC, wantTenant)
				}
				var count int64
				if err := tx.QueryRow(ctx, "SELECT count(*) FROM rls_items").Scan(&count); err != nil {
					return err
				}
				if count != 25 {
					return fmt.Errorf("expected 25 rows for tenant %s, got %d", wantTenant, count)
				}
				return nil
			})
			if err != nil {
				errCh <- err
			}
		}(i)
	}
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatalf("concurrent PgBouncer RLS test failed: %v", err)
	}

	// Ensure raw transaction without InTx has empty app.tenant_id (SET LOCAL cleared on commit)
	rawTx, err := pool.Begin(context.Background())
	if err != nil {
		t.Fatalf("raw Begin failed: %v", err)
	}
	var leakedGUC string
	if err := rawTx.QueryRow(context.Background(), "SELECT current_setting('app.tenant_id', true)").Scan(&leakedGUC); err != nil {
		t.Fatalf("raw QueryRow failed: %v", err)
	}
	_ = rawTx.Rollback(context.Background())
	if leakedGUC != "" {
		t.Fatalf("expected empty GUC outside InTx, got %q", leakedGUC)
	}

	// Ensure cross-tenant INSERT is rejected by FORCE RLS WITH CHECK
	err = InTenantTx(context.Background(), pool, tenantA, func(ctx context.Context, tx Tx) error {
		_, err := tx.Exec(ctx, "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)",
			"spoof-1", tenantB, "spoof")
		return err
	})
	if !errors.Is(err, ErrRLSViolation) {
		t.Fatalf("expected ErrRLSViolation when inserting tenantB row inside tenantA tx, got %v", err)
	}
}

func TestInTx_RollbackAndSessionSetGuard(t *testing.T) {
	pool := NewSimulatedPool(1)
	tenantA := "11111111-1111-4111-8111-111111111111"
	ctxA := tenant.MustWithTenantID(context.Background(), tenantA)

	// Session-level SET app.tenant_id must be rejected
	err := InTx(ctxA, pool, func(ctx context.Context, tx Tx) error {
		_, err := tx.Exec(ctx, "SET app.tenant_id = '22222222-2222-4222-8222-222222222222'")
		return err
	})
	if !errors.Is(err, ErrSessionTenantForbidden) {
		t.Fatalf("expected ErrSessionTenantForbidden, got %v", err)
	}

	// set_config(..., false) must also be rejected
	err = InTx(ctxA, pool, func(ctx context.Context, tx Tx) error {
		_, err := tx.Exec(ctx, "SELECT set_config('app.tenant_id', $1, false)", tenantA)
		return err
	})
	if !errors.Is(err, ErrSessionTenantForbidden) {
		t.Fatalf("expected ErrSessionTenantForbidden for is_local=false, got %v", err)
	}

	// Rollback on business error must discard inserted rows
	sentinel := errors.New("business failure")
	err = InTx(ctxA, pool, func(ctx context.Context, tx Tx) error {
		if _, err := tx.Exec(ctx, "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)",
			"rb-1", tenantA, "should-rollback"); err != nil {
			return err
		}
		return sentinel
	})
	if !errors.Is(err, sentinel) {
		t.Fatalf("expected sentinel error, got %v", err)
	}

	err = InTx(ctxA, pool, func(ctx context.Context, tx Tx) error {
		var count int64
		if err := tx.QueryRow(ctx, "SELECT count(*) FROM rls_items").Scan(&count); err != nil {
			return err
		}
		if count != 0 {
			t.Fatalf("expected 0 rows after rollback, got %d", count)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("query after rollback failed: %v", err)
	}
}
