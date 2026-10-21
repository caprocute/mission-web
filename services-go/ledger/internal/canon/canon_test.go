package canon_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"golang.org/x/text/unicode/norm"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/testdb"
)

// GoldenVector biểu diễn cấu trúc tệp vector kiểm thử chuẩn hoá dùng chung giữa Go, Java và TypeScript
// tại thư mục data/golden/ledger-canon/*.json.
type GoldenVector struct {
	ID                string               `json:"id"`
	Description       string               `json:"description"`
	EquivalentTo      string               `json:"equivalent_to,omitempty"`
	InputPayloadRaw   string               `json:"input_payload_raw"`
	MetadataHeader    canon.MetadataHeader `json:"metadata_header"`
	PrevHashHex       string               `json:"prev_hash"`
	CanonicalPayload  string               `json:"canonical_payload"`
	CanonicalMetadata string               `json:"canonical_metadata"`
	PayloadHashHex    string               `json:"payload_hash"`
	MetadataHashHex   string               `json:"metadata_hash"`
	EntryHashHex      string               `json:"entry_hash"`
	ChainPartition    int16                `json:"chain_partition"`
}

func TestGoldenVectorsMatch(t *testing.T) {
	goldenDir := filepath.Join(testdb.RepoRoot(), "data", "golden", "ledger-canon")
	if os.Getenv("GENERATE_GOLDEN") == "1" {
		if err := generateGoldenFiles(goldenDir); err != nil {
			t.Fatalf("sinh golden vectors lỗi: %v", err)
		}
	}
	entries, err := os.ReadDir(goldenDir)
	if err != nil {
		t.Fatalf("không đọc được thư mục golden vectors %s: %v", goldenDir, err)
	}

	vectorsByID := make(map[string]GoldenVector)
	var count int
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		count++
		fullPath := filepath.Join(goldenDir, e.Name())
		rawBytes, err := os.ReadFile(fullPath)
		if err != nil {
			t.Fatalf("đọc tệp %s lỗi: %v", e.Name(), err)
		}

		var vec GoldenVector
		if err := json.Unmarshal(rawBytes, &vec); err != nil {
			t.Fatalf("unmarshal %s lỗi: %v", e.Name(), err)
		}

		// 1. Kiểm tra chọn chuỗi băm từ aggregate_id
		gotPart, err := canon.ChainPartitionFromString(vec.MetadataHeader.AggregateID)
		if err != nil {
			t.Fatalf("[%s] aggregate_id không hợp lệ: %v", vec.ID, err)
		}
		if gotPart != vec.ChainPartition {
			t.Errorf("[%s] chain_partition lệch: có %d, mong đợi %d", vec.ID, gotPart, vec.ChainPartition)
		}
		if vec.MetadataHeader.ChainPartition != vec.ChainPartition {
			t.Errorf("[%s] metadata_header.chain_partition (%d) != chain_partition (%d)",
				vec.ID, vec.MetadataHeader.ChainPartition, vec.ChainPartition)
		}

		// 2. Kiểm tra chuẩn hoá tải trọng và payload_hash
		payloadHash, canonPayload, err := canon.PayloadHash([]byte(vec.InputPayloadRaw))
		if err != nil {
			t.Fatalf("[%s] PayloadHash lỗi: %v", vec.ID, err)
		}
		if string(canonPayload) != vec.CanonicalPayload {
			t.Errorf("[%s] canonical_payload lệch:\n  có:       %s\n  mong đợi: %s",
				vec.ID, string(canonPayload), vec.CanonicalPayload)
		}
		if canon.Hex32(payloadHash) != vec.PayloadHashHex {
			t.Errorf("[%s] payload_hash lệch: có %s, mong đợi %s",
				vec.ID, canon.Hex32(payloadHash), vec.PayloadHashHex)
		}

		// 3. Kiểm tra chuẩn hoá siêu dữ liệu và metadata_hash
		metaHash, canonMeta, err := canon.MetadataHash(vec.MetadataHeader)
		if err != nil {
			t.Fatalf("[%s] MetadataHash lỗi: %v", vec.ID, err)
		}
		if string(canonMeta) != vec.CanonicalMetadata {
			t.Errorf("[%s] canonical_metadata lệch:\n  có:       %s\n  mong đợi: %s",
				vec.ID, string(canonMeta), vec.CanonicalMetadata)
		}
		if canon.Hex32(metaHash) != vec.MetadataHashHex {
			t.Errorf("[%s] metadata_hash lệch: có %s, mong đợi %s",
				vec.ID, canon.Hex32(metaHash), vec.MetadataHashHex)
		}

		// 4. Kiểm tra entry_hash = SHA-256(prev_hash || payload_hash || metadata_hash)
		prevHash, err := canon.ParseHex32(vec.PrevHashHex)
		if err != nil {
			t.Fatalf("[%s] prev_hash không hợp lệ: %v", vec.ID, err)
		}
		entryHash := canon.EntryHash(prevHash, payloadHash, metaHash)
		if canon.Hex32(entryHash) != vec.EntryHashHex {
			t.Errorf("[%s] entry_hash lệch: có %s, mong đợi %s",
				vec.ID, canon.Hex32(entryHash), vec.EntryHashHex)
		}

		vectorsByID[vec.ID] = vec
	}

	if count < 6 {
		t.Fatalf("cần tối thiểu 6 tệp golden vector trong %s, hiện có %d", goldenDir, count)
	}

	// 5. Kiểm tra các cặp tương đương NFC vs NFD cho ra đúng cùng canonical_payload và payload_hash
	for _, vec := range vectorsByID {
		if vec.EquivalentTo == "" {
			continue
		}
		target, ok := vectorsByID[vec.EquivalentTo]
		if !ok {
			t.Fatalf("[%s] tham chiếu equivalent_to=%q không tồn tại", vec.ID, vec.EquivalentTo)
		}
		if vec.CanonicalPayload != target.CanonicalPayload {
			t.Errorf("[%s vs %s] canonical_payload NFC/NFD phải trùng khớp tuyệt đối", vec.ID, target.ID)
		}
		if vec.PayloadHashHex != target.PayloadHashHex {
			t.Errorf("[%s vs %s] payload_hash NFC/NFD phải trùng khớp tuyệt đối", vec.ID, target.ID)
		}
		if vec.EntryHashHex != target.EntryHashHex {
			t.Errorf("[%s vs %s] entry_hash NFC/NFD phải trùng khớp tuyệt đối", vec.ID, target.ID)
		}
	}
}

