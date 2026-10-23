package forward

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
	"gopkg.in/yaml.v3"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/testdb"
)

// Bản chép lược đồ phải khớp từng byte với contracts/events/avro, và danh mục topic phải khớp với các
// topic có lược đồ đó trong catalog.yaml.
func TestSchemasMatchContracts(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join(testdb.RepoRoot(), "contracts/events/catalog.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	var cat map[string]any
	if err := yaml.Unmarshal(raw, &cat); err != nil {
		t.Fatal(err)
	}
	schemaOf := map[string]string{}
	for _, v := range cat {
		list, _ := v.([]any)
		for _, e := range list {
			m, _ := e.(map[string]any)
			topic, _ := m["topic"].(string)
			schema, _ := m["schema"].(string)
			if topic != "" && schema != "" {
				schemaOf[topic] = schema
			}
		}
	}
	for topic, file := range Topics {
		if !ingest.IsLegalEvent(topic) {
			t.Errorf("%s không thuộc danh mục sự kiện pháp lý của luồng ghi", topic)
		}
		src, ok := schemaOf[topic]
		if !ok {
			t.Errorf("catalog.yaml không có topic %s", topic)
			continue
		}
		if filepath.Base(src) != file {
			t.Errorf("%s: catalog dùng %s, bản chép là %s", topic, src, file)
		}
		want, err := os.ReadFile(filepath.Join(testdb.RepoRoot(), "contracts/events", src))
		if err != nil {
			t.Fatal(err)
		}
		got, _ := schemaFS.ReadFile("schemas/" + file)
		if !bytes.Equal(want, got) {
			t.Errorf("schemas/%s lệch contracts/events/%s", file, src)
		}
	}
}

// progressJSON là bản JSON Avro của progressNative theo đặc tả (bytes của decimal 12,500 thang 3 là
// 0x30D4, mỗi byte một ký tự U+0000..U+00FF; timestamp-micros là số micro giây).
const progressJSON = `{"taskId":"0190a3f4-1111-7000-8000-000000000001","periodId":"0190a3f4-2222-7000-8000-000000000002",
"progressId":"0190a3f4-3333-7000-8000-000000000003","reporterId":"0190a3f4-4444-7000-8000-000000000004",
"progressPct":"0\u00d4","actualValue":null,"evidenceCount":2,"reportedAt":1761200000000000,"aggregateVersion":3}`

func progressRecord(t *testing.T) []byte {
	t.Helper()
	native := map[string]any{
		"taskId": "0190a3f4-1111-7000-8000-000000000001", "periodId": "0190a3f4-2222-7000-8000-000000000002",
		"progressId": "0190a3f4-3333-7000-8000-000000000003", "reporterId": "0190a3f4-4444-7000-8000-000000000004",
		"progressPct": big.NewRat(25, 2), "actualValue": nil, "evidenceCount": int32(2),
		"reportedAt": time.UnixMicro(1761200000000000).UTC(), "aggregateVersion": int32(3),
	}
	bin, err := codecs["task.task.progressed.v1"].BinaryFromNative(nil, native)
	if err != nil {
		t.Fatal(err)
	}
	return bin
}

// Băm bằng JCS của bản JSON Avro: thứ tự khoá trong JSON không ảnh hưởng, và khớp băm tính tay.
func TestPayloadHashIsJCSOfAvroJSON(t *testing.T) {
	got, err := PayloadHash("task.task.progressed.v1", progressRecord(t))
	if err != nil {
		t.Fatal(err)
	}
	canonical, err := canon.CanonicalizeJSON([]byte(progressJSON))
	if err != nil {
		t.Fatal(err)
	}
	want := sha256.Sum256(canonical)
	if !bytes.Equal(got, want[:]) {
		t.Fatalf("payload_hash = %x, muốn %x (JCS %s)", got, want, canonical)
	}
	if _, err := PayloadHash("task.task.progressed.v1", []byte{0x01}); !errors.Is(err, ErrInvalid) {
		t.Fatalf("thân hỏng phải là ErrInvalid, được %v", err)
	}
	if _, err := PayloadHash("org.unit.changed.v1", nil); !errors.Is(err, ErrInvalid) {
		t.Fatalf("topic ngoài danh mục phải là ErrInvalid, được %v", err)
	}
}

func TestConvertBothHeaderStyles(t *testing.T) {
	tenant, agg, id, actor := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	value := progressRecord(t)
	flat := &kgo.Record{Topic: "task.task.progressed.v1", Key: []byte(tenant.String() + ":" + agg.String()), Value: value,
		Headers: []kgo.RecordHeader{
			{Key: "id", Value: []byte(id.String())}, {Key: "tenantid", Value: []byte(tenant.String())},
			{Key: "actorid", Value: []byte(actor.String())}, {Key: "classification", Value: []byte("C1")},
			{Key: "occurredat", Value: []byte("2026-10-23T01:02:03.456Z")},
		}}
	router := &kgo.Record{Topic: "task.task.progressed.v1", Key: flat.Key, Value: value,
		Headers: []kgo.RecordHeader{
			{Key: "ce_id", Value: []byte(id.String())}, {Key: "ce_tenantid", Value: []byte(tenant.String())},
			{Key: "ce_source", Value: []byte("task")},
			{Key: "ce_headers", Value: []byte(`{"actorid":"` + actor.String() + `","classification":"C1","occurredat":"2026-10-23T01:02:03.456Z"}`)},
		}}
	for name, r := range map[string]*kgo.Record{"phẳng": flat, "EventRouter": router} {
		ev, err := Convert(r)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if ev.EventID != id || ev.TenantID != tenant || ev.AggregateID != agg || ev.ActorID == nil || *ev.ActorID != actor {
			t.Fatalf("%s: sai mã: %+v", name, ev)
		}
		if !ev.OccurredAt.Equal(time.Date(2026, 10, 23, 1, 2, 3, 456e6, time.UTC)) || ev.Classification != "C1" {
			t.Fatalf("%s: sai thời điểm hoặc phân loại: %+v", name, ev)
		}
		// Bản ghi chuyển tiếp phải đọc lại được bằng bộ giải mã của luồng ghi.
		rec, err := ingest.EncodeRecord(ev)
		if err != nil {
			t.Fatal(err)
		}
		back, err := ingest.DecodeRecord(rec)
		if err != nil {
			t.Fatalf("%s: luồng ghi không đọc được bản chuyển tiếp: %v", name, err)
		}
		if back.EventID != id || !bytes.Equal(back.PayloadHash, ev.PayloadHash) || back.EventType != "task.task.progressed.v1" {
			t.Fatalf("%s: bản đọc lại lệch: %+v", name, back)
		}
		if name == "EventRouter" && back.AggregateType != "TASK" {
			t.Fatalf("aggregate_type = %q, muốn TASK", back.AggregateType)
		}
	}
	bad := *flat
	bad.Key = []byte("khong-co-khoa")
	if _, err := Convert(&bad); !errors.Is(err, ErrInvalid) {
		t.Fatalf("khoá sai phải là ErrInvalid, được %v", err)
	}
}
