// Mẫu mã Go ĐÚNG quy ước NEN-034 lớp 4 (SVC-03, SVC-09, SVC-12).
package good

import (
	"context"
	"database/sql"
)

func applyTenantSafe(ctx context.Context, tx *sql.Tx, tenantID string) error {
	_, err := tx.ExecContext(ctx, "SELECT set_config('app.tenant_id', $1, true)", tenantID)
	return err
}
