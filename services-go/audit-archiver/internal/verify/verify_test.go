package verify

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"strings"
	"testing"
	"time"

	"mission/services-go/audit-archiver/internal/archive"
	"mission/services-go/audit-archiver/internal/memstore"
)

type kho struct {
	st   *memstore.Store
	priv ed25519.PrivateKey
	keys map[int]ed25519.PublicKey
	b    []*archive.Batch
}

// dung dựng n lô liền nhau của phân vùng 0, mỗi lô 4 bản ghi, mỗi lô một giờ.
func dung(t *testing.T, n int) *kho {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	k := &kho{st: memstore.New(), priv: priv, keys: map[int]ed25519.PublicKey{1: pub}}
	p := archive.Params{Cell: "cell-g4", SignKey: "audit-archiver", Retention: time.Hour}
	t0 := time.Date(2026, 10, 20, 0, 0, 0, 0, time.UTC)
	var prev archive.Link
	pos := int64(0)
	for i := 0; i < n; i++ {
		var rows []archive.Row
		for j := 0; j < 4; j++ {
			off := pos + int64(j)
			if i == 1 && j == 2 {
				continue // offset trống (bản ghi điều khiển giao dịch)
			}
			rows = append(rows, archive.Row{Topic: "sys.audit.v1", Partition: 0, Offset: off,
				Timestamp: t0.Add(time.Duration(i) * time.Hour).Add(time.Duration(j) * time.Second), Value: []byte{1, byte(off)}})
		}
		b, err := archive.Build(p, rows, pos, prev, t0.Add(time.Duration(i)*time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		sig := "vault:v1:" + base64.StdEncoding.EncodeToString(ed25519.Sign(priv, b.ManifestBytes))
		ctx := context.Background()
		for _, o := range [][2]any{{b.ManifestKey, b.ManifestBytes}, {b.SigKey(), []byte(sig)}, {b.DataKey, b.Data}} {
			if _, err := k.st.PutLocked(ctx, o[0].(string), o[1].([]byte), ""); err != nil {
				t.Fatal(err)
			}
		}
		k.b = append(k.b, b)
		prev = archive.Link{Key: b.ManifestKey, Sha256: archive.SHA256Hex(b.ManifestBytes)}
		pos = b.Manifest.DenOffset
	}
	return k
}

func (k *kho) run(t *testing.T, o Options) *Report {
	o.Cell, o.Topic, o.Keys = "cell-g4", "sys.audit.v1", k.keys
	if o.Now == nil {
		o.Now = func() time.Time { return time.Date(2026, 10, 23, 0, 0, 0, 0, time.UTC) }
	}
	r, err := Run(context.Background(), k.st, o)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func coLoi(r *Report, sub string) bool {
	for _, l := range r.Loi {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}

func TestKhoNguyenVenDat(t *testing.T) {
	k := dung(t, 5)
	r := k.run(t, Options{})
	if !r.OK() || r.SoTep != 5 || r.SoBanGhi != 19 || r.PhanVung[0].KhoangTrong != 1 || r.PhanVung[0].DenOffset != 20 {
		t.Fatalf("kho nguyên vẹn phải đạt: %+v %+v", r, r.PhanVung[0])
	}
	if tip := r.Tips()[0]; tip.Key != k.b[4].ManifestKey || tip.DenOffset != 20 {
		t.Fatalf("mắt xích cuối sai: %+v", tip)
	}
}

// Sửa một byte tệp Parquet thì công cụ kiểm phát hiện.
func TestSuaMotByteTepDuLieuBiPhatHien(t *testing.T) {
	k := dung(t, 3)
	k.st.Tamper(k.b[1].DataKey, func(b []byte) []byte { b[len(b)/2] ^= 0x01; return b })
	r := k.run(t, Options{})
	if r.OK() || !coLoi(r, "SHA-256") {
		t.Fatalf("sửa byte phải bị phát hiện: %v", r.Loi)
	}
}

func TestSuaKeKhaiHoacChuKyBiPhatHien(t *testing.T) {
	k := dung(t, 3)
	k.st.Tamper(k.b[1].ManifestKey, func(b []byte) []byte {
		return []byte(strings.Replace(string(b), `"soBanGhi": 3`, `"soBanGhi": 4`, 1))
	})
	r := k.run(t, Options{})
	if !coLoi(r, "chữ ký không khớp") || !coLoi(r, "chuỗi kê khai đứt") {
		t.Fatalf("kê khai bị sửa phải hỏng chữ ký và chuỗi: %v", r.Loi)
	}
}

func TestThieuLoGiuaChuoiBiPhatHien(t *testing.T) {
	k := dung(t, 4)
	for _, key := range []string{k.b[2].ManifestKey, k.b[2].SigKey(), k.b[2].DataKey} {
		k.st.Remove(key)
	}
	r := k.run(t, Options{})
	if !coLoi(r, "chuỗi kê khai đứt") || !coLoi(r, "không liên tục") {
		t.Fatalf("mất lô giữa chuỗi phải bị phát hiện: %v", r.Loi)
	}
	// Mất lô đầu tiên: kiểm toàn bộ thấy kê khai đầu trỏ tới kê khai không còn.
	k2 := dung(t, 2)
	k2.st.Remove(k2.b[0].ManifestKey)
	k2.st.Remove(k2.b[0].SigKey())
	r2 := k2.run(t, Options{})
	if !coLoi(r2, "không có kê khai") || !coLoi(r2, "thiếu các kê khai trước") {
		t.Fatalf("mất lô đầu phải bị phát hiện: %v", r2.Loi)
	}
}

// Kiểm theo cửa sổ: lô đầu cửa sổ được nối với kê khai trước nằm ngoài cửa sổ.
func TestKiemTheoCuaSoNoiKeKhaiTruoc(t *testing.T) {
	k := dung(t, 5)
	r := k.run(t, Options{From: time.Date(2026, 10, 20, 2, 0, 0, 0, time.UTC)})
	if !r.OK() || r.SoTep != 3 {
		t.Fatalf("cửa sổ phải đạt với 3 tệp: %+v", r)
	}
	k.st.Tamper(k.b[1].ManifestKey, func(b []byte) []byte { return append(b, ' ') })
	r = k.run(t, Options{From: time.Date(2026, 10, 20, 2, 0, 0, 0, time.UTC)})
	if !coLoi(r, "băm kê khai trước") {
		t.Fatalf("kê khai trước ngoài cửa sổ bị sửa phải bị phát hiện: %v", r.Loi)
	}
}

// Lô đang ghi dở (gần hiện tại) chỉ là cảnh báo; lô cũ thiếu chữ ký là lỗi.
func TestLoDangGhiLaCanhBao(t *testing.T) {
	k := dung(t, 2)
	k.st.Remove(k.b[1].SigKey())
	k.st.Remove(k.b[1].DataKey)
	gan := func() time.Time { return k.b[1].Manifest.ThoiDiemDau.Add(10 * time.Minute) }
	r := k.run(t, Options{Now: gan})
	if !r.OK() || len(r.CanhBao) != 2 {
		t.Fatalf("lô gần hiện tại phải là cảnh báo: %v %v", r.Loi, r.CanhBao)
	}
	r = k.run(t, Options{})
	if !coLoi(r, "không có chữ ký") || !coLoi(r, "thiếu tệp dữ liệu") {
		t.Fatalf("lô cũ thiếu tệp phải lỗi: %v", r.Loi)
	}
}

func TestDoiTuongLaTrongKhoLaLoi(t *testing.T) {
	k := dung(t, 1)
	_, _ = k.st.PutLocked(context.Background(), "cell-g4/sys.audit.v1/2026/10/20/00/la.txt", []byte("x"), "")
	if r := k.run(t, Options{}); !coLoi(r, "đối tượng lạ") {
		t.Fatalf("đối tượng lạ phải bị báo: %v", r.Loi)
	}
}
