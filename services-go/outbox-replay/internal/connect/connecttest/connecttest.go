// Package connecttest dựng CSDL kiểm thử cho internal/connect và cmd/outbox-replay. Chỉ dùng trong kiểm thử.
package connecttest

import (
	"context"
	"net/url"
	"os"
	"strconv"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// Hai tenant của dữ liệu mẫu.
const (
	TenantA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	TenantB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
)

// PGFixture dựng schema `task` tối thiểu giống NEN-032 (FORCE RLS theo app.tenant_id, bảng
// partman.part_config) trên CSDL do OUTBOX_REPLAY_PG_DSN chỉ (siêu người dùng), tạo vai trò chỉ
// đọc ro_task_<hậu tố> và trả DSN của vai trò đó. Bỏ qua khi không đặt biến (CI có PostgreSQL thì đặt).
func PGFixture(t *testing.T) string {
	t.Helper()
	admin := os.Getenv("OUTBOX_REPLAY_PG_DSN")
	if admin == "" {
		t.Skip("đặt OUTBOX_REPLAY_PG_DSN (siêu người dùng PostgreSQL) để chạy kiểm thử với CSDL thật")
	}
	ctx := context.Background()
	// Mỗi lần gọi một CSDL và một vai trò riêng: các gói kiểm thử chạy song song trên cùng cụm.
	suffix := strconv.FormatInt(time.Now().UnixNano(), 36)
	dbName, role := "outbox_replay_"+suffix, "ro_task_"+suffix
	adminConn, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Fatal(err)
	}
	defer adminConn.Close(ctx)
	if _, err := adminConn.Exec(ctx, "CREATE DATABASE "+dbName); err != nil {
		t.Fatal(err)
	}
	if _, err := adminConn.Exec(ctx, "CREATE ROLE "+role+" LOGIN PASSWORD 'ro_task_test'"); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		c, err := pgx.Connect(context.Background(), admin)
		if err != nil {
			return
		}
		defer c.Close(context.Background())
		_, _ = c.Exec(context.Background(), "DROP DATABASE IF EXISTS "+dbName+" WITH (FORCE)")
		_, _ = c.Exec(context.Background(), "DROP ROLE IF EXISTS "+role)
	})
	au, err := url.Parse(admin)
	if err != nil {
		t.Fatal(err)
	}
	au.Path = "/" + dbName
	conn, err := pgx.Connect(ctx, au.String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	ddl := []string{
		`CREATE SCHEMA task`,
		`CREATE TABLE task.outbox_event (
			id uuid NOT NULL, tenant_id uuid NOT NULL, aggregate_type varchar(64) NOT NULL,
			aggregate_id uuid NOT NULL, aggregate_version bigint NOT NULL, event_type varchar(96) NOT NULL,
			topic varchar(128) NOT NULL, event_key varchar(80) NOT NULL, payload bytea NOT NULL,
			schema_version int NOT NULL, headers jsonb NOT NULL DEFAULT '{}'::jsonb,
			created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (id, created_at)
		) PARTITION BY RANGE (created_at)`,
		`CREATE TABLE task.outbox_event_p2026 PARTITION OF task.outbox_event FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')`,
		`ALTER TABLE task.outbox_event ENABLE ROW LEVEL SECURITY`,
		`ALTER TABLE task.outbox_event FORCE ROW LEVEL SECURITY`,
		`CREATE POLICY rls_outbox ON task.outbox_event FOR ALL TO ` + role + `
			USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)`,
		`GRANT USAGE ON SCHEMA task TO ` + role,
		`GRANT SELECT ON task.outbox_event TO ` + role,
		`CREATE SCHEMA partman`,
		`CREATE TABLE partman.part_config (parent_table text PRIMARY KEY, retention text)`,
		`INSERT INTO partman.part_config VALUES ('task.outbox_event', '72 hours')`,
		`GRANT USAGE ON SCHEMA partman TO ` + role,
		`GRANT SELECT ON partman.part_config TO ` + role,
	}
	for _, q := range ddl {
		if _, err := conn.Exec(ctx, q); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	base := time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC)
	ins := `INSERT INTO task.outbox_event (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, event_key, payload, schema_version, headers, created_at)
		VALUES ($1, $2, 'Task', $3, $4, 'task.task.assigned.v1', 'task.task.assigned.v1', $8, $5, 1, $6::jsonb, $7)`
	rows := []struct {
		id, tenant, agg string
		ver             int64
		at              time.Time
	}{
		{"0195b000-0000-7000-8000-000000000002", TenantA, "22222222-2222-4222-8222-000000000001", 2, base.Add(time.Minute)},
		{"0195b000-0000-7000-8000-000000000001", TenantA, "22222222-2222-4222-8222-000000000001", 1, base},
		{"0195b000-0000-7000-8000-000000000003", TenantB, "33333333-3333-4333-8333-000000000001", 1, base},
		{"0195b000-0000-7000-8000-000000000004", TenantA, "22222222-2222-4222-8222-000000000002", 1, base.Add(time.Minute)},
	}
	for _, r := range rows {
		if _, err := conn.Exec(ctx, ins, r.id, r.tenant, r.agg, r.ver, []byte{0x02, byte(r.ver)},
			`{"traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01","classification":"C1"}`, r.at, r.tenant+":"+r.agg); err != nil {
			t.Fatal(err)
		}
	}
	au.User = url.UserPassword(role, "ro_task_test")
	return au.String()
}
