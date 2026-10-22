// Kiểm thử tích hợp DV-12-01 trên PostgreSQL 17 và Kafka thật (Testcontainers): giao lại EVT-21 ba lần chỉ tạo
// một thông báo cho mỗi người nhận, hai tenant không thấy thông báo của nhau, EVT-04 sinh mẫu chủ trì và phối
// hợp, tham số C2 bị chặn và chuyển thư chết, phản hồi API khớp platform.yaml, phân vùng ngày tạo trước và
// phân vùng quá 90 ngày bị xoá.
package it

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/notification/internal/api"
	"mission/services-go/notification/internal/consume"
	"mission/services-go/notification/internal/contract"
	"mission/services-go/notification/internal/store"
	"mission/services-go/notification/internal/testdb"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

var (
	tenantA = "0192f000-0000-7000-8000-00000000000a"
	tenantB = "0192f000-0000-7000-8000-00000000000b"
	user1   = "0192f000-0000-7000-8000-000000000001"
	user2   = "0192f000-0000-7000-8000-000000000002"
	user3   = "0192f000-0000-7000-8000-000000000003"
)

func token(tenantID, sub string) string {
	h := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none"}`))
	p, _ := json.Marshal(map[string]any{"tenant_id": tenantID, "sub": sub})
	return h + "." + base64.RawURLEncoding.EncodeToString(p) + ".sig"
}

func record(t *testing.T, topic, eventID, tenantID string, v any) *kgo.Record {
	t.Helper()
	b, err := consume.Encode(topic, v)
	if err != nil {
		t.Fatal(err)
	}
	return &kgo.Record{Topic: topic, Key: []byte(tenantID), Value: b, Headers: []kgo.RecordHeader{
		{Key: "id", Value: []byte(eventID)},
		{Key: "tenantid", Value: []byte(tenantID)},
		{Key: "occurredat", Value: []byte(time.Now().UTC().Format(time.RFC3339Nano))},
	}}
}

