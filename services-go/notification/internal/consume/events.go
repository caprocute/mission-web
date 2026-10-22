// Package consume nhận các sự kiện cần báo của SVC-12 (TL-01 "SVC-12 notification-service"): EVT-21
// YeuCauThongBao từ mọi module và EVT-04, EVT-07, EVT-11, EVT-20 trực tiếp; giải mã thân Avro theo lược đồ
// trong contracts/events/avro (bản chép ở schemas/, kiểm thử chặn lệch), xác định người nhận, chọn mẫu và
// ghi thông báo trong cùng giao dịch với inbox idempotent (TL-03 "Inbox idempotent"). Không phát sự kiện.
package consume

import (
	"embed"
	"fmt"
	"math/big"
	"time"

	"github.com/linkedin/goavro/v2"
)

// Các topic SVC-12 tiêu thụ (baseline mục 7).
const (
	TopicNotifyRequested  = "notify.message.requested.v1"        // EVT-21
	TopicTaskAssigned     = "task.task.assigned.v1"              // EVT-04
	TopicEvidenceSubmit   = "product.evidence.submitted.v1"      // EVT-07
	TopicProfileStep      = "appraisal.profile.stepcompleted.v1" // EVT-11
	TopicAnomalyDetected  = "analytics.anomaly.detected.v1"      // EVT-20
	ConsumerName          = "notification-service"
	CategoryAnomalyAlerts = "CANH_BAO_BAT_THUONG"
)

// Topics là danh sách topic đăng ký của consumer group notification-service.
var Topics = []string{TopicNotifyRequested, TopicTaskAssigned, TopicEvidenceSubmit, TopicProfileStep, TopicAnomalyDetected}

//go:embed schemas/*.avsc
var schemaFS embed.FS

// SchemaFiles ánh xạ topic với tệp lược đồ chép từ contracts/events/avro.
var SchemaFiles = map[string]string{
	TopicNotifyRequested: "notify/YeuCauThongBao.avsc",
	TopicTaskAssigned:    "task/NhiemVuDaGiao.avsc",
	TopicEvidenceSubmit:  "product/MinhChungDaNop.avsc",
	TopicProfileStep:     "appraisal/HoSoDanhGiaChuyenBuoc.avsc",
	TopicAnomalyDetected: "analytics/DauHieuBatThuong.avsc",
}

var codecs = map[string]*goavro.Codec{}

func init() {
	for topic, rel := range SchemaFiles {
		b, err := schemaFS.ReadFile("schemas/" + baseName(rel))
		if err != nil {
			panic(err)
		}
		c, err := goavro.NewCodec(string(b))
		if err != nil {
			panic(fmt.Sprintf("consume: lược đồ %s: %v", rel, err))
		}
		codecs[topic] = c
	}
}

func baseName(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' {
			return p[i+1:]
		}
	}
	return p
}

// YeuCauThongBao là thân EVT-21.
type YeuCauThongBao struct {
	RecipientIDs     []string
	TemplateCode     string
	Params           string
	DeepLink         *string
	Priority         string
	AggregateVersion int32
}

// NhiemVuDaGiao là thân EVT-04.
type NhiemVuDaGiao struct {
	TaskID           string
	PlanItemID       *string
	Title            string
	LeadAssigneeID   string
	AssigneeIDs      []string
	DueDate          time.Time
	Weight           *big.Rat
	IsKeyTask        bool
	IsSecret         bool
	AggregateVersion int32
}

// MinhChungDaNop là thân EVT-07.
type MinhChungDaNop struct {
	EvidenceID       string
	VersionID        string
	DeliverableID    string
	TaskID           string
	ObjectSha256     []byte
	AiConfirmed      bool
	SubmittedBy      string
	AggregateVersion int32
}

// HoSoDanhGiaChuyenBuoc là thân EVT-11.
type HoSoDanhGiaChuyenBuoc struct {
	ProfileID        string
	FromStatus       *string
	ToStatus         string
	Stage            string
	ActorID          string
	AggregateVersion int32
}

// DauHieuBatThuong là thân EVT-20.
type DauHieuBatThuong struct {
	AlertKey      string
	SignalCode    string
	Severity      string
	SubjectType   string
	SubjectID     string
	EvidenceChain string
	ModelVersion  string
}

