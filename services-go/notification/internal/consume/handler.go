package consume

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/google/uuid"

	"mission/services-go/notification/internal/render"
	"mission/services-go/notification/internal/store"
	vdb "vn.viettel.okrkpi/services-go/vtplatform/db"
	"vn.viettel.okrkpi/services-go/vtplatform/inbox"
)

// ErrPermanent đánh dấu lỗi không thử lại được (thân sai lược đồ, tham số C2); bản ghi chuyển thư chết.
var ErrPermanent = errors.New("consume: lỗi vĩnh viễn")

// Message là một bản ghi Kafka đã tách tiêu đề CloudEvents (chế độ nhị phân, wiki trang 18).
type Message struct {
	Topic       string
	Partition   int32
	Offset      int64
	Headers     map[string]string
	Value       []byte
	Timestamp   time.Time
	Traceparent string
}

// Tiêu đề CloudEvents do Debezium EventRouter và OutboxHeadersTransform đặt (OutboxHeaders.java).
const (
	HeaderEventID    = "id"
	HeaderTenantID   = "tenantid"
	HeaderOccurredAt = "occurredat"
	HeaderTrace      = "traceparent"
)

// Processor xử lý một bản ghi trong giao dịch tenant cùng inbox.
type Processor struct {
	Pool   vdb.TxBeginner
	Writer *store.Writer
	Guard  *inbox.Guard
	Log    *slog.Logger
}

// NewProcessor tạo Processor với inbox notify.inbox_processed.
func NewProcessor(pool vdb.TxBeginner, log *slog.Logger) (*Processor, error) {
	g, err := inbox.NewGuard("notify", ConsumerName)
	if err != nil {
		return nil, err
	}
	if log == nil {
		log = slog.Default()
	}
	return &Processor{Pool: pool, Writer: store.NewWriter(), Guard: g, Log: log}, nil
}

// Result là kết quả xử lý một bản ghi.
type Result struct {
	Outcome inbox.Outcome
	Created int
}

// Process giải mã, kiểm inbox và ghi thông báo. Lỗi bọc ErrPermanent không nên thử lại.
func (p *Processor) Process(ctx context.Context, m Message) (Result, error) {
	eventID := m.Headers[HeaderEventID]
	tenantID := m.Headers[HeaderTenantID]
	if _, err := uuid.Parse(eventID); err != nil {
		return Result{}, fmt.Errorf("%w: thiếu hoặc sai tiêu đề id", ErrPermanent)
	}
	if _, err := uuid.Parse(tenantID); err != nil {
		return Result{}, fmt.Errorf("%w: thiếu hoặc sai tiêu đề tenantid", ErrPermanent)
	}
	occurred := m.Timestamp
	if v := m.Headers[HeaderOccurredAt]; v != "" {
		if t, err := time.Parse(time.RFC3339Nano, v); err == nil {
			occurred = t
		}
	}
	if occurred.IsZero() {
		occurred = time.Now()
	}
	plan, err := p.plan(m.Topic, m.Value)
	if err != nil {
		return Result{}, err
	}
	ev := inbox.IncomingEvent{
		EventID:     eventID,
		TenantID:    tenantID,
		SourceTopic: m.Topic,
		Partition:   int(m.Partition),
		Offset:      m.Offset,
		Traceparent: m.Headers[HeaderTrace],
		Payload:     m.Value,
	}
	// Mỗi sự kiện là một việc cần báo độc lập nên không dùng chặn phiên bản aggregate: sự kiện đến muộn
	// vẫn sinh thông báo; chống trùng dựa vào inbox và UQ (created_day, tenant, source_event_id, người nhận).
	created := 0
	outcome, err := p.Guard.Process(ctx, p.Pool, ev, func(ctx context.Context, tx vdb.Tx, e inbox.IncomingEvent) error {
		recipients := plan.recipients
		if plan.category != "" {
			subs, err := store.SubscribersOf(ctx, tx, plan.category)
			if err != nil {
				return err
			}
			recipients = subs
		}
		code := plan.templateCode
		ok, err := store.TemplateExists(ctx, tx, code)
		if err != nil {
			return err
		}
		if !ok {
			p.Log.Warn("không có mẫu thông báo, dùng mẫu chung", "template", code, "topic", m.Topic)
		}
		for _, r := range uniq(recipients) {
			params := plan.params
			if plan.paramsFor != nil {
				params = plan.paramsFor(r)
			}
			tc := code
			if plan.templateFor != nil {
				tc = plan.templateFor(r)
			}
			ins, err := p.Writer.Insert(ctx, tx, store.NewNotification{
				TenantID:      e.TenantID,
				RecipientID:   r,
				TemplateCode:  tc,
				Params:        params,
				DeepLink:      plan.deepLink,
				Priority:      plan.priority,
				SourceEventID: e.EventID,
				CreatedAt:     occurred,
			})
			if err != nil {
				return err
			}
			if ins {
				created++
			}
		}
		return nil
	})
	if err != nil {
		if errors.Is(err, render.ErrC2Param) {
			return Result{}, fmt.Errorf("%w: %v", ErrPermanent, err)
		}
		return Result{}, err
	}
	return Result{Outcome: outcome, Created: created}, nil
}

type plan struct {
	recipients   []string
	category     string // khi khác rỗng: người nhận là người đăng ký nhóm thông báo này
	templateCode string
	templateFor  func(recipient string) string
	params       map[string]any
	paramsFor    func(recipient string) map[string]any
	deepLink     *string
	priority     string
}

