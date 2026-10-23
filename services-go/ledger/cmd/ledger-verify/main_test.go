package main

import (
	"bytes"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"mission/services-go/ledger/internal/testdb"
)

func golden(name string) string {
	return filepath.Join(testdb.RepoRoot(), "data/golden/ledger-canon/ledger-verify", name)
}

func TestGoiMauHopLe(t *testing.T) {
	var out, errb bytes.Buffer
	code := run([]string{"--bundle", golden("goi-mau.tar"), "--anchors", golden("ban-neo.json")}, &out, &errb)
	if code != 0 || !strings.Contains(out.String(), "Kết luận: HỢP LỆ") || !strings.Contains(out.String(), "Bản neo: 1/1 ngày khớp") {
		t.Fatalf("mã thoát %d\n%s%s", code, out.String(), errb.String())
	}
}

// Sửa một byte (một chữ số hex của payloadHash): công cụ chỉ đúng chuỗi, seq_no và thoát mã 1.
func TestSuaMotByteBiPhatHien(t *testing.T) {
	raw, err := os.ReadFile(golden("goi-mau.tar"))
	if err != nil {
		t.Fatal(err)
	}
	re := regexp.MustCompile(`\{"actorId":"[^"]+","actorKind":"[A-Z_]+","aggregateId":"[^"]+","aggregateType":"[A-Z_]+","entryHash":"[0-9a-f]{64}","eventId":"[^"]+","eventType":"[^"]+","id":"[^"]+","inScope":(true|false),(?:"metadata":\{[^}]*\},)?"metadataHash":"[0-9a-f]{64}","metadataSha256":"[0-9a-f]{64}","occurredAt":"[^"]+","payloadHash":"([0-9a-f])`)
	loc := re.FindSubmatchIndex(raw)
	if loc == nil {
		t.Fatal("không tìm thấy bản ghi trong gói mẫu")
	}
	seqRe := regexp.MustCompile(`"seqNo":(\d+)`)
	seq := seqRe.FindSubmatch(raw[loc[1]:])
	chainRe := regexp.MustCompile(`"chainPartition":(\d+)`)
	chain := chainRe.FindSubmatch(raw[:loc[0]])
	pos := loc[4]
	bad := append([]byte(nil), raw...)
	if bad[pos] == '0' {
		bad[pos] = '1'
	} else {
		bad[pos] = '0'
	}
	p := filepath.Join(t.TempDir(), "goi-sua.tar")
	if err := os.WriteFile(p, bad, 0o600); err != nil {
		t.Fatal(err)
	}
	var out, errb bytes.Buffer
	code := run([]string{"--bundle", p}, &out, &errb)
	want := "Chuỗi " + string(chain[1]) + ", seq_no " + string(seq[1]) + ": entry_hash tính lại không khớp"
	if code != 1 || !strings.Contains(out.String(), want) || !strings.Contains(out.String(), "Kết luận: KHÔNG HỢP LỆ") {
		t.Fatalf("mã thoát %d, muốn 1 và %q\n%s%s", code, want, out.String(), errb.String())
	}
}

// Sửa trạng thái sau trong metadata của một bản ghi trong phạm vi (cùng độ dài, gói vẫn đọc được): metadata_hash gồm
// băm cột metadata nên công cụ chỉ đúng bản ghi và thoát mã 1. Kê khai cũng lệch băm entries.json; lỗi đó được báo riêng.
func TestSuaMetadataBiPhatHien(t *testing.T) {
	raw, err := os.ReadFile(golden("goi-mau.tar"))
	if err != nil {
		t.Fatal(err)
	}
	from, to := []byte(`"metadata":{"after_status":"DA_HOAN_THANH"}`), []byte(`"metadata":{"after_status":"DA_HUY_BO_XYZ"}`)
	i := bytes.Index(raw, from)
	if i < 0 || len(from) != len(to) {
		t.Fatal("không tìm thấy metadata trong gói mẫu")
	}
	seq := regexp.MustCompile(`"seqNo":(\d+)`).FindSubmatch(raw[i:])
	chains := regexp.MustCompile(`"chainPartition":(\d+)`).FindAllSubmatch(raw[:i], -1)
	if seq == nil || len(chains) == 0 {
		t.Fatal("không xác định được bản ghi bị sửa")
	}
	bad := append([]byte(nil), raw...)
	copy(bad[i:], to)
	p := filepath.Join(t.TempDir(), "goi-sua-metadata.tar")
	if err := os.WriteFile(p, bad, 0o600); err != nil {
		t.Fatal(err)
	}
	var out, errb bytes.Buffer
	code := run([]string{"--bundle", p}, &out, &errb)
	want := "Chuỗi " + string(chains[len(chains)-1][1]) + ", seq_no " + string(seq[1]) +
		": metadata của bản ghi không khớp băm metadata"
	if code != 1 || !strings.Contains(out.String(), want) || !strings.Contains(out.String(), "Kết luận: KHÔNG HỢP LỆ") {
		t.Fatalf("mã thoát %d, muốn 1 và %q\n%s%s", code, want, out.String(), errb.String())
	}
}

func TestThamSoSai(t *testing.T) {
	var out, errb bytes.Buffer
	if code := run(nil, &out, &errb); code != 2 {
		t.Fatalf("thiếu --bundle phải thoát 2, được %d", code)
	}
	if code := run([]string{"--bundle", filepath.Join(t.TempDir(), "khong-co.tar")}, &out, &errb); code != 2 {
		t.Fatalf("gói không tồn tại phải thoát 2, được %d", code)
	}
}
