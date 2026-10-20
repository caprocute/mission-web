package store_test

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/jackc/pgx/v5"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/tenant-registry/internal/testdb"
)

const flywayImage = "flyway/flyway:11"

// Chạy chính Flyway 11 (ảnh flyway/flyway) bằng vai trò mig_registry trên CSDL trống để chắc tên tệp,
// thứ tự phiên bản và cú pháp hợp với Flyway. Bỏ qua khi máy kiểm thử chưa có ảnh.
func TestFlywayMigrate(t *testing.T) {
	if err := exec.Command("docker", "image", "inspect", flywayImage).Run(); err != nil {
		t.Skipf("chưa có ảnh %s, bỏ qua", flywayImage)
	}
	ctx := context.Background()
	fresh, err := startPG(ctx, false)
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Terminate(ctx)
	ip, err := fresh.c.ContainerIP(ctx)
	if err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(fresh.MigDSN)
	pass, _ := u.User.Password()
	dir := filepath.Join(testdb.ModuleRoot(), "db", "migration", "registry")
	fw, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{
			Image: flywayImage,
			Cmd: []string{
				fmt.Sprintf("-url=jdbc:postgresql://%s:5432/registry", ip),
				"-user=mig_registry", "-password=" + pass,
				"-schemas=registry", "-defaultSchema=registry",
				"-locations=filesystem:/flyway/sql", "-connectRetries=5", "migrate",
			},
			HostConfigModifier: func(hc *container.HostConfig) { hc.Binds = append(hc.Binds, dir+":/flyway/sql:ro") },
			WaitingFor:         wait.ForExit().WithExitTimeout(2 * time.Minute),
		},
		Started: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = fw.Terminate(ctx) }()
	logs, _ := fw.Logs(ctx)
	out, _ := io.ReadAll(logs)
	state, _ := fw.State(ctx)
	if state.ExitCode != 0 {
		t.Fatalf("flyway migrate thất bại (mã %d):\n%s", state.ExitCode, out)
	}
	conn, err := pgx.Connect(ctx, fresh.AdminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	var applied int
	if err := conn.QueryRow(ctx, `SELECT count(*) FROM registry.flyway_schema_history WHERE success AND version IS NOT NULL`).Scan(&applied); err != nil {
		t.Fatal(err)
	}
	files, _ := testdb.MigrationFiles()
	if applied != len(files) {
		t.Fatalf("Flyway áp %d migration, thư mục có %d:\n%s", applied, len(files), out)
	}
	if !strings.Contains(string(out), "Successfully applied") {
		t.Logf("nhật ký flyway:\n%s", out)
	}
}
