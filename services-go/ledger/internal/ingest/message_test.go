package ingest

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/testdb"
)

func sampleEvent() EventInput {
	actor := uuid.New()
	h := sha256.Sum256([]byte("payload"))
	return EventInput{
		EventID:        uuid.New(),
		TenantID:       uuid.New(),
		EventType:      "task.task.assigned.v1",
		AggregateType:  "TASK",
		AggregateID:    uuid.New(),
		ActorID:        &actor,
		ActorKind:      "NGUOI",
		OccurredAt:     time.Date(2026, 10, 23, 1, 2, 3, 456789000, time.UTC),
		Classification: "C1",
		PayloadHash:    h[:],
		Metadata:       map[string]any{"before_status": "DANG_SOAN", "after_status": "DA_GIAO"},
	}
}

func TestSchemaKhopHopDong(t *testing.T) {
	want, err := os.ReadFile(filepath.Join(testdb.RepoRoot(), "contracts/events/avro/sys/LedgerAppend.avsc"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(bytes.TrimSpace(want), bytes.TrimSpace([]byte(ledgerAppendSchema))) {
		t.Fatal("schemas/LedgerAppend.avsc lệch contracts/events/avro/sys/LedgerAppend.avsc")
	}
}

func TestMaHoaGiaiMaKhuHoi(t *testing.T) {
	ev := sampleEvent()
	r, err := EncodeRecord(ev)
	if err != nil {
		t.Fatal(err)
	}
	if string(r.Key) != ev.PartitionKey() || r.Topic != TopicLedgerAppend {
		t.Fatalf("khoá %q topic %q", r.Key, r.Topic)
	}
	got, err := DecodeRecord(r)
	if err != nil {
		t.Fatal(err)
	}
	if got.EventID != ev.EventID || got.TenantID != ev.TenantID || got.AggregateID != ev.AggregateID ||
		got.AggregateType != "TASK" || got.ActorKind != "NGUOI" || *got.ActorID != *ev.ActorID ||
		!got.OccurredAt.Equal(ev.OccurredAt) || !bytes.Equal(got.PayloadHash, ev.PayloadHash) {
		t.Fatalf("giải mã lệch: %+v", got)
	}
	if got.Metadata["after_status"] != "DA_GIAO" || got.Metadata["classification"] != "C1" {
		t.Fatalf("metadata %v", got.Metadata)
	}
}

func TestGiaiMaGiaTriMacDinh(t *testing.T) {
	ev := sampleEvent()
	ev.ActorID, ev.ActorKind, ev.AggregateType, ev.Classification, ev.Metadata = nil, "", "", "", nil
	r, _ := EncodeRecord(ev)
	// Thiếu tiêu đề tenantid thì lấy tenant từ khoá.
	r.Headers = r.Headers[:1]
	got, err := DecodeRecord(r)
	if err != nil {
		t.Fatal(err)
	}
	if got.TenantID != ev.TenantID || got.ActorKind != "HE_THONG" || got.AggregateType != "TASK" || got.Metadata != nil {
		t.Fatalf("mặc định sai: %+v", got)
	}
}

func TestGiaiMaTuChoi(t *testing.T) {
	cases := map[string]func(*EventInput, *kgo.Record){
		"sai chuỗi": func(_ *EventInput, r *kgo.Record) {
			ev, _ := DecodeRecord(r)
			other := ev
			for other.ChainPartition() == ev.ChainPartition() {
				other.AggregateID = uuid.New()
			}
			bad, _ := EncodeRecord(other)
			// Giữ thân của đối tượng khác nhưng đổi chainNo: dựng lại thân với chainNo lệch.
			native, _, _ := ledgerAppendCodec.NativeFromBinary(bad.Value)
			m := native.(map[string]any)
			m["chainNo"] = int32(ev.ChainPartition())
			r.Value, _ = ledgerAppendCodec.BinaryFromNative(nil, m)
		},
		"C2 có trường ngoài danh sách": func(_ *EventInput, r *kgo.Record) {
			r.Headers = append(r.Headers,
				kgo.RecordHeader{Key: HeaderClassification, Value: []byte("C2")},
				kgo.RecordHeader{Key: metaHeaderPrefix + "comment_body", Value: []byte("nội dung")})
		},
		"không thuộc danh mục": func(ev *EventInput, r *kgo.Record) {
			native, _, _ := ledgerAppendCodec.NativeFromBinary(r.Value)
			m := native.(map[string]any)
			m["sourceEventType"] = "notify.message.requested.v1"
			r.Value, _ = ledgerAppendCodec.BinaryFromNative(nil, m)
		},
		"bản ghi đặc biệt từ topic": func(ev *EventInput, r *kgo.Record) {
			native, _, _ := ledgerAppendCodec.NativeFromBinary(r.Value)
			m := native.(map[string]any)
			m["sourceEventType"] = EventTypeTenantMovedIn
			r.Value, _ = ledgerAppendCodec.BinaryFromNative(nil, m)
		},
		"thân hỏng": func(_ *EventInput, r *kgo.Record) { r.Value = []byte{1, 2} },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			ev := sampleEvent()
			ev.Classification = ""
			ev.Metadata = nil
			r, _ := EncodeRecord(ev)
			mutate(&ev, r)
			if _, err := DecodeRecord(r); !errors.Is(err, ErrInvalidEvent) {
				t.Fatalf("cần ErrInvalidEvent, nhận %v", err)
			}
		})
	}
}

