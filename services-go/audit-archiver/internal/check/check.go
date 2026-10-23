// Package check là kiểm tra hằng ngày của kho lưu trữ nhật ký kiểm toán (TL-04 "Bảng lịch sao lưu", dòng
// sys.audit.v1: "Kiểm chữ ký và đếm bản ghi hằng ngày"): kiểm chữ ký, chuỗi kê khai và tệp dữ liệu của các lô trong
// cửa sổ gần đây (mặc định 48 giờ, chồng nhau giữa hai lần chạy nên mọi mắt xích đều được kiểm ít nhất một lần), rồi
// đối chiếu với Kafka: offset nhóm audit-archiver đã xác nhận phải trùng offset kết thúc của kê khai cuối trong kho,
// và Kafka không được xoá bản ghi nào chưa vào kho.
package check

import (
	"context"
	"fmt"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"

	"mission/services-go/audit-archiver/internal/archive"
	"mission/services-go/audit-archiver/internal/metrics"
	"mission/services-go/audit-archiver/internal/verify"
)

// KafkaPartition là trạng thái một phân vùng Kafka khi kiểm.
type KafkaPartition struct {
	PhanVung  int32  `json:"phanVung"`
	DaXacNhan int64  `json:"daXacNhan"`
	KeKhai    string `json:"keKhaiXacNhan"`
	DauLog    int64  `json:"dauLog"`
	CuoiLog   int64  `json:"cuoiLog"`
	DenOffset int64  `json:"denOffsetKho"`
	ChuaLuu   int64  `json:"chuaLuu"`
}

// Result là kết quả cho một topic.
type Result struct {
	Kho   *verify.Report   `json:"kho"`
	Kafka []KafkaPartition `json:"kafka"`
}

// Options là tham số kiểm.
type Options struct {
	Verify verify.Options
	Group  string
}

// Run kiểm một topic. adm có thể nil (bỏ phần đối chiếu Kafka).
func Run(ctx context.Context, src verify.Source, adm *kadm.Client, o Options) (*Result, error) {
	rep, err := verify.Run(ctx, src, o.Verify)
	if err != nil {
		return nil, err
	}
	res := &Result{Kho: rep}
	if adm == nil {
		return res, nil
	}
	topic := o.Verify.Topic
	committed, err := adm.FetchOffsetsForTopics(ctx, o.Group, topic)
	if err != nil {
		return nil, fmt.Errorf("check: đọc offset nhóm: %w", err)
	}
	starts, err := adm.ListStartOffsets(ctx, topic)
	if err != nil {
		return nil, err
	}
	ends, err := adm.ListEndOffsets(ctx, topic)
	if err != nil {
		return nil, err
	}
	tips := rep.Tips()
	ends.Each(func(e kadm.ListedOffset) {
		if e.Err != nil {
			rep.Loi = append(rep.Loi, fmt.Sprintf("Kafka phân vùng %d: %v", e.Partition, e.Err))
			return
		}
		kp := KafkaPartition{PhanVung: e.Partition, CuoiLog: e.Offset, DaXacNhan: -1}
		if s, ok := starts.Lookup(topic, e.Partition); ok {
			kp.DauLog = s.Offset
		}
		if c, ok := committed.Lookup(topic, e.Partition); ok && c.Err == nil && c.At >= 0 {
			kp.DaXacNhan, kp.KeKhai = c.At, c.Metadata
		}
		den := int64(-1)
		if t, ok := tips[e.Partition]; ok && t.Key != "" {
			den = t.DenOffset
		} else if kp.KeKhai != "" {
			// Không có lô nào trong cửa sổ: lấy kê khai cuối theo metadata của offset đã xác nhận.
			b, err := src.Get(ctx, kp.KeKhai)
			if err != nil {
				rep.Loi = append(rep.Loi, fmt.Sprintf("phân vùng %d: kê khai đã xác nhận %s không đọc được", e.Partition, kp.KeKhai))
			} else if m, err := archive.ParseManifest(b); err == nil {
				den = m.DenOffset
			}
		}
		kp.DenOffset = den
		switch {
		case den < 0 && kp.CuoiLog > kp.DauLog:
			rep.Loi = append(rep.Loi, fmt.Sprintf("phân vùng %d: Kafka có bản ghi nhưng kho chưa có lô nào", e.Partition))
		case den >= 0 && kp.DaXacNhan > den:
			rep.Loi = append(rep.Loi, fmt.Sprintf("phân vùng %d: offset đã xác nhận %d vượt offset kết thúc trong kho %d (bản ghi được xác nhận mà không có trong kho)", e.Partition, kp.DaXacNhan, den))
		case den >= 0 && kp.DaXacNhan >= 0 && kp.DaXacNhan < den:
			rep.CanhBao = append(rep.CanhBao, fmt.Sprintf("phân vùng %d: kho đi trước offset đã xác nhận (%d < %d), xác nhận sẽ được bù khi dịch vụ chạy tiếp", e.Partition, kp.DaXacNhan, den))
		}
		if den >= 0 && kp.DauLog > den {
			rep.Loi = append(rep.Loi, fmt.Sprintf("phân vùng %d: Kafka đã xoá offset %d..%d chưa vào kho", e.Partition, den, kp.DauLog-1))
		}
		if den >= 0 {
			kp.ChuaLuu = kp.CuoiLog - den
		}
		res.Kafka = append(res.Kafka, kp)
	})
	return res, nil
}

// Record ghi kết quả thành chỉ số (đẩy lên Pushgateway ở job hằng ngày).
func Record(m *metrics.Registry, topic string, res *Result, now time.Time) {
	m.Describe("audit_archiver_kiem_tra_lan_cuoi_giay", "gauge", "Thời điểm Unix kết thúc lần kiểm hằng ngày")
	m.Describe("audit_archiver_kiem_tra_dat", "gauge", "1 khi lần kiểm cuối đạt")
	m.Describe("audit_archiver_kiem_tra_loi", "gauge", "Số lỗi của lần kiểm cuối")
	m.Describe("audit_archiver_kiem_tra_tep", "gauge", "Số tệp đã kiểm ở lần cuối")
	m.Describe("audit_archiver_kiem_tra_ban_ghi", "gauge", "Số bản ghi đã đếm ở lần cuối")
	m.Describe("audit_archiver_chua_luu_ban_ghi", "gauge", "Số offset trên Kafka chưa vào kho theo phân vùng")
	m.Set("audit_archiver_kiem_tra_lan_cuoi_giay", float64(now.Unix()), "topic", topic)
	ok := 0.0
	if res.Kho.OK() {
		ok = 1
	}
	m.Set("audit_archiver_kiem_tra_dat", ok, "topic", topic)
	m.Set("audit_archiver_kiem_tra_loi", float64(len(res.Kho.Loi)), "topic", topic)
	m.Set("audit_archiver_kiem_tra_tep", float64(res.Kho.SoTep), "topic", topic)
	m.Set("audit_archiver_kiem_tra_ban_ghi", float64(res.Kho.SoBanGhi), "topic", topic)
	for _, k := range res.Kafka {
		m.Set("audit_archiver_chua_luu_ban_ghi", float64(k.ChuaLuu), "topic", topic, "phan_vung", fmt.Sprint(k.PhanVung))
	}
}
