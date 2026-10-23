// Package pushmock là bản giả lập FCM HTTP v1 và APNs HTTP/2 cho môi trường dev và kiểm thử (YC-PT-13, MOB-09).
// Bản giả lập kiểm đúng các điều kiện mà dịch vụ thật kiểm (JWT của tài khoản dịch vụ, access token, bearer ES256,
// apns-topic, HTTP/2, kích thước tải), trả lỗi đúng định dạng của từng nhà cung cấp, và ghi lại thông điệp đã nhận
// để kiểm thử đầu cuối và ứng dụng di động đọc qua `GET /_mock/messages`.
//
// Mã thiết bị điều khiển kết quả: bắt đầu bằng "unregistered" thì FCM trả 404 UNREGISTERED, APNs trả 410
// Unregistered; bắt đầu bằng "unavailable" thì trả 503; APNs với mã bắt đầu bằng "bad" trả 400 BadDeviceToken.
package pushmock

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Giới hạn tải của nhà cung cấp: APNs 4 KB cho thông báo thường, FCM 4 KB cho phần dữ liệu.
const maxPayload = 4096

// Received là một thông điệp bản giả lập đã nhận thành công.
type Received struct {
	ID       string    `json:"id"`
	Platform string    `json:"platform"` // FCM hoặc APNS
	Token    string    `json:"token"`
	Title    string    `json:"title"`
	Body     string    `json:"body"`
	DeepLink string    `json:"deepLink"`
	Priority string    `json:"priority"` // FCM: normal/high; APNs: 5/10
	At       time.Time `json:"at"`
	// NghiNgoC2 bật khi tiêu đề hoặc nội dung giống dữ liệu C2 (số định danh, điện thoại, thư điện tử): nội dung
	// đẩy hiện trên màn hình khoá nên không được chứa C2 (TL-03 schema notify).
	NghiNgoC2 bool `json:"nghiNgoC2"`
}

// Server là bản giả lập. FCMKey và APNsKey là khoá công khai dùng để kiểm chữ ký; để trống thì chỉ kiểm cấu trúc.
type Server struct {
	ProjectID    string
	Topic        string
	FCMKey       *rsa.PublicKey
	APNsKey      *ecdsa.PublicKey
	RequireHTTP2 bool
	Now          func() time.Time

	mu     sync.Mutex
	seq    int
	tokens map[string]time.Time
	msgs   []Received
}

var c2Pattern = regexp.MustCompile(`\b\d{9,12}\b|\b0\d{2,3}[ .]?\d{3}[ .]?\d{3,4}\b|[\w.+-]+@[\w-]+\.[\w.]+`)

func (s *Server) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Server) next(prefix string) string {
	s.seq++
	return prefix + strconv.Itoa(s.seq)
}

// Handler trả bộ định tuyến HTTP của bản giả lập.
func (s *Server) Handler() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("POST /token", s.token)
	m.HandleFunc("POST /v1/projects/{project}/messages:send", s.fcmSend)
	m.HandleFunc("POST /3/device/{token}", s.apnsSend)
	m.HandleFunc("GET /_mock/messages", s.list)
	m.HandleFunc("DELETE /_mock/messages", s.reset)
	m.HandleFunc("GET /_mock/health", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	return m
}

// Messages trả bản sao các thông điệp đã nhận.
func (s *Server) Messages() []Received {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Received(nil), s.msgs...)
}

func (s *Server) record(r Received) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	r.ID = s.next(strings.ToLower(r.Platform) + "-")
	r.At = s.now()
	r.NghiNgoC2 = c2Pattern.MatchString(r.Title) || c2Pattern.MatchString(r.Body)
	s.msgs = append(s.msgs, r)
	return r.ID
}

func (s *Server) list(w http.ResponseWriter, r *http.Request) {
	out := s.Messages()
	if t := r.URL.Query().Get("token"); t != "" {
		f := out[:0]
		for _, m := range out {
			if m.Token == t {
				f = append(f, m)
			}
		}
		out = f
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (s *Server) reset(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	s.msgs = nil
	s.mu.Unlock()
	w.WriteHeader(http.StatusNoContent)
}

// token giả lập điểm cấp token OAuth 2.0 của Google cho luồng JWT bearer (RFC 7523).
func (s *Server) token(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil || r.PostForm.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unsupported_grant_type"})
		return
	}
	claims, err := parseJWT(r.PostForm.Get("assertion"), "RS256", s.verifyRS256)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_grant", "error_description": err.Error()})
		return
	}
	if claims["scope"] != "https://www.googleapis.com/auth/firebase.messaging" || claims["iss"] == "" || claims["aud"] == nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_scope"})
		return
	}
	exp, _ := claims["exp"].(float64)
	if time.Unix(int64(exp), 0).Before(s.now()) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_grant", "error_description": "assertion hết hạn"})
		return
	}
	s.mu.Lock()
	if s.tokens == nil {
		s.tokens = map[string]time.Time{}
	}
	tok := s.next("mock-access-")
	s.tokens[tok] = s.now().Add(time.Hour)
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"access_token": tok, "expires_in": 3600, "token_type": "Bearer"})
}

