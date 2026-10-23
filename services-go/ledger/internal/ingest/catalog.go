// Package ingest hiện thực ledger-ingester của SVC-09 (DV-09-02): consumer franz-go nhóm
// ledger-ingester đọc sys.ledger.append.v1, gom sự kiện theo (tenant_id, chain_partition) thành lô
// tối đa 200 bản ghi, nối chuỗi băm SHA-256 trong một giao dịch (TL-03 "Ghi một bản ghi"), cùng hai
// bản ghi đặc biệt TENANT_MOVED_IN và DANH_DAU_SU_CO chỉ gọi qua lệnh quản trị (TL-02b QT-13).
package ingest

import (
	"errors"
	"fmt"
	"strings"
)

// Hằng số của luồng ghi.
const (
	// TopicLedgerAppend là topic kỹ thuật gom sự kiện pháp lý cần ghi sổ, khoá tenant:partition.
	TopicLedgerAppend = "sys.ledger.append.v1"
	// ConsumerGroupLedgerIngester là consumer group của luồng ghi sổ cái.
	ConsumerGroupLedgerIngester = "ledger-ingester"
	// MaxBatchSize là số bản ghi tối đa của một lô ghi trong một giao dịch.
	MaxBatchSize = 200

	// EventTypeTenantMovedIn mở phân đoạn chuỗi ở cell đích, nối tiếp entry_hash cuối của cell nguồn.
	EventTypeTenantMovedIn = "TENANT_MOVED_IN"
	// EventTypeIncidentRecovery là bản ghi "đánh dấu sự cố" mở phân đoạn mới sau khi phá chuỗi.
	EventTypeIncidentRecovery = "DANH_DAU_SU_CO"
)

var (
	// ErrPartitionLocked: chuỗi đang bị khoá ghi do phá chuỗi (BR-13-11), consumer dừng chuỗi này.
	ErrPartitionLocked = errors.New("ingest: chuỗi băm đang bị khoá ghi do sự cố (BR-13-11)")
	// ErrC2CleartextInMetadata: metadata mang trường ngoài danh sách C1 cho phép (ADR-06).
	ErrC2CleartextInMetadata = errors.New("ingest: metadata chứa trường dữ liệu C2/C3 không được phép (ADR-06)")
	// ErrEventNotInLegalCatalog: sự kiện không thuộc danh mục sự kiện pháp lý.
	ErrEventNotInLegalCatalog = errors.New("ingest: sự kiện không thuộc danh mục sự kiện pháp lý")
	// ErrUnauthorizedAdminCommand: lệnh quản trị thiếu người thực hiện hoặc lý do.
	ErrUnauthorizedAdminCommand = errors.New("ingest: lệnh quản trị phải có người thực hiện và lý do")
	// ErrInvalidEvent: sự kiện thiếu trường bắt buộc hoặc sai phân vùng chuỗi; lỗi vĩnh viễn.
	ErrInvalidEvent = errors.New("ingest: sự kiện ghi sổ không hợp lệ")
)

// legalEventTypes ánh xạ topic nghiệp vụ (cột event_type) của sự kiện có giá trị pháp lý với mã EVT
// theo danh mục contracts/events/catalog.yaml (EVT-01..05, 07..09, 11..13, 18, 22, 25) và sys.audit.v1.
var legalEventTypes = map[string]string{
	"okr.objective.approved.v1":          "EVT-01",
	"okr.keyresult.checkedin.v1":         "EVT-02",
	"task.plan.approved.v1":              "EVT-03",
	"task.task.assigned.v1":              "EVT-04",
	"task.task.progressed.v1":            "EVT-05",
	"product.evidence.submitted.v1":      "EVT-07",
	"product.evidence.accepted.v1":       "EVT-08",
	"config.period.locked.v1":            "EVT-09",
	"appraisal.profile.stepcompleted.v1": "EVT-11",
	"appraisal.rating.decided.v1":        "EVT-12",
	"policy.pack.published.v1":           "EVT-13",
	"esign.signature.completed.v1":       "EVT-18",
	"oversight.period.reopened.v1":       "EVT-22",
	"oversight.complaint.filed.v1":       "EVT-25",
	"sys.audit.v1":                       "",
	EventTypeTenantMovedIn:               "",
	EventTypeIncidentRecovery:            "",
}

// adminOnly là các loại chỉ ghi được qua lệnh quản trị, không nhận từ topic.
var adminOnly = map[string]bool{EventTypeTenantMovedIn: true, EventTypeIncidentRecovery: true}

// allowedC1MetadataKeys là các khoá metadata được phép (trạng thái trước/sau rút gọn mức C1 và mã
// tham chiếu) khi phong bì sự kiện phân loại C2 hoặc C3 (ADR-06).
var allowedC1MetadataKeys = map[string]bool{
	"before_status":          true,
	"after_status":           true,
	"ref_id":                 true,
	"reason_code":            true,
	"seal_id":                true,
	"origin_cell":            true,
	"origin_last_entry_hash": true,
	"incident_ref":           true,
	"last_valid_seal_id":     true,
	"classification":         true,
	"schema_version":         true,
}

// IsLegalEvent cho biết event_type có thuộc danh mục phải ghi sổ cái không.
func IsLegalEvent(eventType string) bool {
	_, ok := legalEventTypes[strings.TrimSpace(eventType)]
	return ok
}

// EventCode trả mã EVT của event_type, rỗng với sys.audit.v1 và bản ghi đặc biệt.
func EventCode(eventType string) string {
	return legalEventTypes[strings.TrimSpace(eventType)]
}

// ValidateMetadataC2 chặn metadata mang nội dung ngoài danh sách C1 cho phép khi sự kiện C2/C3,
// và chặn giá trị lồng hoặc chuỗi dài hơn 256 ký tự ở mọi mức phân loại.
func ValidateMetadataC2(classification string, metadata map[string]any) error {
	cls := strings.ToUpper(strings.TrimSpace(classification))
	for k, v := range metadata {
		if (cls == "C2" || cls == "C3") && !allowedC1MetadataKeys[k] {
			return fmt.Errorf("%w: khoá %q không nằm trong danh sách C1 cho phép của sự kiện %s",
				ErrC2CleartextInMetadata, k, cls)
		}
		switch val := v.(type) {
		case map[string]any, []any:
			return fmt.Errorf("%w: giá trị lồng tại khoá %q", ErrC2CleartextInMetadata, k)
		case string:
			if len([]rune(val)) > 256 {
				return fmt.Errorf("%w: giá trị tại khoá %q dài quá 256 ký tự", ErrC2CleartextInMetadata, k)
			}
		}
	}
	return nil
}
