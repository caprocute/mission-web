package pushmock_test

// Kiểm thử MOB-09 phía máy chủ: kênh đẩy FCM HTTP v1 và APNs HTTP/2 của notification-service gửi tới bản giả
// lập, bản giả lập kiểm chữ ký JWT, access token, HTTP/2, apns-topic và trả lỗi đúng định dạng nhà cung cấp.

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"mission/services-go/notification/internal/channel"
	"mission/services-go/notification/internal/pushmock"
)

type env struct {
	mock      *pushmock.Server
	fcm       *channel.FCM
	apns      *channel.APNs
	tokenHits *atomic.Int32
	base      string
}

func setup(t *testing.T) env {
	t.Helper()
	dir := t.TempDir()
	mock := &pushmock.Server{ProjectID: "mission-test", Topic: "vn.mission.app", RequireHTTP2: true}
	var hits atomic.Int32
	h := mock.Handler()
	counted := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			hits.Add(1)
		}
		h.ServeHTTP(w, r)
	})
	fcmSrv := httptest.NewServer(counted)
	t.Cleanup(fcmSrv.Close)
	cred, err := pushmock.WriteDevCredentials(dir, fcmSrv.URL, "mission-test")
	if err != nil {
		t.Fatal(err)
	}
	mock.FCMKey, mock.APNsKey = cred.FCMPublic, cred.APNsPublic

	apnsSrv := httptest.NewUnstartedServer(h)
	apnsSrv.EnableHTTP2 = true
	apnsSrv.StartTLS()
	t.Cleanup(apnsSrv.Close)

	var sa channel.ServiceAccount
	b, _ := os.ReadFile(cred.ServiceAccountFile)
	if err := json.Unmarshal(b, &sa); err != nil {
		t.Fatal(err)
	}
	p8, _ := os.ReadFile(cred.APNsKeyFile)
	key, err := channel.ParsePrivateKey(string(p8))
	if err != nil {
		t.Fatal(err)
	}
	return env{
		mock:      mock,
		fcm:       &channel.FCM{HTTP: fcmSrv.Client(), BaseURL: fcmSrv.URL, Account: sa},
		apns:      &channel.APNs{HTTP: apnsSrv.Client(), BaseURL: apnsSrv.URL, TeamID: "MOCKTEAM01", KeyID: "MOCKKEY001", Key: key, Topic: "vn.mission.app"},
		tokenHits: &hits,
		base:      fcmSrv.URL,
	}
}

func msg(platform, to string) channel.Message {
	return channel.Message{Platform: platform, To: to, Subject: "Việc mới được giao",
		Body: "Đồng chí được giao một nhiệm vụ mới.", DeepLink: "/viec-cua-toi/018f0000-0000-7000-8000-000000000001", Key: "d-1"}
}

func TestFCMGuiThanhCongVaDungLaiAccessToken(t *testing.T) {
	e := setup(t)
	m := msg("FCM", "fcm-tot")
	m.High = true
	id, err := e.fcm.Send(context.Background(), m)
	if err != nil || !strings.HasPrefix(id, "projects/mission-test/messages/fcm-") {
		t.Fatalf("id=%q err=%v", id, err)
	}
	if _, err := e.fcm.Send(context.Background(), msg("FCM", "fcm-tot-2")); err != nil {
		t.Fatal(err)
	}
	if n := e.tokenHits.Load(); n != 1 {
		t.Fatalf("access token phải được dùng lại, số lần xin token = %d", n)
	}
	got := e.mock.Messages()
	if len(got) != 2 || got[0].DeepLink != m.DeepLink || got[0].Priority != "high" || got[0].Title != m.Subject || got[1].Priority != "normal" {
		t.Fatalf("thông điệp ghi nhận sai: %+v", got)
	}
	if got[0].NghiNgoC2 {
		t.Fatal("nội dung mẫu không chứa C2 nhưng bị gắn cờ")
	}
}

func TestFCMLamMoiTokenKhi401(t *testing.T) {
	e := setup(t)
	if _, err := e.fcm.Send(context.Background(), msg("FCM", "fcm-tot")); err != nil {
		t.Fatal(err)
	}
	e.mock.RevokeAccessTokens()
	_, err := e.fcm.Send(context.Background(), msg("FCM", "fcm-tot"))
	var tr channel.Transient
	if !errors.As(err, &tr) || tr.Code != "FCM_AUTH" {
		t.Fatalf("401 phải là lỗi tạm thời FCM_AUTH, nhận %v", err)
	}
	if _, err := e.fcm.Send(context.Background(), msg("FCM", "fcm-tot")); err != nil {
		t.Fatalf("lần thử lại phải xin token mới và gửi được: %v", err)
	}
	if n := e.tokenHits.Load(); n != 2 {
		t.Fatalf("số lần xin token = %d, mong 2", n)
	}
}

func TestFCMLoiTheoMaThietBi(t *testing.T) {
	e := setup(t)
	if _, err := e.fcm.Send(context.Background(), msg("FCM", "unregistered-1")); !errors.Is(err, channel.ErrUnregistered) {
		t.Fatalf("UNREGISTERED phải thu hồi thiết bị, nhận %v", err)
	}
	_, err := e.fcm.Send(context.Background(), msg("FCM", "unavailable-1"))
	var tr channel.Transient
	if !errors.As(err, &tr) {
		t.Fatalf("503 phải là lỗi tạm thời, nhận %v", err)
	}
	if len(e.mock.Messages()) != 0 {
		t.Fatal("thông điệp lỗi không được ghi nhận")
	}
}