// RevokeAccessTokens làm mọi access token đã cấp hết hiệu lực (kiểm thử nhánh 401 của FCM).
func (s *Server) RevokeAccessTokens() {
	s.mu.Lock()
	s.tokens = map[string]time.Time{}
	s.mu.Unlock()
}

func fcmError(w http.ResponseWriter, code int, status, fcmCode, msg string) {
	e := map[string]any{"code": code, "message": msg, "status": status}
	if fcmCode != "" {
		e["details"] = []map[string]string{{"@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode": fcmCode}}
	}
	writeJSON(w, code, map[string]any{"error": e})
}

func (s *Server) fcmSend(w http.ResponseWriter, r *http.Request) {
	auth := r.Header.Get("Authorization")
	s.mu.Lock()
	exp, ok := s.tokens[strings.TrimPrefix(auth, "Bearer ")]
	s.mu.Unlock()
	if !strings.HasPrefix(auth, "Bearer ") || !ok || exp.Before(s.now()) {
		fcmError(w, http.StatusUnauthorized, "UNAUTHENTICATED", "", "Request had invalid authentication credentials.")
		return
	}
	if s.ProjectID != "" && r.PathValue("project") != s.ProjectID {
		fcmError(w, http.StatusForbidden, "PERMISSION_DENIED", "SENDER_ID_MISMATCH", "Project không khớp tài khoản dịch vụ.")
		return
	}
	raw, _ := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	var req struct {
		Message struct {
			Token        string            `json:"token"`
			Notification map[string]string `json:"notification"`
			Data         map[string]string `json:"data"`
			Android      struct {
				Priority string `json:"priority"`
			} `json:"android"`
		} `json:"message"`
	}
	if err := json.Unmarshal(raw, &req); err != nil {
		// Giá trị của `data` không phải chuỗi cũng rơi vào đây, giống FCM thật.
		fcmError(w, http.StatusBadRequest, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "Tải không hợp lệ: "+err.Error())
		return
	}
	msg := req.Message
	switch {
	case msg.Token == "":
		fcmError(w, http.StatusBadRequest, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "Thiếu message.token.")
		return
	case msg.Android.Priority != "" && msg.Android.Priority != "normal" && msg.Android.Priority != "high":
		fcmError(w, http.StatusBadRequest, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "android.priority không hợp lệ.")
		return
	case len(raw) > maxPayload:
		fcmError(w, http.StatusBadRequest, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "Tải vượt 4096 byte.")
		return
	case strings.HasPrefix(msg.Token, "unregistered"):
		fcmError(w, http.StatusNotFound, "NOT_FOUND", "UNREGISTERED", "Requested entity was not found.")
		return
	case strings.HasPrefix(msg.Token, "unavailable"):
		fcmError(w, http.StatusServiceUnavailable, "UNAVAILABLE", "UNAVAILABLE", "The service is currently unavailable.")
		return
	}
	id := s.record(Received{Platform: "FCM", Token: msg.Token, Title: msg.Notification["title"],
		Body: msg.Notification["body"], DeepLink: msg.Data["deepLink"], Priority: msg.Android.Priority})
	writeJSON(w, http.StatusOK, map[string]string{"name": "projects/" + r.PathValue("project") + "/messages/" + id})
}

func apnsError(w http.ResponseWriter, code int, reason string) {
	w.Header().Set("apns-id", "00000000-0000-0000-0000-000000000000")
	body := map[string]any{"reason": reason}
	if code == http.StatusGone {
		body["timestamp"] = time.Now().UnixMilli()
	}
	writeJSON(w, code, body)
}