func strp(s string) *string { return &s }

var stageLabels = map[string]string{
	"TU_DE_XUAT": "tự đánh giá",
	"THAM_DINH":  "thẩm định",
	"DE_XUAT":    "đề xuất",
	"QUYET_DINH": "quyết định",
}

var severityLabels = map[string]string{"CAO": "cao", "VUA": "vừa", "THAP": "thấp"}

func vnDate(t time.Time) string { return t.UTC().Format("02/01/2006") }

// plan dựng việc cần báo từ thân sự kiện. Đường dẫn là tuyến của web (suy luận từ cấu hình tuyến).
func (p *Processor) plan(topic string, value []byte) (*plan, error) {
	switch topic {
	case TopicNotifyRequested:
		var e YeuCauThongBao
		if err := decode(topic, value, &e); err != nil {
			return nil, fmt.Errorf("%w: EVT-21: %v", ErrPermanent, err)
		}
		params := map[string]any{}
		if s := strings.TrimSpace(e.Params); s != "" {
			if err := json.Unmarshal([]byte(s), &params); err != nil {
				return nil, fmt.Errorf("%w: EVT-21 params không phải đối tượng JSON", ErrPermanent)
			}
		}
		if err := render.CheckParams(params); err != nil {
			return nil, fmt.Errorf("%w: %v", ErrPermanent, err)
		}
		if e.TemplateCode == "" || len(e.TemplateCode) > 48 {
			return nil, fmt.Errorf("%w: EVT-21 templateCode không hợp lệ", ErrPermanent)
		}
		return &plan{recipients: e.RecipientIDs, templateCode: e.TemplateCode, params: params,
			deepLink: e.DeepLink, priority: priority(e.Priority)}, nil
	case TopicTaskAssigned:
		var e NhiemVuDaGiao
		if err := decode(topic, value, &e); err != nil {
			return nil, fmt.Errorf("%w: EVT-04: %v", ErrPermanent, err)
		}
		// Nhiệm vụ mật mang tiêu đề che "Nhiệm vụ hạn chế" ngay trong sự kiện (lược đồ EVT-04).
		params := map[string]any{"title": e.Title, "dueDate": vnDate(e.DueDate)}
		lead := e.LeadAssigneeID
		recipients := append([]string{lead}, e.AssigneeIDs...)
		pr := "THUONG"
		if e.IsKeyTask {
			pr = "CAO"
		}
		return &plan{recipients: recipients, templateCode: "NV_DUOC_GIAO",
			templateFor: func(r string) string {
				if r == lead {
					return "NV_DUOC_GIAO"
				}
				return "NV_PHOI_HOP"
			},
			params: params, deepLink: strp("/tasks/" + e.TaskID), priority: pr}, nil
	case TopicEvidenceSubmit:
		var e MinhChungDaNop
		if err := decode(topic, value, &e); err != nil {
			return nil, fmt.Errorf("%w: EVT-07: %v", ErrPermanent, err)
		}
		// EVT-07 chỉ mang người nộp; người xác nhận nhận thông báo qua EVT-21 do MOD-03 phát (suy luận).
		return &plan{recipients: []string{e.SubmittedBy}, templateCode: "MC_DA_NOP", params: map[string]any{},
			deepLink: strp("/tasks/" + e.TaskID), priority: "THUONG"}, nil
	case TopicProfileStep:
		var e HoSoDanhGiaChuyenBuoc
		if err := decode(topic, value, &e); err != nil {
			return nil, fmt.Errorf("%w: EVT-11: %v", ErrPermanent, err)
		}
		label, ok := stageLabels[e.Stage]
		if !ok {
			label = "xử lý"
		}
		// EVT-11 chỉ mang người thực hiện bước; người xử lý bước sau nhận EVT-21 của MOD-04 (suy luận).
		return &plan{recipients: []string{e.ActorID}, templateCode: "HS_DA_CHUYEN_BUOC",
			params: map[string]any{"stageLabel": label}, deepLink: strp("/appraisal"), priority: "THUONG"}, nil
	case TopicAnomalyDetected:
		var e DauHieuBatThuong
		if err := decode(topic, value, &e); err != nil {
			return nil, fmt.Errorf("%w: EVT-20: %v", ErrPermanent, err)
		}
		label, ok := severityLabels[e.Severity]
		if !ok {
			label = "chưa xác định"
		}
		pr := "THUONG"
		if e.Severity == "CAO" {
			pr = "CAO"
		}
		// EVT-20 không mang người nhận; báo cho người đã đăng ký nhóm cảnh báo bất thường ở tenant
		// (notify.channel_pref, thường là người giám sát), không báo cho đối tượng bị cảnh báo (suy luận).
		return &plan{category: CategoryAnomalyAlerts, templateCode: "CANH_BAO_BAT_THUONG",
			params: map[string]any{"severityLabel": label}, deepLink: strp("/oversight/alerts"), priority: pr}, nil
	default:
		return nil, fmt.Errorf("%w: topic %q không thuộc SVC-12", ErrPermanent, topic)
	}
}

func priority(p string) string {
	p = strings.TrimSpace(p)
	if p == "" || len(p) > 8 {
		return "THUONG"
	}
	return p
}

func uniq(ids []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		u, err := uuid.Parse(id)
		if err != nil {
			continue
		}
		s := u.String()
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}