func TestFCMTuChoiJWTKySai(t *testing.T) {
	e := setup(t)
	other, err := pushmock.WriteDevCredentials(t.TempDir(), e.base, "mission-test")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(other.ServiceAccountFile)
	var sa channel.ServiceAccount
	_ = json.Unmarshal(b, &sa)
	e.fcm.Account.PrivateKey = sa.PrivateKey // khoá khác khoá bản giả lập tin cậy
	if _, err := e.fcm.Send(context.Background(), msg("FCM", "fcm-tot")); !errors.Is(err, channel.ErrPermanent) {
		t.Fatalf("JWT ký bằng khoá lạ phải bị từ chối vĩnh viễn, nhận %v", err)
	}
}

func TestAPNsGuiQuaHTTP2(t *testing.T) {
	e := setup(t)
	m := msg("APNS", "apns-tot")
	m.High = true
	id, err := e.apns.Send(context.Background(), m)
	if err != nil || !strings.HasPrefix(id, "apns-") {
		t.Fatalf("id=%q err=%v", id, err)
	}
	got := e.mock.Messages()
	if len(got) != 1 || got[0].Priority != "10" || got[0].DeepLink != m.DeepLink || got[0].Body != m.Body {
		t.Fatalf("thông điệp ghi nhận sai: %+v", got)
	}
	if _, err := e.apns.Send(context.Background(), msg("APNS", "apns-tot")); err != nil {
		t.Fatal(err)
	}
	if p := e.mock.Messages()[1].Priority; p != "5" {
		t.Fatalf("ưu tiên thường phải là 5, nhận %s", p)
	}
}

func TestAPNsLoiTheoMaThietBiVaTopic(t *testing.T) {
	e := setup(t)
	for _, tok := range []string{"unregistered-1", "bad-token"} {
		if _, err := e.apns.Send(context.Background(), msg("APNS", tok)); !errors.Is(err, channel.ErrUnregistered) {
			t.Fatalf("%s phải thu hồi thiết bị, nhận %v", tok, err)
		}
	}
	e.apns.Topic = "vn.ung-dung.khac"
	if _, err := e.apns.Send(context.Background(), msg("APNS", "apns-tot")); !errors.Is(err, channel.ErrPermanent) {
		t.Fatalf("sai apns-topic phải là lỗi vĩnh viễn, nhận %v", err)
	}
}

func TestAPNsChiNhanHTTP2(t *testing.T) {
	e := setup(t)
	srv := httptest.NewUnstartedServer(e.mock.Handler())
	srv.StartTLS() // không bật HTTP/2
	defer srv.Close()
	e.apns.HTTP = &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}} //nolint:gosec // chỉ trong kiểm thử
	e.apns.BaseURL = srv.URL
	if _, err := e.apns.Send(context.Background(), msg("APNS", "apns-tot")); err == nil {
		t.Fatal("APNs giả phải từ chối HTTP/1.1")
	}
}

func TestPushRouterTheoNenTang(t *testing.T) {
	e := setup(t)
	r := channel.PushRouter{FCM: e.fcm, APNS: e.apns}
	for _, p := range []string{"FCM", "APNS"} {
		if _, err := r.Send(context.Background(), msg(p, strings.ToLower(p)+"-tot")); err != nil {
			t.Fatalf("%s: %v", p, err)
		}
	}
	if _, err := r.Send(context.Background(), msg("WEB_PUSH", "x")); !errors.Is(err, channel.ErrPermanent) {
		t.Fatalf("nền tảng chưa hỗ trợ phải là lỗi vĩnh viễn, nhận %v", err)
	}
	got := e.mock.Messages()
	if len(got) != 2 || got[0].Platform != "FCM" || got[1].Platform != "APNS" {
		t.Fatalf("định tuyến sai: %+v", got)
	}
}

func TestCoNghiNgoC2VaApiXemThongDiep(t *testing.T) {
	e := setup(t)
	m := msg("FCM", "fcm-c2")
	m.Body = "Liên hệ 0912 345 678 hoặc can.bo@example.vn"
	if _, err := e.fcm.Send(context.Background(), m); err != nil {
		t.Fatal(err)
	}
	if _, err := e.fcm.Send(context.Background(), msg("FCM", "fcm-tot")); err != nil {
		t.Fatal(err)
	}
	resp, err := http.Get(e.base + "/_mock/messages?token=fcm-c2")
	if err != nil {
		t.Fatal(err)
	}
	var out struct {
		Items []pushmock.Received `json:"items"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	resp.Body.Close()
	if len(out.Items) != 1 || !out.Items[0].NghiNgoC2 {
		t.Fatalf("lọc theo mã thiết bị hoặc cờ C2 sai: %+v", out.Items)
	}
	req, _ := http.NewRequest(http.MethodDelete, e.base+"/_mock/messages", nil)
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != http.StatusNoContent {
		t.Fatalf("xoá danh sách: %v", err)
	}
	if len(e.mock.Messages()) != 0 {
		t.Fatal("danh sách phải rỗng sau khi xoá")
	}
}