func TestVietnameseNFCvsNFDIsolated(t *testing.T) {
	nfcText := "Đảng ủy Khối các cơ quan Trung ương — Nghị quyết số 366-QĐ/TW về kiểm điểm, đánh giá, xếp loại chất lượng hằng năm"
	nfdText := norm.NFD.String(nfcText)
	if nfcText == nfdText {
		t.Fatal("chuỗi NFD phải khác byte so với chuỗi NFC trước khi chuẩn hoá")
	}

	rawNFC := `{"don_vi":"` + nfcText + `","diem_so":"98.500","nam":2026}`
	rawNFD := `{"nam":2026,"diem_so":"98.500","don_vi":"` + nfdText + `"}`

	hashNFC, canonNFC, err := canon.PayloadHash([]byte(rawNFC))
	if err != nil {
		t.Fatalf("PayloadHash NFC lỗi: %v", err)
	}
	hashNFD, canonNFD, err := canon.PayloadHash([]byte(rawNFD))
	if err != nil {
		t.Fatalf("PayloadHash NFD lỗi: %v", err)
	}

	if !bytes.Equal(canonNFC, canonNFD) {
		t.Fatalf("chuỗi chuẩn hoá NFC và NFD khác nhau:\nNFC: %s\nNFD: %s", canonNFC, canonNFD)
	}
	if hashNFC != hashNFD {
		t.Fatalf("payload_hash NFC và NFD khác nhau: %x != %x", hashNFC, hashNFD)
	}
}

