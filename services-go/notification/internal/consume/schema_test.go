package consume

import (
	"bytes"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"testing"
	"time"
)

// Bản chép lược đồ trong schemas/ phải trùng contracts/events/avro (API-first, sửa mã chứ không sửa hợp đồng).
func TestSchemasMatchContract(t *testing.T) {
	_, f, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(f), "..", "..", "..", "..", "contracts", "events", "avro")
	for topic, rel := range SchemaFiles {
		want, err := os.ReadFile(filepath.Join(root, rel))
		if err != nil {
			t.Fatal(err)
		}
		got, err := schemaFS.ReadFile("schemas/" + baseName(rel))
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(bytes.TrimSpace(want), bytes.TrimSpace(got)) {
			t.Fatalf("%s: lược đồ %s lệch hợp đồng, chép lại từ contracts/events/avro", topic, rel)
		}
	}
}

func TestPlanTaskAssigned(t *testing.T) {
	p := &Processor{}
	lead, co := "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f701", "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f702"
	b, err := Encode(TopicTaskAssigned, NhiemVuDaGiao{TaskID: "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f7aa", Title: "Dự thảo",
		LeadAssigneeID: lead, AssigneeIDs: []string{co, lead}, DueDate: time.Date(2026, 10, 30, 0, 0, 0, 0, time.UTC),
		IsKeyTask: true, AggregateVersion: 3})
	if err != nil {
		t.Fatal(err)
	}
	pl, err := p.plan(TopicTaskAssigned, b)
	if err != nil {
		t.Fatal(err)
	}
	if got := uniq(pl.recipients); len(got) != 2 {
		t.Fatalf("người nhận phải gộp trùng, nhận %v", got)
	}
	if pl.templateFor(lead) != "NV_DUOC_GIAO" || pl.templateFor(co) != "NV_PHOI_HOP" {
		t.Fatal("chủ trì và phối hợp phải dùng mẫu khác nhau")
	}
	if pl.params["dueDate"] != "30/10/2026" || pl.priority != "CAO" || *pl.deepLink != "/tasks/0192f2c4-5d6e-7f80-91a2-b3c4d5e6f7aa" {
		t.Fatalf("tham số sai: %+v", pl)
	}
}

// Mọi thân sự kiện mã hoá rồi giải mã giữ nguyên giá trị, gồm union rỗng, union có giá trị và kiểu logic.
func TestRoundTrip(t *testing.T) {
	from := "DA_TU_DANH_GIA"
	plan := "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f7bb"
	link := "/okr"
	id := "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f7aa"
	cases := []struct {
		topic string
		in    any
		out   any
	}{
		{TopicNotifyRequested, YeuCauThongBao{RecipientIDs: []string{id}, TemplateCode: "X", Params: "{}", DeepLink: &link, Priority: "CAO", AggregateVersion: 1}, &YeuCauThongBao{}},
		{TopicTaskAssigned, NhiemVuDaGiao{TaskID: id, PlanItemID: &plan, Title: "T", LeadAssigneeID: id, AssigneeIDs: []string{}, DueDate: time.Date(2026, 1, 2, 0, 0, 0, 0, time.UTC), AggregateVersion: 2}, &NhiemVuDaGiao{}},
		{TopicEvidenceSubmit, MinhChungDaNop{EvidenceID: id, VersionID: id, DeliverableID: id, TaskID: id, ObjectSha256: make([]byte, 32), SubmittedBy: id, AggregateVersion: 3}, &MinhChungDaNop{}},
		{TopicProfileStep, HoSoDanhGiaChuyenBuoc{ProfileID: id, FromStatus: &from, ToStatus: "DA_THAM_DINH", Stage: "THAM_DINH", ActorID: id, AggregateVersion: 4}, &HoSoDanhGiaChuyenBuoc{}},
		{TopicAnomalyDetected, DauHieuBatThuong{AlertKey: "k", SignalCode: "CAO_BANG", Severity: "CAO", SubjectType: "DON_VI", SubjectID: id, EvidenceChain: "[]", ModelVersion: "1"}, &DauHieuBatThuong{}},
	}
	for _, c := range cases {
		b, err := Encode(c.topic, c.in)
		if err != nil {
			t.Fatalf("%s: %v", c.topic, err)
		}
		if err := decode(c.topic, b, c.out); err != nil {
			t.Fatalf("%s: %v", c.topic, err)
		}
		if got := reflect.ValueOf(c.out).Elem().Interface(); !reflect.DeepEqual(got, c.in) {
			t.Fatalf("%s: giải mã lệch\n có  %#v\n cần %#v", c.topic, got, c.in)
		}
	}
	if err := decode(TopicTaskAssigned, []byte{1, 2, 3}, &NhiemVuDaGiao{}); err == nil {
		t.Fatal("thân sai lược đồ phải lỗi")
	}
}