// Encode tuần tự hoá thân sự kiện theo lược đồ của topic (dùng cho kiểm thử và công cụ dev).
func Encode(topic string, v any) ([]byte, error) {
	c, ok := codecs[topic]
	if !ok {
		return nil, fmt.Errorf("consume: topic %q không thuộc SVC-12", topic)
	}
	var native map[string]any
	switch e := v.(type) {
	case YeuCauThongBao:
		native = map[string]any{"recipientIds": anys(e.RecipientIDs), "templateCode": e.TemplateCode,
			"params": e.Params, "deepLink": union("string", e.DeepLink), "priority": e.Priority,
			"aggregateVersion": e.AggregateVersion}
	case NhiemVuDaGiao:
		var w any
		if e.Weight != nil {
			w = goavro.Union("bytes.decimal", e.Weight)
		}
		native = map[string]any{"taskId": e.TaskID, "planItemId": union("string", e.PlanItemID),
			"title": e.Title, "leadAssigneeId": e.LeadAssigneeID, "assigneeIds": anys(e.AssigneeIDs),
			"dueDate": e.DueDate, "weight": w, "isKeyTask": e.IsKeyTask, "isSecret": e.IsSecret,
			"aggregateVersion": e.AggregateVersion}
	case MinhChungDaNop:
		native = map[string]any{"evidenceId": e.EvidenceID, "versionId": e.VersionID, "deliverableId": e.DeliverableID,
			"taskId": e.TaskID, "objectSha256": e.ObjectSha256, "aiConfirmed": e.AiConfirmed,
			"submittedBy": e.SubmittedBy, "aggregateVersion": e.AggregateVersion}
	case HoSoDanhGiaChuyenBuoc:
		native = map[string]any{"profileId": e.ProfileID, "fromStatus": union("string", e.FromStatus),
			"toStatus": e.ToStatus, "stage": e.Stage, "actorId": e.ActorID, "aggregateVersion": e.AggregateVersion}
	case DauHieuBatThuong:
		native = map[string]any{"alertKey": e.AlertKey, "signalCode": e.SignalCode, "severity": e.Severity,
			"subjectType": e.SubjectType, "subjectId": e.SubjectID, "evidenceChain": e.EvidenceChain,
			"modelVersion": e.ModelVersion}
	default:
		return nil, fmt.Errorf("consume: kiểu %T không hỗ trợ", v)
	}
	return c.BinaryFromNative(nil, native)
}

func union(name string, s *string) any {
	if s == nil {
		return nil
	}
	return goavro.Union(name, *s)
}

func anys(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}

// record là bản ghi Avro đã giải mã ở dạng gốc của goavro.
type record map[string]any

func (r record) str(k string) string { s, _ := r[k].(string); return s }

func (r record) b(k string) bool { v, _ := r[k].(bool); return v }

func (r record) i32(k string) int32 { v, _ := r[k].(int32); return v }

// opt mở giá trị union ["null", T] mà goavro trả dạng map một khoá.
func (r record) opt(k string) any {
	if m, ok := r[k].(map[string]any); ok {
		for _, v := range m {
			return v
		}
	}
	return r[k]
}

func (r record) optStr(k string) *string {
	if s, ok := r.opt(k).(string); ok {
		return &s
	}
	return nil
}

func (r record) strs(k string) []string {
	arr, _ := r[k].([]any)
	out := make([]string, 0, len(arr))
	for _, v := range arr {
		if s, ok := v.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func decode(topic string, data []byte, v any) error {
	c, ok := codecs[topic]
	if !ok {
		return fmt.Errorf("consume: topic %q không thuộc SVC-12", topic)
	}
	native, rest, err := c.NativeFromBinary(data)
	if err != nil {
		return err
	}
	if len(rest) != 0 {
		return fmt.Errorf("consume: thân sự kiện thừa %d byte", len(rest))
	}
	m, ok := native.(map[string]any)
	if !ok {
		return fmt.Errorf("consume: thân sự kiện không phải bản ghi")
	}
	r := record(m)
	switch e := v.(type) {
	case *YeuCauThongBao:
		*e = YeuCauThongBao{RecipientIDs: r.strs("recipientIds"), TemplateCode: r.str("templateCode"),
			Params: r.str("params"), DeepLink: r.optStr("deepLink"), Priority: r.str("priority"),
			AggregateVersion: r.i32("aggregateVersion")}
	case *NhiemVuDaGiao:
		due, _ := r["dueDate"].(time.Time)
		w, _ := r.opt("weight").(*big.Rat)
		*e = NhiemVuDaGiao{TaskID: r.str("taskId"), PlanItemID: r.optStr("planItemId"), Title: r.str("title"),
			LeadAssigneeID: r.str("leadAssigneeId"), AssigneeIDs: r.strs("assigneeIds"), DueDate: due, Weight: w,
			IsKeyTask: r.b("isKeyTask"), IsSecret: r.b("isSecret"), AggregateVersion: r.i32("aggregateVersion")}
	case *MinhChungDaNop:
		sha, _ := r["objectSha256"].([]byte)
		*e = MinhChungDaNop{EvidenceID: r.str("evidenceId"), VersionID: r.str("versionId"),
			DeliverableID: r.str("deliverableId"), TaskID: r.str("taskId"), ObjectSha256: sha,
			AiConfirmed: r.b("aiConfirmed"), SubmittedBy: r.str("submittedBy"), AggregateVersion: r.i32("aggregateVersion")}
	case *HoSoDanhGiaChuyenBuoc:
		*e = HoSoDanhGiaChuyenBuoc{ProfileID: r.str("profileId"), FromStatus: r.optStr("fromStatus"),
			ToStatus: r.str("toStatus"), Stage: r.str("stage"), ActorID: r.str("actorId"),
			AggregateVersion: r.i32("aggregateVersion")}
	case *DauHieuBatThuong:
		*e = DauHieuBatThuong{AlertKey: r.str("alertKey"), SignalCode: r.str("signalCode"), Severity: r.str("severity"),
			SubjectType: r.str("subjectType"), SubjectID: r.str("subjectId"), EvidenceChain: r.str("evidenceChain"),
			ModelVersion: r.str("modelVersion")}
	default:
		return fmt.Errorf("consume: kiểu %T không hỗ trợ", v)
	}
	return nil
}
