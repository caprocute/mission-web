// Kiểm thử tích hợp DV-12-05 trên PostgreSQL 17 thật: đăng ký, đăng ký lại cùng mã bởi người khác, huỷ; tuỳ
// chọn tắt nhóm làm bộ định tuyến bỏ kênh đó; hai tenant không thấy thiết bị của nhau; không có mã thiết bị
// dạng rõ trong CSDL; năm thao tác khớp platform.yaml.
package it

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"

	"mission/services-go/notification/internal/api"
	"mission/services-go/notification/internal/contract"
	"mission/services-go/notification/internal/crypt"
	"mission/services-go/notification/internal/prefs"
	"mission/services-go/notification/internal/store"
	"mission/services-go/notification/internal/testdb"
)

func TestDevicesAndPreferences(t *testing.T) {
	if testing.Short() {
		t.Skip("kiểm thử tích hợp cần Docker")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
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
	admin, err := pgx.Connect(ctx, adminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close(ctx)

	kr, _ := crypt.NewLocalKeyring(bytes.Repeat([]byte{9}, 32))
	ps := prefs.Store{Pool: pool, Field: crypt.Field{Keys: kr}}
	srv := httptest.NewServer(api.Handler(api.Deps{Inbox: store.Reader{Pool: pool}, Prefs: ps}))
	defer srv.Close()
	spec, err := contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi"))
	if err != nil {
		t.Fatal(err)
	}

	type resp struct {
		code int
		body []byte
		ct   string
		etag string
	}
	call := func(t *testing.T, method, path, tok string, body any, hdr map[string]string) resp {
		t.Helper()
		var rd io.Reader
		if body != nil {
			b, _ := json.Marshal(body)
			rd = bytes.NewReader(b)
		}
		rq, _ := http.NewRequest(method, srv.URL+"/api/v1"+path, rd)
		rq.Header.Set("Authorization", "Bearer "+tok)
		if body != nil {
			rq.Header.Set("Content-Type", "application/json")
		}
		if method != "GET" {
			rq.Header.Set("Idempotency-Key", uuid.NewString())
		}
		for k, v := range hdr {
			rq.Header.Set(k, v)
		}
		rs, err := http.DefaultClient.Do(rq)
		if err != nil {
			t.Fatal(err)
		}
		defer rs.Body.Close()
		b, _ := io.ReadAll(rs.Body)
		return resp{rs.StatusCode, b, rs.Header.Get("Content-Type"), rs.Header.Get("ETag")}
	}
	check := func(t *testing.T, r resp, path, method string, want int) {
		t.Helper()
		if r.code != want {
			t.Fatalf("%s %s trả %d, cần %d: %s", method, path, r.code, want, r.body)
		}
		if err := spec.Validate("modules/platform.yaml", path, method, r.code, r.ct, r.body); err != nil {
			t.Fatal(err)
		}
	}

	const token = "fcm-token-0123456789-abcdefghijklmnop"
	tokA1, tokA2, tokB1 := tokenFor(tenantA, user1), tokenFor(tenantA, user2), tokenFor(tenantB, user1)
	var dev1 prefs.Device

	t.Run("đăng ký, làm mới, chuyển chủ và huỷ thiết bị", func(t *testing.T) {
		r := call(t, "POST", "/notifications/devices", tokA1, map[string]any{"platform": "FCM", "pushToken": token,
			"deviceName": "Điện thoại công vụ", "appVersion": "1.0.0"}, nil)
		check(t, r, "/notifications/devices", "post", 201)
		_ = json.Unmarshal(r.body, &dev1)
		if bytes.Contains(r.body, []byte(token)) {
			t.Fatal("phản hồi không được chứa mã thiết bị")
		}
		r = call(t, "POST", "/notifications/devices", tokA1, map[string]any{"platform": "FCM", "pushToken": token,
			"deviceName": "Điện thoại công vụ", "appVersion": "1.0.1"}, nil)
		var again prefs.Device
		_ = json.Unmarshal(r.body, &again)
		if again.ID != dev1.ID || *again.AppVersion != "1.0.1" {
			t.Fatalf("đăng ký lại cùng mã phải làm mới thiết bị cũ: %+v", again)
		}
		r = call(t, "GET", "/notifications/devices", tokA1, nil, nil)
		check(t, r, "/notifications/devices", "get", 200)
		if bytes.Contains(r.body, []byte(token)) || !bytes.Contains(r.body, []byte(dev1.ID)) {
			t.Fatalf("danh sách thiết bị sai hoặc lộ mã: %s", r.body)
		}
		// Tenant B không thấy thiết bị của tenant A.
		r = call(t, "GET", "/notifications/devices", tokB1, nil, nil)
		if bytes.Contains(r.body, []byte(dev1.ID)) {
			t.Fatal("tenant khác không được thấy thiết bị")
		}
		// user2 đăng ký cùng mã: thiết bị chuyển chủ, bản của user1 bị thu hồi.
		r = call(t, "POST", "/notifications/devices", tokA2, map[string]any{"platform": "FCM", "pushToken": token,
			"deviceName": "Máy tính bảng"}, nil)
		check(t, r, "/notifications/devices", "post", 201)
		var dev2 prefs.Device
		_ = json.Unmarshal(r.body, &dev2)
		if dev2.ID == dev1.ID {
			t.Fatal("chuyển chủ phải tạo bản đăng ký mới")
		}
		if r := call(t, "GET", "/notifications/devices", tokA1, nil, nil); bytes.Contains(r.body, []byte(dev1.ID)) {
			t.Fatal("thiết bị đã chuyển chủ phải bị thu hồi khỏi người cũ")
		}
		// user1 không huỷ được thiết bị của user2; user2 huỷ được, huỷ lần hai trả 404.
		r = call(t, "DELETE", "/notifications/devices/"+dev2.ID, tokA1, nil, nil)
		check(t, r, "/notifications/devices/{deviceId}", "delete", 404)
		r = call(t, "DELETE", "/notifications/devices/"+dev2.ID, tokA2, nil, nil)
		check(t, r, "/notifications/devices/{deviceId}", "delete", 204)
		if r := call(t, "DELETE", "/notifications/devices/"+dev2.ID, tokA2, nil, nil); r.code != 404 {
			t.Fatalf("huỷ lần hai phải trả 404, nhận %d", r.code)
		}
		r = call(t, "POST", "/notifications/devices", tokA1, map[string]any{"platform": "SMS", "pushToken": token, "deviceName": "x"}, nil)
		check(t, r, "/notifications/devices", "post", 422)
	})

	t.Run("không có mã thiết bị dạng rõ trong CSDL", func(t *testing.T) {
		var n int
		if err := admin.QueryRow(ctx, `SELECT count(*) FROM notify.push_device
			WHERE position($1::bytea IN token_enc) > 0 OR device_name LIKE '%' || $2 || '%'`, []byte(token), token).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 0 {
			t.Fatal("mã thiết bị không được lưu dạng rõ")
		}
		var cols int
		_ = admin.QueryRow(ctx, `SELECT count(*) FROM information_schema.columns
			WHERE table_schema = 'notify' AND table_name = 'push_device' AND column_name = 'push_token'`).Scan(&cols)
		if cols != 0 {
			t.Fatal("không được có cột bản rõ")
		}
	})

	t.Run("tuỳ chọn kênh với If-Match và bộ định tuyến", func(t *testing.T) {
		r := call(t, "GET", "/notifications/preferences", tokA1, nil, nil)
		check(t, r, "/notifications/preferences", "get", 200)
		if r.etag != `"0"` {
			t.Fatalf("phiên bản ban đầu phải là \"0\", nhận %s", r.etag)
		}
		in := map[string]any{"quietHoursEnabled": false, "categories": []map[string]any{
			{"category": "NHIEM_VU", "channels": []string{"IN_APP"}},
			{"category": "PHE_DUYET", "channels": []string{"IN_APP", "PUSH", "EMAIL"}}}}
		r = call(t, "PUT", "/notifications/preferences", tokA1, in, nil)
		check(t, r, "/notifications/preferences", "put", 428)
		r = call(t, "PUT", "/notifications/preferences", tokA1, in, map[string]string{"If-Match": `"0"`})
		check(t, r, "/notifications/preferences", "put", 200)
		if r.etag != `"1"` {
			t.Fatalf("phiên bản sau khi lưu phải là \"1\", nhận %s", r.etag)
		}
		r = call(t, "PUT", "/notifications/preferences", tokA1, in, map[string]string{"If-Match": `"0"`})
		check(t, r, "/notifications/preferences", "put", 409)
		bad := map[string]any{"quietHoursEnabled": false, "categories": []map[string]any{{"category": "DANH_GIA", "channels": []string{"PUSH"}}}}
		r = call(t, "PUT", "/notifications/preferences", tokA1, bad, map[string]string{"If-Match": `"1"`})
		check(t, r, "/notifications/preferences", "put", 422)

		// Thiết bị của user1 cho bộ định tuyến.
		call(t, "POST", "/notifications/devices", tokA1, map[string]any{"platform": "APNS", "pushToken": "apns-token-abcdefghijklmnop", "deviceName": "iPhone"}, nil)
		now := time.Date(2026, 10, 22, 10, 0, 0, 0, time.UTC)
		plan, err := ps.Plan(ctx, tenantA, user1, "NV_DUOC_GIAO", "THUONG", now)
		if err != nil {
			t.Fatal(err)
		}
		if fmt.Sprint(plan.Channels) != "[INAPP]" || len(plan.Devices) != 0 {
			t.Fatalf("nhóm nhiệm vụ đã tắt đẩy chỉ còn kênh trong ứng dụng: %+v", plan)
		}
		plan, err = ps.Plan(ctx, tenantA, user1, "OKR_CHO_DUYET", "THUONG", now)
		if err != nil {
			t.Fatal(err)
		}
		if fmt.Sprint(plan.Channels) != "[INAPP EMAIL PUSH]" || len(plan.Devices) != 1 || plan.Devices[0].Token != "apns-token-abcdefghijklmnop" {
			t.Fatalf("nhóm phê duyệt phải gửi đẩy tới thiết bị đã giải mã: %v", plan.Channels)
		}
		if s := fmt.Sprintf("%v %+v %#v", plan.Devices[0], plan.Devices[0], plan.Devices[0]); bytes.Contains([]byte(s), []byte("apns-token")) {
			t.Fatal("in thiết bị không được lộ mã")
		}
		// Bật giờ yên lặng 22:00–06:00: thông báo thường ban đêm chỉ vào ứng dụng, ưu tiên cao vẫn đẩy.
		q := map[string]any{"quietHoursEnabled": true, "quietHoursStart": "22:00", "quietHoursEnd": "06:00", "categories": []map[string]any{}}
		r = call(t, "PUT", "/notifications/preferences", tokA1, q, map[string]string{"If-Match": `"1"`})
		check(t, r, "/notifications/preferences", "put", 200)
		night := time.Date(2026, 10, 22, 23, 30, 0, 0, time.UTC)
		plan, _ = ps.Plan(ctx, tenantA, user1, "OKR_CHO_DUYET", "THUONG", night)
		if fmt.Sprint(plan.Channels) != "[INAPP]" {
			t.Fatalf("giờ yên lặng phải hoãn kênh ngoài: %v", plan.Channels)
		}
		plan, _ = ps.Plan(ctx, tenantA, user1, "OKR_CHO_DUYET", "CAO", night)
		if fmt.Sprint(plan.Channels) != "[INAPP EMAIL PUSH]" {
			t.Fatalf("ưu tiên cao vẫn gửi trong giờ yên lặng: %v", plan.Channels)
		}
		// Tenant B không thấy tuỳ chọn của tenant A.
		if r := call(t, "GET", "/notifications/preferences", tokB1, nil, nil); r.etag != `"0"` {
			t.Fatal("tenant khác phải thấy tuỳ chọn mặc định")
		}
	})

	t.Run("thu hồi thiết bị lâu không dùng", func(t *testing.T) {
		if _, err := admin.Exec(ctx, `UPDATE notify.push_device SET last_seen_at = now() - interval '91 days' WHERE revoked_at IS NULL`); err != nil {
			t.Fatal(err)
		}
		n, err := prefs.RevokeStale(ctx, pool)
		if err != nil || n != 1 {
			t.Fatalf("phải thu hồi 1 thiết bị, thu hồi %d (%v)", n, err)
		}
	})
}

func tokenFor(tenantID, sub string) string { return token(tenantID, sub) }
