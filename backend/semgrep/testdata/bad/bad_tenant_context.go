// Mẫu mã Go SAI để chứng minh luật Semgrep NEN-034 lớp 4 bắt được đặt tenant mức phiên (SVC-03, SVC-09, SVC-12).
package bad

import (
	"context"
	"database/sql"
)

func applyTenantWrong(ctx context.Context, tx *sql.Tx, tenantID string) error {
	// ruleid: mission-no-session-set-app-param
	if _, err := tx.ExecContext(ctx, "SET app.tenant_id = $1", tenantID); err != nil {
		return err
	}
	// ruleid: mission-no-set-session
	if _, err := tx.ExecContext(ctx, "SET SESSION statement_timeout = '30s'"); err != nil {
		return err
	}
	// ruleid: mission-no-set-config-is-local-false
	_, err := tx.ExecContext(ctx, "SELECT set_config('app.tenant_id', $1, false)", tenantID)
	return err
}