func TestNotificationService(t *testing.T) {
	if testing.Short() {
		t.Skip("kiểm thử tích hợp cần Docker")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	pg, err := tcpg.Run(ctx, "postgres:17", tcpg.WithDatabase("notify"), tcpg.WithUsername("postgres"),
		tcpg.WithPassword(uuid.NewString()), tcpg.BasicWaitStrategies())
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, pg)
	adminDSN, err := pg.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	db, err := testdb.Bootstrap(ctx, adminDSN, "notify")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, db.SvcDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()

	kc, err := tckafka.Run(ctx, "confluentinc/confluent-local:7.5.0", tckafka.WithClusterID("notify-it"))
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, kc)
	brokers, err := kc.Brokers(ctx)
	if err != nil {
		t.Fatal(err)
	}

	prod, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.AllowAutoTopicCreation())
	if err != nil {
		t.Fatal(err)
	}
	defer prod.Close()

	evt21 := uuid.NewString()
	req := consume.YeuCauThongBao{RecipientIDs: []string{user1, user2}, TemplateCode: "OKR_CHO_DUYET",
		Params:   `{"unitName":"Ban Tổ chức","periodName":"Quý I/2027","dueDate":"08/01/2027"}`,
		DeepLink: strp("/okr/phe-duyet"), Priority: "CAO", AggregateVersion: 1}
	var recs []*kgo.Record
	for i := 0; i < 3; i++ { // Kafka giao lại cùng một sự kiện ba lần
		recs = append(recs, record(t, consume.TopicNotifyRequested, evt21, tenantA, req))
	}
	recs = append(recs, record(t, consume.TopicNotifyRequested, uuid.NewString(), tenantB, consume.YeuCauThongBao{
		RecipientIDs: []string{user1}, TemplateCode: "OKR_NHAC_CHECKIN", Params: `{"dueDate":"15/01/2027"}`,
		Priority: "THUONG", AggregateVersion: 1}))
	recs = append(recs, record(t, consume.TopicNotifyRequested, uuid.NewString(), tenantA, consume.YeuCauThongBao{
		RecipientIDs: []string{user3}, TemplateCode: "THONG_BAO_CHUNG", Params: `{"soDienThoai":"0912345678"}`,
		Priority: "THUONG", AggregateVersion: 1}))
	taskID := uuid.NewString()
	recs = append(recs, record(t, consume.TopicTaskAssigned, uuid.NewString(), tenantA, consume.NhiemVuDaGiao{
		TaskID: taskID, Title: "Dự thảo hướng dẫn kiểm điểm", LeadAssigneeID: user3, AssigneeIDs: []string{user2},
		DueDate: time.Date(2026, 10, 30, 0, 0, 0, 0, time.UTC), AggregateVersion: 2}))
	if err := prod.ProduceSync(ctx, recs...).FirstErr(); err != nil {
		t.Fatal(err)
	}

	prov, _ := otel.Init(otel.Config{ServiceName: "notification-service", ServiceVersion: "test", Cell: "CELL-IT"})
	proc, err := consume.NewProcessor(store.Beginner{Pool: pool}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	cons, err := consume.NewConsumer(brokers, proc, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	cons.OTel = prov
	runCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() { _ = cons.Run(runCtx); close(done) }()
	t.Cleanup(func() { stop(); <-done; cons.Close() })

	deadline := time.Now().Add(90 * time.Second)
	for {
		if prov.CounterValue(consume.MetricCreated) >= 5 && prov.CounterValue(consume.MetricDuplicate) >= 2 &&
			prov.CounterValue(consume.MetricDLQ) >= 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("hết giờ chờ consumer: tạo=%d trùng=%d thư chết=%d", prov.CounterValue(consume.MetricCreated),
				prov.CounterValue(consume.MetricDuplicate), prov.CounterValue(consume.MetricDLQ))
		}
		time.Sleep(200 * time.Millisecond)
	}

	admin, err := pgx.Connect(ctx, adminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close(ctx)

	t.Run("giao lại ba lần chỉ tạo một thông báo mỗi người nhận", func(t *testing.T) {
		var n int
		if err := admin.QueryRow(ctx, `SELECT count(*) FROM notify.notification WHERE source_event_id = $1`, evt21).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 2 {
			t.Fatalf("mong đợi 2 thông báo, có %d", n)
		}
		if err := admin.QueryRow(ctx, `SELECT count(*) FROM notify.delivery d JOIN notify.notification x
			ON x.id = d.notification_id WHERE x.source_event_id = $1 AND d.channel = 'INAPP' AND d.status = 'DA_GUI'`, evt21).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 2 {
			t.Fatalf("mong đợi 2 lần gửi INAPP, có %d", n)
		}
	})

	t.Run("tham số C2 bị chặn", func(t *testing.T) {
		var n int
		if err := admin.QueryRow(ctx, `SELECT count(*) FROM notify.notification WHERE recipient_user_id = $1 AND template_code = 'THONG_BAO_CHUNG'`, user3).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 0 {
			t.Fatal("thông báo mang tham số C2 không được ghi")
		}
	})

	t.Run("EVT-04 báo chủ trì và phối hợp", func(t *testing.T) {
		rows, err := admin.Query(ctx, `SELECT recipient_user_id::text, template_code, deep_link FROM notify.notification
			WHERE deep_link = $1 ORDER BY template_code`, "/tasks/"+taskID)
		if err != nil {
			t.Fatal(err)
		}
		got := map[string]string{}
		for rows.Next() {
			var r, c, l string
			if err := rows.Scan(&r, &c, &l); err != nil {
				t.Fatal(err)
			}
			got[c] = r
		}
		if got["NV_DUOC_GIAO"] != user3 || got["NV_PHOI_HOP"] != user2 {
			t.Fatalf("người nhận EVT-04 sai: %v", got)
		}
	})

	t.Run("RLS chặn khi chưa đặt tenant", func(t *testing.T) {
		var n int
		if err := pool.QueryRow(ctx, `SELECT count(*) FROM notify.notification`).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 0 {
			t.Fatalf("svc_notify không đặt app.tenant_id không được thấy dòng nào, thấy %d", n)
		}
	})

	spec, err := contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi"))
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(api.Handler(api.Deps{Inbox: store.Reader{Pool: pool}}))
	defer srv.Close()

	call := func(t *testing.T, method, path, tok string, body any) (int, []byte, string) {
		t.Helper()
		var rd io.Reader
		if body != nil {
			b, _ := json.Marshal(body)
			rd = bytes.NewReader(b)
		}
		rq, _ := http.NewRequest(method, srv.URL+path, rd)
		rq.Header.Set("Authorization", "Bearer "+tok)
		if body != nil {
			rq.Header.Set("Content-Type", "application/json")
			rq.Header.Set("Idempotency-Key", uuid.NewString())
		}
		rs, err := http.DefaultClient.Do(rq)
		if err != nil {
			t.Fatal(err)
		}
		defer rs.Body.Close()
		b, _ := io.ReadAll(rs.Body)
		return rs.StatusCode, b, rs.Header.Get("Content-Type")
	}
	list := func(t *testing.T, tenantID, user, q string) store.Page {
		t.Helper()
		code, b, ct := call(t, "GET", "/api/v1/notifications"+q, token(tenantID, user), nil)
		if code != 200 {
			t.Fatalf("listNotifications trả %d: %s", code, b)
		}
		if err := spec.Validate("modules/platform.yaml", "/notifications", "get", code, ct, b); err != nil {
			t.Fatal(err)
		}
		var p store.Page
		_ = json.Unmarshal(b, &p)
		return p
	}

	t.Run("hai tenant không thấy thông báo của nhau", func(t *testing.T) {
		a := list(t, tenantA, user1, "")
		b := list(t, tenantB, user1, "")
		if len(a.Items) != 1 || a.Items[0].TemplateCode != "OKR_CHO_DUYET" || a.Items[0].Title != "Có bộ OKR cần phê duyệt" {
			t.Fatalf("hộp thông báo tenant A sai: %+v", a.Items)
		}
		if a.Items[0].Body != "Ban Tổ chức trình OKR Quý I/2027, hạn duyệt 08/01/2027." {
			t.Fatalf("nội dung dựng sai: %q", a.Items[0].Body)
		}
		if len(b.Items) != 1 || b.Items[0].TemplateCode != "OKR_NHAC_CHECKIN" || b.Items[0].ID == a.Items[0].ID {
			t.Fatalf("hộp thông báo tenant B sai: %+v", b.Items)
		}
		if n := list(t, tenantB, user2, ""); len(n.Items) != 0 {
			t.Fatalf("user2 ở tenant B không có thông báo, thấy %d", len(n.Items))
		}
	})

	t.Run("phân trang con trỏ và đánh dấu đã đọc", func(t *testing.T) {
		p1 := list(t, tenantA, user2, "?limit=1")
		if len(p1.Items) != 1 || p1.NextCursor == nil || p1.UnreadCount != 2 {
			t.Fatalf("trang 1 sai: %+v", p1)
		}
		p2 := list(t, tenantA, user2, "?limit=1&cursor="+*p1.NextCursor)
		if len(p2.Items) != 1 || p2.NextCursor != nil || p2.Items[0].ID == p1.Items[0].ID {
			t.Fatalf("trang 2 sai: %+v", p2)
		}
		code, b, ct := call(t, "POST", "/api/v1/notifications/read", token(tenantA, user2), map[string]any{"ids": []string{p1.Items[0].ID}})
		if code != 200 {
			t.Fatalf("markNotificationsRead trả %d: %s", code, b)
		}
		if err := spec.Validate("modules/platform.yaml", "/notifications/read", "post", code, ct, b); err != nil {
			t.Fatal(err)
		}
		var res map[string]int
		_ = json.Unmarshal(b, &res)
		if res["updated"] != 1 || res["unreadCount"] != 1 {
			t.Fatalf("kết quả đánh dấu sai: %v", res)
		}
		if u := list(t, tenantA, user2, "?unread=true"); len(u.Items) != 1 || u.Items[0].ID != p2.Items[0].ID {
			t.Fatalf("lọc chưa đọc sai: %+v", u.Items)
		}
		// Tenant B không đánh dấu được thông báo của tenant A dù biết mã.
		_, b, _ = call(t, "POST", "/api/v1/notifications/read", token(tenantB, user2), map[string]any{"ids": []string{p2.Items[0].ID}})
		_ = json.Unmarshal(b, &res)
		if res["updated"] != 0 {
			t.Fatal("tenant khác không được đánh dấu thông báo")
		}
		_, b, _ = call(t, "POST", "/api/v1/notifications/read", token(tenantA, user2), map[string]any{"all": true})
		_ = json.Unmarshal(b, &res)
		if res["updated"] != 1 || res["unreadCount"] != 0 {
			t.Fatalf("đánh dấu tất cả sai: %v", res)
		}
		code, b, ct = call(t, "POST", "/api/v1/notifications/read", token(tenantA, user2), map[string]any{"all": true, "ids": []string{p2.Items[0].ID}})
		if code != 422 {
			t.Fatalf("gửi cả ids và all phải trả 422, nhận %d", code)
		}
		if err := spec.Validate("modules/platform.yaml", "/notifications/read", "post", code, ct, b); err != nil {
			t.Fatal(err)
		}
		code, _, _ = call(t, "GET", "/api/v1/notifications?cursor=sai", token(tenantA, user2), nil)
		if code != 400 {
			t.Fatalf("con trỏ sai phải trả 400, nhận %d", code)
		}
	})

	t.Run("phân vùng ngày tạo trước và xoá sau 90 ngày", func(t *testing.T) {
		old := time.Now().UTC().AddDate(0, 0, -100)
		if _, err := pool.Exec(ctx, `SELECT notify.fn_ensure_day_partitions($1::date, 1)`, old.Format(time.DateOnly)); err != nil {
			t.Fatal(err)
		}
		created, dropped, err := store.Maintain(ctx, pool, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		if dropped < 2 {
			t.Fatalf("phải xoá phân vùng quá 90 ngày của notification và delivery, xoá %d", dropped)
		}
		var reg *string
		name := fmt.Sprintf("notify.notification_p%s", old.Format("20060102"))
		_ = admin.QueryRow(ctx, `SELECT to_regclass($1)::text`, name).Scan(&reg)
		if reg != nil {
			t.Fatalf("phân vùng %s phải bị xoá", name)
		}
		future := fmt.Sprintf("notify.delivery_p%s", time.Now().UTC().AddDate(0, 0, 7).Format("20060102"))
		_ = admin.QueryRow(ctx, `SELECT to_regclass($1)::text`, future).Scan(&reg)
		if reg == nil {
			t.Fatalf("phân vùng %s phải được tạo trước (tạo mới %d)", future, created)
		}
		var n int
		_ = admin.QueryRow(ctx, `SELECT count(*) FROM notify.notification`).Scan(&n)
		if n != 5 {
			t.Fatalf("phân vùng hiện hành không được đụng tới, còn %d dòng", n)
		}
	})
}

func strp(s string) *string { return &s }
