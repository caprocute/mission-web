// Lệnh audit-verify (INF-24): công cụ độc lập cho kiểm toán viên kiểm kho lưu trữ nhật ký kiểm toán. Chỉ cần tệp
// khoá công khai (audit-archiver khoa-cong-khai) và tài khoản S3 chỉ đọc; không cần OpenBao hay Kafka.
//
//	AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=… audit-verify \
//	  -endpoint rgw-bk.example:443 -bucket audit-cell-g4 -cell cell-g4 -topic sys.audit.v1 \
//	  -khoa-cong-khai audit-archiver.pub.json [-tu 2026-10-01T00:00:00Z] [-den …] [-bo-qua-du-lieu]
//
// In báo cáo JSON ra stdout. Mã thoát 0 khi đạt, 1 khi phát hiện sai lệch, 2 khi lỗi chạy.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"mission/services-go/audit-archiver/internal/objstore"
	"mission/services-go/audit-archiver/internal/transit"
	"mission/services-go/audit-archiver/internal/verify"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	fs := flag.NewFlagSet("audit-verify", flag.ContinueOnError)
	endpoint := fs.String("endpoint", "", "địa chỉ S3 host:cổng")
	insecure := fs.Bool("khong-tls", false, "kết nối S3 không TLS (chỉ dùng ở môi trường thử)")
	region := fs.String("region", "", "vùng S3")
	bucket := fs.String("bucket", "", "bucket lưu trữ")
	cell := fs.String("cell", "", "mã cell")
	topic := fs.String("topic", "sys.audit.v1", "topic")
	pub := fs.String("khoa-cong-khai", "", "tệp khoá công khai")
	from := fs.String("tu", "", "chỉ kiểm lô từ thời điểm này (RFC 3339); bỏ trống là kiểm toàn bộ chuỗi")
	to := fs.String("den", "", "chỉ kiểm lô tới thời điểm này (RFC 3339)")
	skip := fs.Bool("bo-qua-du-lieu", false, "chỉ kiểm chữ ký và chuỗi, không tải tệp Parquet")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	fail := func(err error) int { fmt.Fprintln(os.Stderr, "audit-verify:", err); return 2 }
	if *endpoint == "" || *bucket == "" || *cell == "" || *pub == "" {
		return fail(fmt.Errorf("cần -endpoint, -bucket, -cell, -khoa-cong-khai"))
	}
	kb, err := os.ReadFile(*pub)
	if err != nil {
		return fail(err)
	}
	keys, err := transit.DecodePublicKeys(kb)
	if err != nil {
		return fail(err)
	}
	// Công cụ chỉ đọc: Mode và Retention chỉ cần hợp lệ để tạo client, không ghi gì.
	st, err := objstore.New(objstore.Config{Endpoint: *endpoint, Secure: !*insecure, Region: *region, Bucket: *bucket,
		AccessKey: os.Getenv("AWS_ACCESS_KEY_ID"), SecretKey: os.Getenv("AWS_SECRET_ACCESS_KEY"), Retention: time.Hour})
	if err != nil {
		return fail(err)
	}
	o := verify.Options{Cell: *cell, Topic: *topic, Keys: keys, SkipData: *skip}
	if *from != "" {
		if o.From, err = time.Parse(time.RFC3339, *from); err != nil {
			return fail(fmt.Errorf("-tu: %w", err))
		}
	}
	if *to != "" {
		if o.To, err = time.Parse(time.RFC3339, *to); err != nil {
			return fail(fmt.Errorf("-den: %w", err))
		}
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	rep, err := verify.Run(ctx, st, o)
	if err != nil {
		return fail(err)
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(rep)
	if !rep.OK() {
		fmt.Fprintf(os.Stderr, "audit-verify: KHÔNG ĐẠT, %d lỗi\n", len(rep.Loi))
		return 1
	}
	fmt.Fprintf(os.Stderr, "audit-verify: đạt, %d tệp, %d bản ghi\n", rep.SoTep, rep.SoBanGhi)
	return 0
}
