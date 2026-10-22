// Kiểm thử tích hợp DV-12-03 trên PostgreSQL 17 thật, Mailpit làm hộp thư thử, máy chủ HTTP giả lập cổng SMS, FCM
// và APNs (HTTP/2 qua TLS): cổng SMS lỗi liên tục thì mạch SMS mở trong khi email và đẩy vẫn gửi đúng hạn, SMS
// xếp hàng rồi gửi lại khi cổng phục hồi; giờ yên lặng hoãn thông báo thường, không hoãn thông báo ưu tiên cao;
// thiết bị hết đăng ký bị thu hồi; kênh bị tắt lúc gửi thì bỏ qua; không có email, số điện thoại dạng rõ trong CSDL
// và nhật ký.
package it

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/notification/internal/channel"
	"mission/services-go/notification/internal/crypt"
	"mission/services-go/notification/internal/deliver"
	"mission/services-go/notification/internal/directory"
	"mission/services-go/notification/internal/prefs"
	"mission/services-go/notification/internal/store"
	"mission/services-go/notification/internal/testdb"
	vdb "vn.viettel.okrkpi/services-go/vtplatform/db"
)

type fakeSMS struct {
	mu   sync.Mutex
	down atomic.Bool
	got  []map[string]string
}

func (f *fakeSMS) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if f.down.Load() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	var m map[string]string
	_ = json.NewDecoder(r.Body).Decode(&m)
	f.mu.Lock()
	f.got = append(f.got, m)
	f.mu.Unlock()
	_ = json.NewEncoder(w).Encode(map[string]string{"code": "0", "messageId": "sms-" + m["requestId"][:8]})
}

func startMailpit(t *testing.T, ctx context.Context) (smtpAddr, api string) {
	t.Helper()
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{Image: "axllent/mailpit:v1.31.6",
			ExposedPorts: []string{"1025/tcp", "8025/tcp"}, WaitingFor: wait.ForListeningPort("8025/tcp")},
		Started: true})
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, c)
	host, _ := c.Host(ctx)
	sp, _ := c.MappedPort(ctx, "1025/tcp")
	hp, _ := c.MappedPort(ctx, "8025/tcp")
	return fmt.Sprintf("%s:%s", host, sp.Port()), fmt.Sprintf("http://%s:%s", host, hp.Port())
}

