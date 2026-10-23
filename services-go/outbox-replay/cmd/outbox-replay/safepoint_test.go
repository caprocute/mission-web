package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/twmb/franz-go/pkg/kfake"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/connect"
	"vn.viettel.okrkpi/services-go/outbox-replay/internal/connect/connecttest"
	"vn.viettel.okrkpi/services-go/outbox-replay/internal/replay"
)

// diemAnToanGia bọc kho PostgreSQL thật, giả lập mốc WAL cuối của cụm vừa nâng cấp ở TTDL kia (INF-18, DR-10 bước 8).
type diemAnToanGia struct {
	*replay.PGOutboxStore
	walCuoi time.Time
}

func (d diemAnToanGia) LastReplayTimestamp(context.Context) (time.Time, bool, error) {
	return d.walCuoi, true, nil
}

func TestSafePointThamSoXungDot(t *testing.T) {
	var stdout, stderr bytes.Buffer
	args := append(contractArgs(), "--schema", "task", "--tenant", connecttest.TenantA, "--from-safe-point", "--from", "2026-10-20T10:00:00Z")
	if code := runCLI(context.Background(), args, &stdout, &stderr, replay.NewInMemoryOutboxStore(), nil); code != 2 {
		t.Fatalf("--from-safe-point cùng --from: code=%d stderr=%s", code, stderr.String())
	}
	stderr.Reset()
	args = append(contractArgs(), "--schema", "task", "--tenant", connecttest.TenantA, "--from-safe-point")
	if code := runCLI(context.Background(), args, &stdout, &stderr, replay.NewInMemoryOutboxStore(), nil); code != 1 || !strings.Contains(stderr.String(), "không hỗ trợ") {
		t.Fatalf("kho không có mốc WAL: code=%d stderr=%s", code, stderr.String())
	}
}

// Trên PostgreSQL thật chưa từng khôi phục, pg_last_xact_replay_timestamp() rỗng: công cụ dừng và yêu cầu --from.
func TestSafePointCumChuaKhoiPhuc(t *testing.T) {
	dsn := connecttest.PGFixture(t)
	withEnv(t, map[string]string{connect.EnvDatabaseURL: dsn})
	var stdout, stderr bytes.Buffer
	args := append(contractArgs(), "--schema", "task", "--tenant", connecttest.TenantA, "--from-safe-point")
	if code := runCLI(context.Background(), args, &stdout, &stderr, nil, nil); code != 1 || !strings.Contains(stderr.String(), "chưa từng ở chế độ khôi phục") {
		t.Fatalf("code=%d stderr=%s", code, stderr.String())
	}
}

// Điểm an toàn = WAL cuối − 15 phút: chỉ các dòng từ mốc đó được chọn. Phát lại hai lần (chồng lên những gì
// Debezium đã gửi trước sự cố) lên Kafka, consumer có inbox (processed_event, khoá eventId) chỉ xử lý mỗi sự kiện
// một lần.
func TestSafePointPhatLaiKhongTrungOConsumerCoInbox(t *testing.T) {
	dsn := connecttest.PGFixture(t)
	replayTopic := "task.task.assigned.v1.replay.svc-scoring"
	c, err := kfake.NewCluster(kfake.SeedTopics(1, replayTopic, replay.AuditTopic))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	withEnv(t, map[string]string{connect.EnvDatabaseURL: dsn, connect.EnvBrokers: strings.Join(c.ListenAddrs(), ",")})
	ctx := context.Background()
	pg, dong, err := connect.OpenStore(ctx, connect.FromEnv(getenv), connecttest.TenantA)
	if err != nil {
		t.Fatal(err)
	}
	defer dong()
	// Dữ liệu mẫu: hai dòng lúc 10:00, hai dòng lúc 10:01 (một của tenant B). WAL cuối 10:15:30 → từ 10:00:30.
	store := diemAnToanGia{PGOutboxStore: pg, walCuoi: time.Date(2026, 10, 20, 10, 15, 30, 0, time.UTC)}

	base := append(contractArgs(), "--schema", "task", "--tenant", connecttest.TenantA, "--consumer", "svc-scoring", "--from-safe-point")
	var stdout, stderr bytes.Buffer
	if code := runCLI(ctx, base, &stdout, &stderr, store, nil); code != 0 {
		t.Fatalf("dry-run: code=%d stderr=%s", code, stderr.String())
	}
	if !strings.Contains(stderr.String(), "ĐIỂM AN TOÀN: --from=2026-10-20T10:00:30Z") {
		t.Fatalf("thiếu điểm an toàn: %s", stderr.String())
	}
	var dry replay.Result
	if err := json.Unmarshal(stdout.Bytes(), &dry); err != nil {
		t.Fatal(err)
	}
	if dry.TotalMatched != 2 {
		t.Fatalf("điểm an toàn phải chọn 2 dòng lúc 10:01 của tenant A: %+v", dry)
	}

	for lan := 1; lan <= 2; lan++ {
		stdout.Reset()
		stderr.Reset()
		args := append(base, "--confirm", "--actor", "ops", "--reason", "DR-10-buoc-8", "--rate-limit", "0")
		if code := runCLI(ctx, args, &stdout, &stderr, store, nil); code != 0 {
			t.Fatalf("lần %d: code=%d stderr=%s", lan, code, stderr.String())
		}
	}

	// Consumer có inbox: INSERT ... ON CONFLICT DO NOTHING theo (consumer, event_id) như processed_event.
	admin := os.Getenv("OUTBOX_REPLAY_PG_DSN")
	conn, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	bang := "inbox_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	if _, err := conn.Exec(ctx, "CREATE TABLE "+bang+" (consumer text, event_id uuid, PRIMARY KEY (consumer, event_id))"); err != nil {
		t.Fatal(err)
	}
	defer func() { _, _ = conn.Exec(context.Background(), "DROP TABLE "+bang) }()
	nhan := consume(t, c.ListenAddrs(), replayTopic, 4)
	xuLy := 0
	for _, r := range nhan {
		id, _ := r.HeaderValue("ce_id")
		tag, err := conn.Exec(ctx, "INSERT INTO "+bang+" VALUES ('svc-scoring', $1) ON CONFLICT DO NOTHING", id)
		if err != nil {
			t.Fatal(err)
		}
		xuLy += int(tag.RowsAffected())
	}
	var soDong int
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM "+bang).Scan(&soDong); err != nil {
		t.Fatal(err)
	}
	if len(nhan) != 4 || xuLy != 2 || soDong != 2 {
		t.Fatalf("nhận %d bản ghi, xử lý %d, inbox %d dòng; muốn 4, 2, 2", len(nhan), xuLy, soDong)
	}
}