func TestChainPartitionDeterminismAndDistribution(t *testing.T) {
	// Cùng một đối tượng luôn rơi vào đúng một phân vùng chuỗi (kể cả chữ hoa/thường trong UUID).
	for i := 0; i < 500; i++ {
		id := uuid.New()
		p1 := canon.ChainPartition(id)
		p2, err := canon.ChainPartitionFromString(strings.ToUpper(id.String()))
		if err != nil {
			t.Fatal(err)
		}
		p3, err := canon.ChainPartitionFromString(strings.ToLower(id.String()))
		if err != nil {
			t.Fatal(err)
		}
		if p1 < 0 || p1 >= canon.ChainPartitionCount {
			t.Fatalf("phân vùng chuỗi %d nằm ngoài 0..63", p1)
		}
		if p1 != p2 || p1 != p3 {
			t.Fatalf("cùng aggregate_id %s phải cho cùng phân vùng: %d, %d, %d", id, p1, p2, p3)
		}
	}
}

func TestRFC8785EdgeCases(t *testing.T) {
	// 1. Trùng khoá sau khi chuẩn hoá NFC phải bị từ chối
	keyNFC := norm.NFC.String("kỳ_đánh_giá")
	keyNFD := norm.NFD.String("kỳ_đánh_giá")
	dupJSON := `{"` + keyNFC + `":1,"` + keyNFD + `":2}`
	if _, err := canon.CanonicalizeJSON([]byte(dupJSON)); !errors.Is(err, canon.ErrDuplicateNFCKey) {
		t.Fatalf("mong đợi ErrDuplicateNFCKey khi hai khoá trùng sau NFC, nhận: %v", err)
	}

	// 2. Định dạng thời gian UTC micro-giây và chuỗi thập phân
	ts := time.Date(2026, 10, 21, 15, 4, 5, 123456789, time.FixedZone("ICT", 7*3600))
	gotTS := canon.FormatTimestamp(ts)
	wantTS := "2026-10-21T08:04:05.123456Z"
	if gotTS != wantTS {
		t.Errorf("FormatTimestamp: có %q, mong đợi %q", gotTS, wantTS)
	}

	if _, err := canon.ValidateDecimalString("125.500"); err != nil {
		t.Errorf("ValidateDecimalString hợp lệ báo lỗi: %v", err)
	}
	if _, err := canon.ValidateDecimalString("125.50.0"); !errors.Is(err, canon.ErrInvalidDecimalString) {
		t.Errorf("ValidateDecimalString sai định dạng phải trả ErrInvalidDecimalString, nhận: %v", err)
	}
}

func strPtr(s string) *string { return &s }

func buildGoldenVector(id, desc, eqTo, rawPayload, prevHex string, hdr canon.MetadataHeader) (GoldenVector, error) {
	part, err := canon.ChainPartitionFromString(hdr.AggregateID)
	if err != nil {
		return GoldenVector{}, err
	}
	hdr.ChainPartition = part
	pHash, pCanon, err := canon.PayloadHash([]byte(rawPayload))
	if err != nil {
		return GoldenVector{}, err
	}
	mHash, mCanon, err := canon.MetadataHash(hdr)
	if err != nil {
		return GoldenVector{}, err
	}
	prevHash, err := canon.ParseHex32(prevHex)
	if err != nil {
		return GoldenVector{}, err
	}
	eHash := canon.EntryHash(prevHash, pHash, mHash)
	return GoldenVector{
		ID:                id,
		Description:       desc,
		EquivalentTo:      eqTo,
		InputPayloadRaw:   rawPayload,
		MetadataHeader:    hdr,
		PrevHashHex:       canon.Hex32(prevHash),
		CanonicalPayload:  string(pCanon),
		CanonicalMetadata: string(mCanon),
		PayloadHashHex:    canon.Hex32(pHash),
		MetadataHashHex:   canon.Hex32(mHash),
		EntryHashHex:      canon.Hex32(eHash),
		ChainPartition:    part,
	}, nil
}