func mailCount(t *testing.T, api, to string) int {
	t.Helper()
	resp, err := http.Get(api + "/api/v1/search?query=" + "to:" + to)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		MessagesCount int `json:"messages_count"`
		Messages      []struct {
			Subject string `json:"Subject"`
		} `json:"messages"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return out.MessagesCount
}

func TestExternalChannels(t *testing.T) {
	if testing.Short() {
		t.Skip("kiểm thử tích hợp cần Docker")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	pg, err := tcpg.Run(ctx, "postgres:17", tcpg.WithDatabase("notify"), tcpg.WithUsername("postgres"),
		tcpg.WithPassword(uuid.NewString()), tcpg.BasicWaitStrategies())
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, pg)
	adminDSN, _ := pg.ConnectionString(ctx, "sslmode=disable")
	db, err := testdb.Bootstrap(ctx, adminDSN, "notify")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, db.SvcDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	smtpAddr, mailAPI := startMailpit(t, ctx)

	tenant := uuid.NewString()
	u1, u2, u3 := uuid.NewString(), uuid.NewString(), uuid.NewString()
	email1, phone1, email2 := "can.bo.mot@donvi.test", "0912 345 678", "can.bo.hai@donvi.test"
	dir := directory.Static{tenant + "/" + u1: {Email: email1, Phone: phone1}, tenant + "/" + u2: {Email: email2},
		tenant + "/" + u3: {Email: "can.bo.ba@donvi.test", Phone: "0987654321"}}
	kr, _ := crypt.NewLocalKeyring(bytes.Repeat([]byte{7}, 32))
	field := crypt.Field{Keys: kr}
	ps := prefs.Store{Pool: pool, Field: field}
	yes, no := true, false
	all := []string{"IN_APP", "EMAIL", "SMS", "PUSH"}
	if _, err := ps.Update(ctx, tenant, u1, 0, prefs.Input{QuietHoursEnabled: &no,
		Categories: []prefs.CategoryPref{{Category: "NHIEM_VU", Channels: all}}}); err != nil {
		t.Fatal(err)
	}
	if _, err := ps.Update(ctx, tenant, u2, 0, prefs.Input{QuietHoursEnabled: &yes, QuietHoursStart: strp("22:00"),
		QuietHoursEnd: strp("06:00"), Categories: []prefs.CategoryPref{{Category: "NHIEM_VU",
			Channels: []string{"IN_APP", "EMAIL"}}}}); err != nil {
		t.Fatal(err)
	}
	if _, err := ps.Update(ctx, tenant, u3, 0, prefs.Input{QuietHoursEnabled: &no,
		Categories: []prefs.CategoryPref{{Category: "NHIEM_VU", Channels: []string{"IN_APP", "SMS"}}}}); err != nil {
		t.Fatal(err)
	}
	for _, d := range []prefs.Registration{{Platform: "FCM", PushToken: "fcm-tot", DeviceName: "Điện thoại"},
		{Platform: "APNS", PushToken: "apns-tot", DeviceName: "iPhone"},
		{Platform: "FCM", PushToken: "fcm-da-go", DeviceName: "Máy cũ"}} {
		if _, err := ps.Register(ctx, tenant, u1, d); err != nil {
			t.Fatal(err)
		}
	}

	// Giả lập ba cổng ngoài.
	sms := &fakeSMS{}
	sms.down.Store(true)
	smsSrv := httptest.NewServer(sms)
	defer smsSrv.Close()
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	der, _ := x509.MarshalPKCS8PrivateKey(rsaKey)
	var fcmSent atomic.Int32
	fcmSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			_ = r.ParseForm()
			if r.Form.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" || r.Form.Get("assertion") == "" {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "fcm-access", "expires_in": 3600})
			return
		}
		if r.Header.Get("Authorization") != "Bearer fcm-access" || r.URL.Path != "/v1/projects/mission-test/messages:send" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		var m struct {
			Message struct {
				Token string `json:"token"`
			} `json:"message"`
		}
		_ = json.NewDecoder(r.Body).Decode(&m)
		if m.Message.Token == "fcm-da-go" {
			w.WriteHeader(http.StatusNotFound)
			_, _ = io.WriteString(w, `{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`)
			return
		}
		fcmSent.Add(1)
		_ = json.NewEncoder(w).Encode(map[string]string{"name": "projects/mission-test/messages/1"})
	}))
	defer fcmSrv.Close()
	ecKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	var apnsSent atomic.Int32
	apnsSrv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor != 2 || !strings.HasPrefix(r.Header.Get("authorization"), "bearer ") ||
			r.Header.Get("apns-topic") != "vn.mission.app" || r.URL.Path != "/3/device/apns-tot" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		apnsSent.Add(1)
		w.Header().Set("apns-id", uuid.NewString())
	}))
	apnsSrv.EnableHTTP2 = true
	apnsSrv.StartTLS()
	defer apnsSrv.Close()

	var logs bytes.Buffer
	log := slog.New(slog.NewJSONHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	smsBreaker := channel.NewBreaker(2, 200*time.Millisecond)
	push := channel.PushRouter{
		FCM: &channel.FCM{BaseURL: fcmSrv.URL, Account: channel.ServiceAccount{ProjectID: "mission-test",
			ClientEmail: "notify@mission-test.iam", TokenURI: fcmSrv.URL + "/token",
			PrivateKey: string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))}},
		APNS: &channel.APNs{HTTP: apnsSrv.Client(), BaseURL: apnsSrv.URL, TeamID: "TEAM", KeyID: "KEY", Key: ecKey,
			Topic: "vn.mission.app"},
	}
	lanes := map[string]deliver.Lane{
		"EMAIL": {Channel: "EMAIL", Sender: channel.SMTP{Addr: smtpAddr, From: "thongbao@mission.test",
			TLSMode: "none"}, Concurrency: 4, Breaker: channel.NewBreaker(5, time.Minute)},
		"SMS":  {Channel: "SMS", Sender: channel.ViettelSMS{BaseURL: smsSrv.URL, Brandname: "MISSION", ASCII: true}, Concurrency: 2, Breaker: smsBreaker},
		"PUSH": {Channel: "PUSH", Sender: push, Concurrency: 4, Breaker: channel.NewBreaker(5, time.Minute)},
	}
	w := &deliver.Worker{Pool: pool, Prefs: ps, Directory: dir, Field: field, Log: log,
		Backoff: []time.Duration{50 * time.Millisecond}, MaxAttempts: 5, Poll: 50 * time.Millisecond}

	writer := store.NewWriter()
	writer.External = []string{"EMAIL", "SMS", "PUSH"}
	insert := func(t *testing.T, user, prio string, at time.Time) string {
		t.Helper()
		tx, err := store.Beginner{Pool: pool}.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = tx.Rollback(ctx) }()
		if _, err := tx.Exec(ctx, vdb.SetLocalTenantSQL, tenant); err != nil {
			t.Fatal(err)
		}
		ev := uuid.NewString()
		if _, err := writer.Insert(ctx, tx, store.NewNotification{TenantID: tenant, RecipientID: user,
			TemplateCode: "NV_DUOC_GIAO", Params: map[string]any{"title": "Báo cáo sơ kết quý", "dueDate": "30/10/2026"},
			DeepLink: strp("/nhiem-vu/1"), Priority: prio, SourceEventID: ev, CreatedAt: at}); err != nil {
			t.Fatal(err)
		}
		if err := tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		return store.NotificationID(ev, user).String()
	}
	type row struct {
		channel, status, lastErr string
		attempts                 int
	}
	deliveries := func(t *testing.T, notif string) map[string]row {
		t.Helper()
		out := map[string]row{}
		err := pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
			if _, err := tx.Exec(ctx, vdb.SetLocalTenantSQL, tenant); err != nil {
				return err
			}
			rows, err := tx.Query(ctx, `SELECT channel, status, coalesce(last_error, ''), attempts FROM notify.delivery
				WHERE notification_id = $1`, notif)
			if err != nil {
				return err
			}
			for rows.Next() {
				var r row
				if err := rows.Scan(&r.channel, &r.status, &r.lastErr, &r.attempts); err != nil {
					return err
				}
				out[r.channel] = r
			}
			return rows.Err()
		})
		if err != nil {
			t.Fatal(err)
		}
		return out
	}
	run := func(t *testing.T, ch string) int {
		t.Helper()
		n, err := w.RunOnce(ctx, lanes[ch], nil)
		if err != nil {
			t.Fatal(err)
		}
		return n
	}

	now := time.Now()
	t.Run("SMS lỗi thì mạch mở, email và đẩy vẫn gửi, SMS gửi lại khi cổng phục hồi", func(t *testing.T) {
		n1, n2 := insert(t, u1, "THUONG", now), insert(t, u3, "THUONG", now)
		if d := deliveries(t, n1); len(d) != 4 || d["INAPP"].status != "DA_GUI" || d["SMS"].status != "CHO" {
			t.Fatalf("delivery ban đầu: %+v", d)
		}
		run(t, "SMS")
		if !smsBreaker.IsOpen() {
			t.Fatal("mạch SMS phải mở sau hai lần lỗi liên tiếp")
		}
		start := time.Now()
		run(t, "EMAIL")
		run(t, "PUSH")
		if time.Since(start) > 5*time.Second {
			t.Fatal("email và đẩy bị chậm vì SMS lỗi")
		}
		d := deliveries(t, n1)
		if d["EMAIL"].status != "DA_GUI" || d["PUSH"].status != "DA_GUI" || d["SMS"].status != "CHO" ||
			d["SMS"].attempts != 1 || d["SMS"].lastErr == "" {
			t.Fatalf("sau lần gửi đầu: %+v", d)
		}
		if mailCount(t, mailAPI, email1) != 1 || fcmSent.Load() != 1 || apnsSent.Load() != 1 {
			t.Fatalf("email %d, fcm %d, apns %d", mailCount(t, mailAPI, email1), fcmSent.Load(), apnsSent.Load())
		}
		// Mạch mở: việc SMS đến hạn được trả về hàng đợi, không tính lần thử.
		time.Sleep(60 * time.Millisecond)
		run(t, "SMS")
		if a := deliveries(t, n2)["SMS"].attempts; a != 1 {
			t.Fatalf("mạch mở không được tính lần thử, có %d", a)
		}
		sms.down.Store(false)
		time.Sleep(250 * time.Millisecond) // hết thời gian mở, nửa mở cho một lần thử
		for i := 0; i < 5 && (deliveries(t, n1)["SMS"].status != "DA_GUI" || deliveries(t, n2)["SMS"].status != "DA_GUI"); i++ {
			run(t, "SMS")
			time.Sleep(60 * time.Millisecond)
		}
		if deliveries(t, n1)["SMS"].status != "DA_GUI" || deliveries(t, n2)["SMS"].status != "DA_GUI" || smsBreaker.IsOpen() {
			t.Fatalf("SMS chưa gửi lại: %+v %+v", deliveries(t, n1), deliveries(t, n2))
		}
		sms.mu.Lock()
		got := sms.got
		sms.mu.Unlock()
		if len(got) != 2 || got[0]["brandname"] != "MISSION" {
			t.Fatalf("cổng SMS nhận %v", len(got))
		}
		phones := map[string]bool{got[0]["to"]: true, got[1]["to"]: true}
		if !phones["84912345678"] || strings.ContainsAny(got[0]["content"], "ồệượ") {
			t.Fatalf("SMS phải theo dạng 84 và không dấu")
		}
		// Thiết bị FCM đã gỡ ứng dụng bị thu hồi.
		var revoked int
		_ = pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
			_, _ = tx.Exec(ctx, vdb.SetLocalTenantSQL, tenant)
			return tx.QueryRow(ctx, `SELECT count(*) FROM notify.push_device WHERE revoked_at IS NOT NULL`).Scan(&revoked)
		})
		if revoked != 1 {
			t.Fatalf("thiết bị hết đăng ký phải bị thu hồi, có %d", revoked)
		}
	})

	t.Run("giờ yên lặng hoãn thông báo thường, không hoãn ưu tiên cao", func(t *testing.T) {
		night := time.Date(2026, 10, 22, 23, 0, 0, 0, prefs.Local)
		quiet := &deliver.Worker{Pool: pool, Prefs: ps, Directory: dir, Field: field, Log: log,
			Now: func() time.Time { return night }}
		normal, high := insert(t, u2, "THUONG", now), insert(t, u2, "CAO", now)
		if _, err := quiet.RunOnce(ctx, lanes["EMAIL"], nil); err != nil {
			t.Fatal(err)
		}
		if d := deliveries(t, high)["EMAIL"]; d.status != "DA_GUI" {
			t.Fatalf("ưu tiên cao phải gửi ngay: %+v", d)
		}
		d := deliveries(t, normal)["EMAIL"]
		if d.status != "CHO" || d.attempts != 0 || d.lastErr != "QUIET_HOURS" {
			t.Fatalf("thông báo thường phải hoãn: %+v", d)
		}
		var next time.Time
		_ = pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
			_, _ = tx.Exec(ctx, vdb.SetLocalTenantSQL, tenant)
			return tx.QueryRow(ctx, `SELECT next_attempt_at FROM notify.delivery WHERE notification_id = $1 AND channel = 'EMAIL'`,
				normal).Scan(&next)
		})
		if want := time.Date(2026, 10, 23, 6, 0, 0, 0, prefs.Local); !next.Equal(want) {
			t.Fatalf("hoãn tới %v, muốn %v", next, want)
		}
		if mailCount(t, mailAPI, email2) != 1 {
			t.Fatal("hộp thư phải có đúng thư ưu tiên cao")
		}
	})

	t.Run("kênh bị tắt lúc gửi thì bỏ qua", func(t *testing.T) {
		n := insert(t, u3, "THUONG", now)
		if _, err := ps.Update(ctx, tenant, u3, 1, prefs.Input{QuietHoursEnabled: &no,
			Categories: []prefs.CategoryPref{{Category: "NHIEM_VU", Channels: []string{"IN_APP"}}}}); err != nil {
			t.Fatal(err)
		}
		run(t, "SMS")
		if d := deliveries(t, n)["SMS"]; d.status != "BO_QUA" || d.lastErr != "CHANNEL_OFF" {
			t.Fatalf("%+v", d)
		}
		if d := deliveries(t, insert(t, u3, "THUONG", now)); len(d) != 1 {
			t.Fatalf("kênh đã tắt không được xếp hàng: %+v", d)
		}
	})

	t.Run("không có email, số điện thoại dạng rõ trong CSDL và nhật ký", func(t *testing.T) {
		admin, err := pgx.Connect(ctx, adminDSN)
		if err != nil {
			t.Fatal(err)
		}
		defer admin.Close(ctx)
		var dump string
		if err := admin.QueryRow(ctx, `SELECT coalesce(string_agg(t::text, ' '), '') FROM (
			SELECT d::text AS t FROM notify.delivery d UNION ALL SELECT n::text FROM notify.notification n
			UNION ALL SELECT p::text FROM notify.push_device p) x`).Scan(&dump); err != nil {
			t.Fatal(err)
		}
		for _, s := range []string{email1, email2, "912345678", "0912 345 678", "987654321", "fcm-tot", "apns-tot"} {
			if strings.Contains(dump, s) || strings.Contains(logs.String(), s) {
				t.Fatalf("lộ dữ liệu cá nhân %q", s)
			}
		}
		var withBidx int
		if err := admin.QueryRow(ctx, `SELECT count(*) FROM notify.delivery WHERE channel IN ('EMAIL', 'SMS')
			AND status = 'DA_GUI' AND address_bidx IS NOT NULL`).Scan(&withBidx); err != nil {
			t.Fatal(err)
		}
		if withBidx < 3 {
			t.Fatalf("việc đã gửi phải có chỉ mục mù địa chỉ, có %d", withBidx)
		}
	})
}
