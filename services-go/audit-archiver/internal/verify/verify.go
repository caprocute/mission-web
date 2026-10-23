// Package verify kiểm kho lưu trữ nhật ký kiểm toán một cách độc lập (INF-24): chữ ký từng kê khai bằng khoá công
// khai, chuỗi băm giữa các kê khai của một phân vùng, dải offset liên tục, SHA-256 và số bản ghi của từng tệp
// Parquet. Gói chỉ cần quyền đọc và liệt kê bucket cùng tệp khoá công khai, không cần OpenBao hay Kafka; dùng chung
// cho công cụ audit-verify của kiểm toán viên và job kiểm hằng ngày.
package verify

import (
	"context"
	"crypto/ed25519"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"mission/services-go/audit-archiver/internal/archive"
	"mission/services-go/audit-archiver/internal/transit"
)

// Source là kho chỉ đọc.
type Source interface {
	List(ctx context.Context, prefix, startAfter string, fn func(key string) error) error
	Get(ctx context.Context, key string) ([]byte, error)
}

// Options là phạm vi kiểm.
type Options struct {
	Cell  string
	Topic string
	Keys  map[int]ed25519.PublicKey
	// From và To giới hạn theo giờ của khoá (giờ bản ghi đầu lô, UTC). From rỗng là kiểm toàn bộ chuỗi từ đầu: khi
	// đó kê khai đầu tiên của mỗi phân vùng phải là mắt xích gốc.
	From, To time.Time
	// SkipData bỏ qua tải tệp Parquet (chỉ kiểm chữ ký và chuỗi).
	SkipData bool
	// InFlight là khoảng thời gian gần hiện tại mà lô có thể đang ghi dở (kê khai chưa có chữ ký, chưa có tệp dữ
	// liệu); trong khoảng này thiếu tệp là cảnh báo, ngoài khoảng là lỗi.
	InFlight time.Duration
	Now      func() time.Time
}

// Tip là kê khai cuối của một phân vùng trong phạm vi kiểm.
type Tip struct {
	Key       string
	DenOffset int64
	TaoLuc    time.Time
}

// PartitionResult là kết quả theo phân vùng.
type PartitionResult struct {
	PhanVung  int32 `json:"phanVung"`
	SoTep     int   `json:"soTep"`
	SoBanGhi  int64 `json:"soBanGhi"`
	TuOffset  int64 `json:"tuOffset"`
	DenOffset int64 `json:"denOffset"`
	// KhoangTrong là số offset trong dải không có bản ghi (bản ghi điều khiển giao dịch, nén log).
	KhoangTrong int64  `json:"khoangTrong"`
	KeKhaiCuoi  string `json:"keKhaiCuoi"`
	tip         Tip
}

// Report là kết quả kiểm.
type Report struct {
	Cell     string                     `json:"cell"`
	Topic    string                     `json:"topic"`
	TuGio    *time.Time                 `json:"tuGio,omitempty"`
	DenGio   *time.Time                 `json:"denGio,omitempty"`
	SoTep    int                        `json:"soTep"`
	SoBanGhi int64                      `json:"soBanGhi"`
	PhanVung map[int32]*PartitionResult `json:"phanVung"`
	Loi      []string                   `json:"loi"`
	CanhBao  []string                   `json:"canhBao"`
}

// OK cho biết không có lỗi.
func (r *Report) OK() bool { return len(r.Loi) == 0 }

// Tips trả kê khai cuối theo phân vùng.
func (r *Report) Tips() map[int32]Tip {
	out := map[int32]Tip{}
	for p, pr := range r.PhanVung {
		out[p] = pr.tip
	}
	return out
}

func (r *Report) loi(format string, a ...any) { r.Loi = append(r.Loi, fmt.Sprintf(format, a...)) }
func (r *Report) canhBao(format string, a ...any) {
	r.CanhBao = append(r.CanhBao, fmt.Sprintf(format, a...))
}

type entry struct {
	from     int64
	hour     time.Time
	base     string
	manifest bool
	sig      bool
	data     bool
}

// hourOf đọc giờ từ khoá <prefix>yyyy/MM/dd/HH/<tên>.
func hourOf(prefix, key string) (time.Time, bool) {
	rest := strings.TrimPrefix(key, prefix)
	if len(rest) < 14 {
		return time.Time{}, false
	}
	t, err := time.Parse("2006/01/02/15", rest[:13])
	return t, err == nil
}