func (s *Server) apnsSend(w http.ResponseWriter, r *http.Request) {
	if s.RequireHTTP2 && r.ProtoMajor != 2 {
		apnsError(w, http.StatusBadRequest, "BadRequest")
		return
	}
	auth := r.Header.Get("authorization")
	if !strings.HasPrefix(strings.ToLower(auth), "bearer ") {
		apnsError(w, http.StatusForbidden, "MissingProviderToken")
		return
	}
	claims, err := parseJWT(auth[len("bearer "):], "ES256", s.verifyES256)
	if err != nil || claims["iss"] == "" || claims["_kid"] == "" {
		apnsError(w, http.StatusForbidden, "InvalidProviderToken")
		return
	}
	if iat, _ := claims["iat"].(float64); s.now().Sub(time.Unix(int64(iat), 0)) > time.Hour {
		apnsError(w, http.StatusForbidden, "ExpiredProviderToken")
		return
	}
	topic := r.Header.Get("apns-topic")
	if topic == "" {
		apnsError(w, http.StatusBadRequest, "MissingTopic")
		return
	}
	if s.Topic != "" && topic != s.Topic {
		apnsError(w, http.StatusBadRequest, "DeviceTokenNotForTopic")
		return
	}
	if pt := r.Header.Get("apns-push-type"); pt != "alert" && pt != "background" {
		apnsError(w, http.StatusBadRequest, "InvalidPushType")
		return
	}
	prio := r.Header.Get("apns-priority")
	if prio != "" && prio != "5" && prio != "10" {
		apnsError(w, http.StatusBadRequest, "BadPriority")
		return
	}
	raw, _ := io.ReadAll(io.LimitReader(r.Body, maxPayload+1))
	if len(raw) > maxPayload {
		apnsError(w, http.StatusRequestEntityTooLarge, "PayloadTooLarge")
		return
	}
	var p struct {
		Aps struct {
			Alert struct {
				Title string `json:"title"`
				Body  string `json:"body"`
			} `json:"alert"`
		} `json:"aps"`
		DeepLink string `json:"deepLink"`
	}
	if err := json.Unmarshal(raw, &p); err != nil {
		apnsError(w, http.StatusBadRequest, "BadPayload")
		return
	}
	tok := r.PathValue("token")
	switch {
	case strings.HasPrefix(tok, "unregistered"):
		apnsError(w, http.StatusGone, "Unregistered")
		return
	case strings.HasPrefix(tok, "bad"):
		apnsError(w, http.StatusBadRequest, "BadDeviceToken")
		return
	case strings.HasPrefix(tok, "unavailable"):
		apnsError(w, http.StatusServiceUnavailable, "ServiceUnavailable")
		return
	}
	id := s.record(Received{Platform: "APNS", Token: tok, Title: p.Aps.Alert.Title, Body: p.Aps.Alert.Body,
		DeepLink: p.DeepLink, Priority: prio})
	w.Header().Set("apns-id", id)
	w.WriteHeader(http.StatusOK)
}

func (s *Server) verifyRS256(signed, sig []byte) bool {
	if s.FCMKey == nil {
		return true
	}
	sum := sha256.Sum256(signed)
	return rsa.VerifyPKCS1v15(s.FCMKey, crypto.SHA256, sum[:], sig) == nil
}

func (s *Server) verifyES256(signed, sig []byte) bool {
	if s.APNsKey == nil {
		return true
	}
	if len(sig) != 64 {
		return false
	}
	sum := sha256.Sum256(signed)
	return ecdsa.Verify(s.APNsKey, sum[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:]))
}

// parseJWT tách và kiểm JWT dạng gọn; trả claims, thêm "_kid" từ header.
func parseJWT(tok, alg string, verify func(signed, sig []byte) bool) (map[string]any, error) {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("JWT không đủ ba phần")
	}
	var hdr map[string]any
	if err := decodePart(parts[0], &hdr); err != nil || hdr["alg"] != alg {
		return nil, fmt.Errorf("header JWT không hợp lệ hoặc alg khác %s", alg)
	}
	var claims map[string]any
	if err := decodePart(parts[1], &claims); err != nil {
		return nil, fmt.Errorf("claims JWT không hợp lệ")
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || !verify([]byte(parts[0]+"."+parts[1]), sig) {
		return nil, fmt.Errorf("chữ ký JWT không hợp lệ")
	}
	kid, _ := hdr["kid"].(string)
	claims["_kid"] = kid
	if v, ok := claims["iss"].(string); !ok || v == "" {
		claims["iss"] = ""
	}
	return claims, nil
}

func decodePart(p string, v any) error {
	b, err := base64.RawURLEncoding.DecodeString(p)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
