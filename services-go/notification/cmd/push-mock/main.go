// Lệnh push-mock chạy bản giả lập FCM HTTP v1 (HTTP) và APNs (HTTP/2 qua TLS tự ký) cho môi trường dev
// (YC-PT-13, MOB-09). Khi khởi động, lệnh sinh tài khoản dịch vụ FCM giả, khoá .p8 APNs giả và kho tin cậy
// vào thư mục -out (mặc định ngoài repo), rồi in các biến môi trường cần đặt cho notification-service.
//
//	go run ./cmd/push-mock -out /tmp/push-mock
package main

import (
	"crypto/tls"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"mission/services-go/notification/internal/pushmock"
)

func main() {
	fcmAddr := flag.String("fcm-addr", ":35190", "địa chỉ nghe của FCM giả (HTTP)")
	apnsAddr := flag.String("apns-addr", ":35191", "địa chỉ nghe của APNs giả (HTTP/2 qua TLS)")
	host := flag.String("host", "localhost", "tên máy notification-service dùng để gọi bản giả lập")
	project := flag.String("project", "mission-dev", "project_id của tài khoản dịch vụ FCM giả")
	topic := flag.String("topic", "vn.mission.app", "bundle id ứng dụng iOS (apns-topic)")
	out := flag.String("out", os.TempDir()+"/push-mock", "thư mục ghi khoá giả và kho tin cậy (không đặt trong repo)")
	flag.Parse()

	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	port := (*fcmAddr)[strings.LastIndex(*fcmAddr, ":")+1:]
	fcmURL := "http://" + *host + ":" + port
	cred, err := pushmock.WriteDevCredentials(*out, fcmURL, *project)
	if err != nil {
		log.Error("không sinh được khoá giả", "err", err)
		os.Exit(1)
	}
	cert, err := pushmock.SelfSignedTLS(*out, []string{*host, "localhost", "127.0.0.1", "push-mock"})
	if err != nil {
		log.Error("không sinh được chứng thư TLS", "err", err)
		os.Exit(1)
	}
	srv := &pushmock.Server{ProjectID: *project, Topic: *topic, FCMKey: cred.FCMPublic, APNsKey: cred.APNsPublic,
		RequireHTTP2: true}
	h := srv.Handler()

	fcm := &http.Server{Addr: *fcmAddr, Handler: h, ReadHeaderTimeout: 5 * time.Second}
	apns := &http.Server{Addr: *apnsAddr, Handler: h, ReadHeaderTimeout: 5 * time.Second,
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12, NextProtos: []string{"h2"}}}
	go func() {
		if err := fcm.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Error("FCM giả dừng", "err", err)
			os.Exit(1)
		}
	}()
	go func() {
		if err := apns.ListenAndServeTLS("", ""); err != nil && err != http.ErrServerClosed {
			log.Error("APNs giả dừng", "err", err)
			os.Exit(1)
		}
	}()
	aport := (*apnsAddr)[strings.LastIndex(*apnsAddr, ":")+1:]
	fmt.Printf("# Đặt cho notification-service (NOTIFY_CHANNELS có PUSH):\n"+
		"FCM_SERVICE_ACCOUNT_FILE=%s\nFCM_BASE_URL=%s\n"+
		"APNS_KEY_FILE=%s\nAPNS_KEY_ID=MOCKKEY001\nAPNS_TEAM_ID=MOCKTEAM01\nAPNS_TOPIC=%s\nAPNS_BASE_URL=https://%s:%s\n"+
		"SSL_CERT_FILE=%s/ca-bundle.pem\n"+
		"# Xem thông điệp đã nhận: curl %s/_mock/messages\n",
		cred.ServiceAccountFile, fcmURL, cred.APNsKeyFile, *topic, *host, aport, *out, fcmURL)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	_ = fcm.Close()
	_ = apns.Close()
}
