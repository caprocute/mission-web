package archive

import (
	"bytes"
	"strings"
	"testing"
	"time"
)

func rows(n int, from int64) []Row {
	t0 := time.Date(2026, 10, 23, 7, 59, 0, 0, time.UTC)
	out := make([]Row, n)
	for i := range out {
		out[i] = Row{
			Topic: "sys.audit.v1", Partition: 3, Offset: from + int64(i),
			Timestamp: t0.Add(time.Duration(i) * time.Second),
			Value:     []byte{0, 0, 0, 0, 7, byte(i)},
			Headers:   []Header{{Key: "ce_id", Value: []byte("id-" + string(rune('a'+i%26)))}, {Key: "tenant_id", Value: []byte("t1")}},
		}
		if i%2 == 0 {
			out[i].Key = []byte("tenant-1")
		}
	}
	return out
}

// Cùng dãy bản ghi luôn cho cùng byte Parquet: điều kiện để dựng lại tệp từ Kafka và so khớp kê khai đã ký.
func TestEncodeParquetXacDinhVaDocLaiDung(t *testing.T) {
	rs := rows(500, 1000)
	a, err := EncodeParquet(rs)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := EncodeParquet(rows(500, 1000))
	if !bytes.Equal(a, b) {
		t.Fatal("mã hoá Parquet không xác định")
	}
	back, err := DecodeParquet(a)
	if err != nil {
		t.Fatal(err)
	}
	if len(back) != 500 || back[499].Offset != 1499 || !back[10].Timestamp.Equal(rs[10].Timestamp) {
		t.Fatalf("đọc lại sai: %d bản ghi", len(back))
	}
	if !bytes.Equal(back[2].Key, []byte("tenant-1")) || back[1].Key != nil || string(back[3].Headers[1].Value) != "t1" || !bytes.Equal(back[4].Value, rs[4].Value) {
		t.Fatalf("khoá, header hoặc thân Avro không giữ nguyên: %+v", back[1])
	}
}

func TestBuildKhoaVaChuoi(t *testing.T) {
	now := time.Date(2026, 10, 23, 8, 3, 0, 0, time.UTC)
	p := Params{Cell: "cell-g4", SignKey: "audit-archiver", Retention: 10 * 365 * 24 * time.Hour}
	b, err := Build(p, rows(3, 42), 40, Link{Key: "k0", Sha256: strings.Repeat("a", 64)}, now)
	if err != nil {
		t.Fatal(err)
	}
	want := "cell-g4/sys.audit.v1/2026/10/23/07/0003-00000000000000000040"
	if b.DataKey != want+".parquet" || b.ManifestKey != want+".json" || b.SigKey() != want+".sig" {
		t.Fatalf("khoá sai: %s", b.ManifestKey)
	}
	m := b.Manifest
	if m.TuOffset != 40 || m.DenOffset != 45 || m.OffsetDau != 42 || m.OffsetCuoi != 44 || m.SoBanGhi != 3 || m.KeKhaiTruoc != "k0" || m.Sha256 != SHA256Hex(b.Data) {
		t.Fatalf("kê khai sai: %+v", m)
	}
	ki, ok := ParseKey(b.DataKey)
	if !ok || ki.Partition != 3 || ki.From != 40 || ki.Kind != "parquet" {
		t.Fatalf("ParseKey sai: %+v", ki)
	}
	pm, err := ParseManifest(b.ManifestBytes)
	if err != nil || pm.DenOffset != 45 {
		t.Fatalf("ParseManifest: %v", err)
	}
	if _, err := ParseManifest([]byte(`{"phienBan":1,"la":1}`)); err == nil {
		t.Fatal("kê khai có trường lạ phải bị từ chối")
	}
	if _, err := Build(p, rows(2, 10), 11, Link{}, now); err == nil {
		t.Fatal("bản ghi trước vị trí bắt đầu phải bị từ chối")
	}
	if _, ok := ParseKey("cell-g4/sys.audit.v1/2026/10/23/07/3-40.parquet"); ok {
		t.Fatal("khoá không đệm số phải bị từ chối")
	}
}