func generateGoldenFiles(outDir string) error {
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return err
	}
	zeroHex := canon.Hex32(canon.ZeroHash)
	tenantA := "11111111-1111-4111-8111-111111111111"
	aggTask1 := "0192b100-0000-7000-8000-000000000001"
	actor1 := "0192b000-0000-7000-8000-000000000099"

	v1, err := buildGoldenVector(
		"01-evt01-task-assigned-genesis",
		"Bản ghi đầu chuỗi (seq_no=1, prev_hash=32 byte 0) cho EVT-01 (Giao nhiệm vụ) với số ECMAScript, điểm chuỗi thập phân và dấu thời gian micro-giây UTC",
		"",
		"{\n  \"weight_score\": \"25.500\",\n  \"task_id\": \"0192b100-0000-7000-8000-000000000001\",\n  \"zero_neg\": -0.0,\n  \"small_exp\": 0.0000001,\n  \"large_exp\": 1e+21,\n  \"exact_int\": 100.00,\n  \"title\": \"Xây dựng báo cáo tổng kết công tác Đảng năm 2026\",\n  \"occurred_at\": \"2026-10-21T08:00:00.123456Z\"\n}",
		zeroHex,
		canon.MetadataHeader{
			TenantID:      tenantA,
			SeqNo:         1,
			EventType:     "EVT-01",
			AggregateType: "Task",
			AggregateID:   aggTask1,
			ActorID:       strPtr(actor1),
			ActorKind:     "NGUOI",
			OccurredAt:    "2026-10-21T08:00:00.123456Z",
			RecordedAt:    "2026-10-21T08:00:00.456789Z",
		},
	)
	if err != nil {
		return err
	}

	nfcPayload := `{"don_vi":"Đảng ủy Khối các cơ quan Trung ương","ket_qua":"Hoàn thành xuất sắc nhiệm vụ","diem_tong":"96.750","ky_danh_gia":"2026-Q4"}`
	nfdPayload := norm.NFD.String(`{"ky_danh_gia":"2026-Q4","diem_tong":"96.750","ket_qua":"Hoàn thành xuất sắc nhiệm vụ","don_vi":"Đảng ủy Khối các cơ quan Trung ương"}`)
	aggProfile := "0192b200-0000-7000-8000-000000000002"

	v2, err := buildGoldenVector(
		"02-vietnamese-nfc-composed",
		"Tải trọng tiếng Việt ở dạng dựng sẵn Unicode NFC chuẩn",
		"03-vietnamese-nfd-decomposed",
		nfcPayload,
		zeroHex,
		canon.MetadataHeader{
			TenantID:      tenantA,
			SeqNo:         1,
			EventType:     "EVT-08",
			AggregateType: "AppraisalProfile",
			AggregateID:   aggProfile,
			ActorID:       strPtr(actor1),
			ActorKind:     "NGUOI",
			OccurredAt:    "2026-10-21T09:15:00.000000Z",
			RecordedAt:    "2026-10-21T09:15:00.050000Z",
		},
	)
	if err != nil {
		return err
	}

	v3, err := buildGoldenVector(
		"03-vietnamese-nfd-decomposed",
		"Tải trọng tiếng Việt ở dạng tổ hợp dấu rời Unicode NFD và thứ tự khoá đảo ngược — phải cho cùng canonical_payload và payload_hash với vector 02",
		"02-vietnamese-nfc-composed",
		nfdPayload,
		zeroHex,
		canon.MetadataHeader{
			TenantID:      tenantA,
			SeqNo:         1,
			EventType:     "EVT-08",
			AggregateType: norm.NFD.String("AppraisalProfile"),
			AggregateID:   aggProfile,
			ActorID:       strPtr(actor1),
			ActorKind:     "NGUOI",
			OccurredAt:    "2026-10-21T09:15:00.000000Z",
			RecordedAt:    "2026-10-21T09:15:00.050000Z",
		},
	)
	if err != nil {
		return err
	}

	aggUTF16 := "0192b300-0000-7000-8000-000000000003"
	v4, err := buildGoldenVector(
		"04-utf16-key-order-and-escapes",
		"Kiểm tra sắp xếp khoá theo đơn vị mã UTF-16 (U+1F600 surrogate 0xD83D đứng trước U+E000) và thoát ký tự điều khiển chuẩn RFC 8785",
		"",
		"{\"\uE000\":\"bmp-private-use\",\"\U0001F600\":\"supplementary-surrogate\",\"ctrl\":\"dòng 1\\ndòng 2\\t\\\"trích dẫn\\\"\\\\gạch chéo\\u001f\",\"html\":\"<a>&</a>\"}",
		zeroHex,
		canon.MetadataHeader{
			TenantID:      tenantA,
			SeqNo:         1,
			EventType:     "EVT-13",
			AggregateType: "PolicyPack",
			AggregateID:   aggUTF16,
			ActorID:       strPtr(actor1),
			ActorKind:     "NGUOI",
			OccurredAt:    "2026-10-21T10:00:00.000000Z",
			RecordedAt:    "2026-10-21T10:00:00.010000Z",
		},
	)
	if err != nil {
		return err
	}

	v5, err := buildGoldenVector(
		"05-evt18-esign-completed-chained",
		"Bản ghi nối tiếp chuỗi băm (seq_no=2, prev_hash = entry_hash của vector 01) cho EVT-18 (Ký số hoàn tất)",
		"",
		"{\n  \"request_id\": \"0192b500-0000-7000-8000-000000000018\",\n  \"aggregate_id\": \"0192b100-0000-7000-8000-000000000001\",\n  \"signature_format\": \"PADES_B_LTA\",\n  \"signed_pdf_sha256\": \"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"\n}",
		v1.EntryHashHex,
		canon.MetadataHeader{
			TenantID:      tenantA,
			SeqNo:         2,
			EventType:     "EVT-18",
			AggregateType: "Task",
			AggregateID:   aggTask1,
			ActorID:       strPtr(actor1),
			ActorKind:     "NGUOI",
			OccurredAt:    "2026-10-21T08:05:00.000000Z",
			RecordedAt:    "2026-10-21T08:05:00.250000Z",
		},
	)
	if err != nil {
		return err
	}

	originLastHash := "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
	aggTenantMove := "0192b600-0000-7000-8000-000000000006"
	v6, err := buildGoldenVector(
		"06-tenant-moved-in-special-record",
		"Bản ghi quản trị đặc biệt TENANT_MOVED_IN nối tiếp entry_hash cuối của cell nguồn kèm mã niêm phong cuối",
		"",
		"{\n  \"origin_cell\": \"CELL-SHARED-01\",\n  \"target_cell\": \"CELL-HN\",\n  \"last_seal_id\": \"0192b5ff-0000-7000-8000-000000000088\",\n  \"origin_last_entry_hash\": \"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\"\n}",
		originLastHash,
		canon.MetadataHeader{
			TenantID:      tenantA,
			SeqNo:         101,
			EventType:     "TENANT_MOVED_IN",
			AggregateType: "TenantCell",
			AggregateID:   aggTenantMove,
			ActorID:       nil,
			ActorKind:     "HE_THONG",
			OccurredAt:    "2026-10-21T11:00:00.000000Z",
			RecordedAt:    "2026-10-21T11:00:00.005000Z",
		},
	)
	if err != nil {
		return err
	}

	for _, v := range []GoldenVector{v1, v2, v3, v4, v5, v6} {
		b, err := json.MarshalIndent(v, "", "  ")
		if err != nil {
			return err
		}
		path := filepath.Join(outDir, v.ID+".json")
		if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
			return err
		}
	}
	return nil
}