func TestKiemMetadataC2(t *testing.T) {
	if err := ValidateMetadataC2("C2", map[string]any{"before_status": "A", "ref_id": "x"}); err != nil {
		t.Fatal(err)
	}
	if err := ValidateMetadataC2("C1", map[string]any{"ghi_chu": "được phép ở C1"}); err != nil {
		t.Fatal(err)
	}
	for _, m := range []map[string]any{
		{"noi_dung": "rõ"},
		{"ref_id": map[string]any{"a": 1}},
	} {
		if err := ValidateMetadataC2("C3", m); !errors.Is(err, ErrC2CleartextInMetadata) {
			t.Fatalf("%v: cần chặn, nhận %v", m, err)
		}
	}
	long := string(bytes.Repeat([]byte("a"), 257))
	if err := ValidateMetadataC2("C1", map[string]any{"ref_id": long}); err == nil {
		t.Fatal("cần chặn chuỗi dài")
	}
}

// TestLuongGhiKhopVectorVang: luồng ghi tính metadata_hash và entry_hash đúng như bộ vector dùng chung.
func TestLuongGhiKhopVectorVang(t *testing.T) {
	files, _ := filepath.Glob(filepath.Join(testdb.RepoRoot(), "data/golden/ledger-canon/*.json"))
	if len(files) == 0 {
		t.Fatal("không thấy vector")
	}
	ing := &Ingester{}
	for _, f := range files {
		raw, _ := os.ReadFile(f)
		var v struct {
			Header       canon.MetadataHeader `json:"metadata_header"`
			Metadata     string               `json:"input_metadata_raw"`
			Prev         string               `json:"prev_hash"`
			PayloadHash  string               `json:"payload_hash"`
			MetadataHash string               `json:"metadata_hash"`
			EntryHash    string               `json:"entry_hash"`
		}
		if err := json.Unmarshal(raw, &v); err != nil {
			t.Fatal(err)
		}
		h := v.Header
		occurred, _ := time.Parse(time.RFC3339Nano, h.OccurredAt)
		recorded, _ := time.Parse(time.RFC3339Nano, h.RecordedAt)
		ev := EventInput{
			EventID: uuid.New(), TenantID: uuid.MustParse(h.TenantID), EventType: h.EventType,
			AggregateType: h.AggregateType, AggregateID: uuid.MustParse(h.AggregateID),
			ActorKind: h.ActorKind, OccurredAt: occurred,
		}
		ev.PayloadHash, _ = hex.DecodeString(v.PayloadHash)
		if err := json.Unmarshal([]byte(v.Metadata), &ev.Metadata); err != nil {
			t.Fatal(f, err)
		}
		if h.ActorID != nil {
			a := uuid.MustParse(*h.ActorID)
			ev.ActorID = &a
		}
		prev, _ := canon.ParseHex32(v.Prev)
		e, err := ing.entry(ev, ev.TenantID, h.ChainPartition, h.SeqNo, prev, recorded)
		if err != nil {
			t.Fatal(f, err)
		}
		if hex.EncodeToString(e.MetadataHash) != v.MetadataHash || hex.EncodeToString(e.EntryHash) != v.EntryHash {
			t.Fatalf("%s: lệch vector", filepath.Base(f))
		}
	}
}
