// Command ledger-verify kiểm chứng ngoại tuyến một gói xuất bằng chứng sổ cái của Mission (TL-01 "Công cụ kiểm
// chứng độc lập", BR-13-12): tính lại chuỗi băm, dựng lại gốc tenant, kiểm đường đi Merkle tới gốc cell, kiểm
// chữ ký gốc và token RFC 3161, đối chiếu bản neo của bên độc lập. Không gọi mạng, không cần CSDL.
//
// Mã thoát: 0 khi HỢP LỆ, 1 khi phát hiện sai, 2 khi không đọc được tham số hoặc gói.
package main

import (
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"

	"mission/services-go/ledger/internal/bundle"
	"mission/services-go/ledger/internal/proof"
)

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func run(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("ledger-verify", flag.ContinueOnError)
	fs.SetOutput(stderr)
	path := fs.String("bundle", "", "tệp gói xuất (.tar)")
	anchors := fs.String("anchors", "", "tệp JSON bản neo lấy từ bên độc lập (một bản neo hoặc mảng)")
	tsaCA := fs.String("tsa-ca", "", "tệp PEM chứng thư gốc tin cậy của TSA")
	sealCA := fs.String("seal-ca", "", "tệp PEM chứng thư gốc tin cậy của khoá niêm phong")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Cách dùng: ledger-verify --bundle <gói.tar> [--anchors <bản-neo.json>] [--tsa-ca <tsa.pem>] [--seal-ca <ca.pem>]")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *path == "" {
		fs.Usage()
		return 2
	}
	var opt bundle.Options
	var err error
	if opt.TSARoots, err = loadPool(*tsaCA); err != nil {
		fmt.Fprintln(stderr, "Lỗi:", err)
		return 2
	}
	if opt.SealRoots, err = loadPool(*sealCA); err != nil {
		fmt.Fprintln(stderr, "Lỗi:", err)
		return 2
	}
	if *anchors != "" {
		if opt.Anchors, err = loadAnchors(*anchors); err != nil {
			fmt.Fprintln(stderr, "Lỗi:", err)
			return 2
		}
	}
	f, err := os.Open(*path)
	if err != nil {
		fmt.Fprintln(stderr, "Lỗi: không mở được gói:", err)
		return 2
	}
	defer f.Close()
	b, err := bundle.Read(f)
	if err != nil {
		fmt.Fprintln(stderr, "Lỗi:", err)
		return 2
	}
	rep := bundle.Verify(b, opt)
	fmt.Fprintf(stdout, "Gói: tenant %s, cell %s, xuất lúc %s\n", b.Manifest.TenantID, b.Manifest.CellCode, b.Manifest.CreatedAt)
	for _, l := range rep.Lines {
		fmt.Fprintln(stdout, l)
	}
	for _, w := range rep.Warnings {
		fmt.Fprintln(stdout, "Lưu ý:", w)
	}
	for _, e := range rep.Errors {
		fmt.Fprintln(stdout, "Sai:", e)
	}
	if !rep.OK() {
		fmt.Fprintln(stdout, "Kết luận: KHÔNG HỢP LỆ")
		return 1
	}
	fmt.Fprintln(stdout, "Kết luận: HỢP LỆ")
	return 0
}

func loadPool(path string) (*x509.CertPool, error) {
	if path == "" {
		return nil, nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	n := 0
	for rest := raw; ; {
		var b *pem.Block
		b, rest = pem.Decode(rest)
		if b == nil {
			break
		}
		c, err := x509.ParseCertificate(b.Bytes)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
		pool.AddCert(c)
		n++
	}
	if n == 0 {
		return nil, fmt.Errorf("%s không chứa chứng thư PEM", path)
	}
	return pool, nil
}

func loadAnchors(path string) ([]proof.AnchorDocument, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var many []proof.AnchorDocument
	if err := json.Unmarshal(raw, &many); err == nil {
		return many, nil
	}
	var one proof.AnchorDocument
	if err := json.Unmarshal(raw, &one); err != nil {
		return nil, errors.New(path + " không phải bản neo JSON hợp lệ")
	}
	return []proof.AnchorDocument{one}, nil
}
