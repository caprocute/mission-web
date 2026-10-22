package main

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"mission/services-go/notification/internal/channel"
	"mission/services-go/notification/internal/directory"
	"mission/services-go/notification/internal/ratelimit"
)

// Kênh ngoài (DV-12-03) dựng từ biến môi trường. Địa chỉ cổng, tài khoản và khoá của từng kênh chỉ nằm trong cấu
// hình triển khai (bí mật gắn từ OpenBao thành biến môi trường hoặc tệp), nên đổi sang cổng thật chỉ đổi cấu hình.

func env(k string) string { return strings.TrimSpace(os.Getenv(k)) }

// externalChannels đọc NOTIFY_CHANNELS (ví dụ "EMAIL,SMS,PUSH"); trống thì chỉ có thông báo trong ứng dụng.
func externalChannels() []string {
	var out []string
	for _, c := range strings.Split(env("NOTIFY_CHANNELS"), ",") {
		switch c = strings.ToUpper(strings.TrimSpace(c)); c {
		case channel.Email, channel.SMS, channel.Push:
			out = append(out, c)
		}
	}
	return out
}

func buildSender(ch string, hc *http.Client) (channel.Sender, error) {
	switch ch {
	case channel.Email:
		if env("SMTP_ADDR") == "" || env("SMTP_FROM") == "" {
			return nil, fmt.Errorf("kênh EMAIL cần SMTP_ADDR và SMTP_FROM")
		}
		mode := env("SMTP_TLS")
		if mode == "" {
			mode = "starttls"
		}
		return channel.SMTP{Addr: env("SMTP_ADDR"), Host: env("SMTP_HOST"), From: env("SMTP_FROM"),
			User: env("SMTP_USER"), Password: os.Getenv("SMTP_PASSWORD"), TLSMode: mode,
			TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}, nil
	case channel.SMS:
		if env("SMS_BASE_URL") == "" {
			return nil, fmt.Errorf("kênh SMS cần SMS_BASE_URL")
		}
		return channel.ViettelSMS{HTTP: hc, BaseURL: env("SMS_BASE_URL"), APIKey: os.Getenv("SMS_API_KEY"),
			Brandname: env("SMS_BRANDNAME"), ASCII: env("SMS_ASCII") != "false"}, nil
	case channel.Push:
		if env("PUSH_MODE") == "internal" {
			if env("PUSH_INTERNAL_URL") == "" {
				return nil, fmt.Errorf("PUSH_MODE=internal cần PUSH_INTERNAL_URL")
			}
			return channel.PushRouter{Internal: channel.InternalPush{HTTP: hc, BaseURL: env("PUSH_INTERNAL_URL"),
				APIKey: os.Getenv("PUSH_INTERNAL_API_KEY")}}, nil
		}
		var r channel.PushRouter
		if f := env("FCM_SERVICE_ACCOUNT_FILE"); f != "" {
			b, err := os.ReadFile(f)
			if err != nil {
				return nil, fmt.Errorf("không đọc được FCM_SERVICE_ACCOUNT_FILE")
			}
			var sa channel.ServiceAccount
			if err := json.Unmarshal(b, &sa); err != nil || sa.ProjectID == "" || sa.TokenURI == "" {
				return nil, fmt.Errorf("FCM_SERVICE_ACCOUNT_FILE không hợp lệ")
			}
			r.FCM = &channel.FCM{HTTP: hc, BaseURL: env("FCM_BASE_URL"), Account: sa}
		}
		if f := env("APNS_KEY_FILE"); f != "" {
			b, err := os.ReadFile(f)
			if err != nil {
				return nil, fmt.Errorf("không đọc được APNS_KEY_FILE")
			}
			key, err := channel.ParsePrivateKey(string(b))
			if err != nil {
				return nil, fmt.Errorf("APNS_KEY_FILE không hợp lệ")
			}
			r.APNS = &channel.APNs{HTTP: hc, BaseURL: env("APNS_BASE_URL"), TeamID: env("APNS_TEAM_ID"),
				KeyID: env("APNS_KEY_ID"), Key: key, Topic: env("APNS_TOPIC")}
		}
		if r.FCM == nil && r.APNS == nil {
			return nil, fmt.Errorf("kênh PUSH cần FCM_SERVICE_ACCOUNT_FILE, APNS_KEY_FILE hoặc PUSH_MODE=internal")
		}
		return r, nil
	}
	return nil, fmt.Errorf("kênh không hỗ trợ: %s", ch)
}

func buildDirectory(hc *http.Client) (directory.Directory, error) {
	if env("KEYCLOAK_ADMIN_URL") == "" {
		return nil, fmt.Errorf("kênh EMAIL, SMS cần KEYCLOAK_ADMIN_URL để tra địa chỉ liên lạc")
	}
	return &directory.Keycloak{HTTP: hc, BaseURL: env("KEYCLOAK_ADMIN_URL"), Realm: envDefault("KEYCLOAK_REALM", "mission"),
		ClientID: env("KEYCLOAK_CLIENT_ID"), ClientSecret: os.Getenv("KEYCLOAK_CLIENT_SECRET"),
		PhoneAttr: env("KEYCLOAK_PHONE_ATTR")}, nil
}

func envDefault(k, def string) string {
	if v := env(k); v != "" {
		return v
	}
	return def
}

// concurrency là bulkhead mặc định của từng kênh (suy luận), đổi bằng NOTIFY_CONCURRENCY_<KÊNH>.
func concurrency(ch string) int {
	def := map[string]int{channel.Email: 8, channel.SMS: 4, channel.Push: 16}[ch]
	return envInt("NOTIFY_CONCURRENCY_"+ch, def)
}

func envInt(k string, def int) int {
	var n int
	if _, err := fmt.Sscanf(env(k), "%d", &n); err == nil && n > 0 {
		return n
	}
	return def
}

// bulkLimits là giới hạn mỗi 60 giây của đợt hàng loạt theo kênh, đổi bằng BULK_RATE_<KÊNH>. Mặc định là suy luận
// (TL-01 không ghi con số): SMS 6.000, EMAIL 30.000, PUSH 120.000 lần mỗi phút.
func bulkLimits() ratelimit.Limits {
	return ratelimit.Limits{Window: time.Minute, Per: map[string]int{
		channel.SMS:   envInt("BULK_RATE_SMS", 6000),
		channel.Email: envInt("BULK_RATE_EMAIL", 30000),
		channel.Push:  envInt("BULK_RATE_PUSH", 120000),
	}}
}

func httpClient() *http.Client { return &http.Client{Timeout: 10 * time.Second} }