// Run kiểm kho theo Options.
func Run(ctx context.Context, src Source, o Options) (*Report, error) {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.InFlight == 0 {
		o.InFlight = 15 * time.Minute
	}
	rep := &Report{Cell: o.Cell, Topic: o.Topic, PhanVung: map[int32]*PartitionResult{}, Loi: []string{}, CanhBao: []string{}}
	prefix := archive.Prefix(o.Cell, o.Topic)
	parts := map[int32]map[int64]*entry{}
	collect := func(key string) error {
		ki, ok := archive.ParseKey(key)
		h, ok2 := hourOf(prefix, key)
		if !ok || !ok2 {
			rep.loi("đối tượng lạ trong kho: %s", key)
			return nil
		}
		if !o.From.IsZero() && (h.Before(o.From.Truncate(time.Hour)) || (!o.To.IsZero() && h.After(o.To))) {
			return nil
		}
		m := parts[ki.Partition]
		if m == nil {
			m = map[int64]*entry{}
			parts[ki.Partition] = m
		}
		e := m[ki.From]
		base := strings.TrimSuffix(key, "."+ki.Kind)
		if e == nil {
			e = &entry{from: ki.From, hour: h, base: base}
			m[ki.From] = e
		} else if e.base != base {
			rep.loi("phân vùng %d có hai lô cùng offset bắt đầu %d ở hai giờ khác nhau", ki.Partition, ki.From)
			return nil
		}
		switch ki.Kind {
		case "json":
			e.manifest = true
		case "sig":
			e.sig = true
		case "parquet":
			e.data = true
		}
		return nil
	}
	if o.From.IsZero() {
		if err := src.List(ctx, prefix, "", collect); err != nil {
			return nil, err
		}
	} else {
		f := o.From.UTC()
		rep.TuGio = &f
		to := o.To
		if to.IsZero() {
			to = o.Now()
		}
		to = to.UTC()
		rep.DenGio = &to
		for d := time.Date(f.Year(), f.Month(), f.Day(), 0, 0, 0, 0, time.UTC); !d.After(to); d = d.AddDate(0, 0, 1) {
			if err := src.List(ctx, prefix+d.Format("2006/01/02/"), "", collect); err != nil {
				return nil, err
			}
		}
	}
	ps := make([]int32, 0, len(parts))
	for p := range parts {
		ps = append(ps, p)
	}
	sort.Slice(ps, func(i, j int) bool { return ps[i] < ps[j] })
	for _, p := range ps {
		if err := verifyPartition(ctx, src, o, rep, p, parts[p]); err != nil {
			return nil, err
		}
	}
	return rep, nil
}

func verifyPartition(ctx context.Context, src Source, o Options, rep *Report, p int32, m map[int64]*entry) error {
	es := make([]*entry, 0, len(m))
	for _, e := range m {
		es = append(es, e)
	}
	sort.Slice(es, func(i, j int) bool { return es[i].from < es[j].from })
	pr := &PartitionResult{PhanVung: p}
	rep.PhanVung[p] = pr
	recent := func(e *entry) bool { return o.Now().Sub(e.hour) < time.Hour+o.InFlight }
	var prevKey string
	var prevBytes []byte
	var prev archive.Manifest
	for i, e := range es {
		mk := e.base + ".json"
		if !e.manifest {
			// Thứ tự ghi là kê khai, chữ ký rồi mới đến dữ liệu, nên tệp dữ liệu không có kê khai không thể do
			// audit-archiver tạo ra.
			rep.loi("phân vùng %d: tệp %s.parquet không có kê khai", p, e.base)
			continue
		}
		mb, err := src.Get(ctx, mk)
		if err != nil {
			return fmt.Errorf("verify: đọc %s: %w", mk, err)
		}
		man, err := archive.ParseManifest(mb)
		if err != nil {
			rep.loi("%s: %v", mk, err)
			continue
		}
		if !e.sig {
			if recent(e) {
				rep.canhBao("%s chưa có chữ ký (lô có thể đang ghi)", mk)
			} else {
				rep.loi("%s không có chữ ký", mk)
			}
		} else {
			sig, err := src.Get(ctx, archive.SigKeyFor(mk))
			if err != nil {
				return fmt.Errorf("verify: đọc chữ ký %s: %w", mk, err)
			}
			if err := transit.Verify(o.Keys, mb, string(sig)); err != nil {
				rep.loi("%s: %v", mk, err)
			}
		}
		if man.Cell != o.Cell || man.Topic != o.Topic || man.PhanVung != p || man.TuOffset != e.from || man.TepDuLieu != e.base+".parquet" {
			rep.loi("%s: nội dung kê khai không khớp khoá đối tượng", mk)
		}
		// Chuỗi băm và dải offset liên tục.
		switch {
		case i > 0 && prevKey != "":
			if man.KeKhaiTruoc != prevKey || man.KeKhaiTruocSha256 != archive.SHA256Hex(prevBytes) {
				rep.loi("%s: chuỗi kê khai đứt (kê khai trước ghi %s, thực tế %s)", mk, man.KeKhaiTruoc, prevKey)
			}
			if man.TuOffset != prev.DenOffset {
				rep.loi("%s: dải offset không liên tục (bắt đầu %d, lô trước kết thúc %d)", mk, man.TuOffset, prev.DenOffset)
			}
		case man.KeKhaiTruoc == "":
			if o.From.IsZero() && man.TuOffset != 0 {
				rep.canhBao("phân vùng %d: chuỗi bắt đầu ở offset %d, không phải 0", p, man.TuOffset)
			}
		default:
			// Kê khai đầu phạm vi: đọc mắt xích trước theo khoá ghi trong kê khai.
			pb, err := src.Get(ctx, man.KeKhaiTruoc)
			switch {
			case err != nil:
				rep.loi("%s: không đọc được kê khai trước %s: %v", mk, man.KeKhaiTruoc, err)
			case archive.SHA256Hex(pb) != man.KeKhaiTruocSha256:
				rep.loi("%s: băm kê khai trước %s không khớp", mk, man.KeKhaiTruoc)
			default:
				pm, err := archive.ParseManifest(pb)
				if err != nil || pm.DenOffset != man.TuOffset || pm.PhanVung != p {
					rep.loi("%s: kê khai trước %s không nối đúng dải offset", mk, man.KeKhaiTruoc)
				}
				if sig, err := src.Get(ctx, archive.SigKeyFor(man.KeKhaiTruoc)); err != nil || transit.Verify(o.Keys, pb, string(sig)) != nil {
					rep.loi("%s: chữ ký kê khai trước %s không hợp lệ", mk, man.KeKhaiTruoc)
				}
			}
			if o.From.IsZero() {
				rep.loi("%s: thiếu các kê khai trước trong kho", mk)
			}
		}
		if !e.data {
			if recent(e) {
				rep.canhBao("%s chưa có tệp dữ liệu (lô có thể đang ghi)", mk)
			} else {
				rep.loi("%s: thiếu tệp dữ liệu", mk)
			}
		} else if !o.SkipData {
			checkData(ctx, src, rep, man)
		}
		if pr.SoTep == 0 {
			pr.TuOffset = man.TuOffset
		}
		pr.SoTep++
		pr.SoBanGhi += man.SoBanGhi
		pr.KhoangTrong += (man.DenOffset - man.TuOffset) - man.SoBanGhi
		pr.DenOffset = man.DenOffset
		pr.KeKhaiCuoi = mk
		pr.tip = Tip{Key: mk, DenOffset: man.DenOffset, TaoLuc: man.TaoLuc}
		rep.SoTep++
		rep.SoBanGhi += man.SoBanGhi
		prevKey, prevBytes, prev = mk, mb, man
	}
	return nil
}

func checkData(ctx context.Context, src Source, rep *Report, man archive.Manifest) {
	data, err := src.Get(ctx, man.TepDuLieu)
	if err != nil {
		rep.loi("%s: không đọc được: %v", man.TepDuLieu, err)
		return
	}
	if int64(len(data)) != man.KichThuoc || archive.SHA256Hex(data) != man.Sha256 {
		rep.loi("%s: SHA-256 hoặc kích thước không khớp kê khai (tệp đã bị sửa)", man.TepDuLieu)
		return
	}
	rows, err := archive.DecodeParquet(data)
	if err != nil {
		rep.loi("%s: %v", man.TepDuLieu, err)
		return
	}
	if int64(len(rows)) != man.SoBanGhi {
		rep.loi("%s: có %d bản ghi, kê khai ghi %d", man.TepDuLieu, len(rows), man.SoBanGhi)
		return
	}
	for i, r := range rows {
		if r.Topic != man.Topic || r.Partition != man.PhanVung || r.Offset < man.TuOffset || r.Offset >= man.DenOffset ||
			(i > 0 && r.Offset <= rows[i-1].Offset) {
			rep.loi("%s: bản ghi %d sai topic, phân vùng hoặc offset", man.TepDuLieu, i)
			return
		}
	}
	if rows[0].Offset != man.OffsetDau || rows[len(rows)-1].Offset != man.OffsetCuoi {
		rep.loi("%s: offset đầu cuối không khớp kê khai", man.TepDuLieu)
	}
}

// ErrFailed báo kiểm không đạt (dùng làm mã thoát).
var ErrFailed = errors.New("kiểm kho lưu trữ không đạt")
