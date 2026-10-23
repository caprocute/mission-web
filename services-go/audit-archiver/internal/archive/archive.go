// Package archive định nghĩa dạng lưu trữ bất biến của nhật ký kiểm toán (INF-24, TL-04 "Kafka: MirrorMaker 2"
// đoạn audit-archiver, "Bảng lịch sao lưu" dòng sys.audit.v1): mỗi lô là một tệp Parquet nén zstd có lược đồ cố
// định giữ nguyên thân Avro và header phong bì, kèm một tệp kê khai JSON nối chuỗi băm với kê khai trước và một
// tệp chữ ký của kê khai.
//
// Khoá đối tượng (suy luận từ thẻ INF-24, thêm đoạn topic vì một dịch vụ có thể lưu nhiều topic):
//
//	<cell>/<topic>/<yyyy>/<MM>/<dd>/<HH>/<phân vùng 4 số>-<offset bắt đầu 20 số>.parquet
//	<cell>/<topic>/<yyyy>/<MM>/<dd>/<HH>/<phân vùng 4 số>-<offset bắt đầu 20 số>.json   (kê khai)
//	<cell>/<topic>/<yyyy>/<MM>/<dd>/<HH>/<phân vùng 4 số>-<offset bắt đầu 20 số>.sig    (chữ ký kê khai)
//
// Giờ trong khoá là giờ UTC của bản ghi đầu lô. Số được đệm 0 để thứ tự từ điển của khoá trùng thứ tự thời gian và
// offset, nhờ đó liệt kê với StartAfter tìm được các lô ghi sau một kê khai đã biết.
package archive

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/parquet-go/parquet-go"
	"github.com/parquet-go/parquet-go/compress/zstd"
)

// ManifestVersion là phiên bản dạng kê khai; đổi lược đồ Parquet hay trường kê khai thì tăng số này.
const ManifestVersion = 1

// Header là một header Kafka của bản ghi (phong bì CloudEvents: ce_id, ce_type, tenant_id, traceparent…).
type Header struct {
	Key   string `parquet:"key"`
	Value []byte `parquet:"value"`
}

// Row là một bản ghi Kafka trong tệp Parquet. Value là thân Avro nguyên vẹn như trên topic (kèm tiền tố mã lược
// đồ của registry nếu có), không giải mã để tệp lưu trữ là bản sao đúng từng byte.
type Row struct {
	Topic     string    `parquet:"topic,dict"`
	Partition int32     `parquet:"partition"`
	Offset    int64     `parquet:"offset"`
	Timestamp time.Time `parquet:"timestamp,timestamp(millisecond)"`
	Key       []byte    `parquet:"key,optional"`
	Value     []byte    `parquet:"value"`
	Headers   []Header  `parquet:"headers,list"`
}

// Manifest là kê khai của một lô. Chữ ký phủ đúng các byte JSON đã lưu, nên kê khai không bao giờ được dựng lại
// sau khi đã ghi; khi khôi phục chỉ dựng lại tệp Parquet và so SHA-256 với kê khai.
type Manifest struct {
	PhienBan int    `json:"phienBan"`
	Cell     string `json:"cell"`
	Topic    string `json:"topic"`
	PhanVung int32  `json:"phanVung"`
	// TuOffset là vị trí đọc khi bắt đầu lô (gồm), DenOffset là vị trí đọc sau lô (không gồm). Hai lô liền nhau
	// của một phân vùng thoả TuOffset sau = DenOffset trước; offset trống giữa chừng (bản ghi điều khiển giao dịch,
	// nén log) nằm trong dải nhưng không có bản ghi.
	TuOffset     int64     `json:"tuOffset"`
	DenOffset    int64     `json:"denOffset"`
	OffsetDau    int64     `json:"offsetDau"`
	OffsetCuoi   int64     `json:"offsetCuoi"`
	SoBanGhi     int64     `json:"soBanGhi"`
	ThoiDiemDau  time.Time `json:"thoiDiemDau"`
	ThoiDiemCuoi time.Time `json:"thoiDiemCuoi"`
	TepDuLieu    string    `json:"tepDuLieu"`
	KichThuoc    int64     `json:"kichThuoc"`
	Sha256       string    `json:"sha256"`
	// KeKhaiTruoc và KeKhaiTruocSha256 nối chuỗi: băm SHA-256 các byte kê khai trước của cùng phân vùng. Rỗng ở lô
	// đầu tiên của phân vùng.
	KeKhaiTruoc       string    `json:"keKhaiTruoc,omitempty"`
	KeKhaiTruocSha256 string    `json:"keKhaiTruocSha256,omitempty"`
	KhoaKy            string    `json:"khoaKy"`
	TaoLuc            time.Time `json:"taoLuc"`
	GiuDen            time.Time `json:"giuDen"`
}

// Link là mắt xích trước của một phân vùng: khoá và băm byte kê khai.
type Link struct {
	Key    string
	Sha256 string
}

// Batch là một lô đã dựng xong, sẵn sàng ký và tải lên.
type Batch struct {
	Manifest      Manifest
	ManifestKey   string
	ManifestBytes []byte
	DataKey       string
	Data          []byte
}

// SigKey là khoá đối tượng chữ ký của kê khai.
func (b *Batch) SigKey() string { return SigKeyFor(b.ManifestKey) }

// Prefix là tiền tố mọi đối tượng của một topic trong cell.
func Prefix(cell, topic string) string { return cell + "/" + topic + "/" }

// baseKey là phần khoá chung của ba đối tượng một lô.
func baseKey(cell, topic string, partition int32, from int64, first time.Time) string {
	t := first.UTC()
	return fmt.Sprintf("%s%04d/%02d/%02d/%02d/%04d-%020d", Prefix(cell, topic), t.Year(), int(t.Month()), t.Day(), t.Hour(), partition, from)
}

// SigKeyFor đổi khoá kê khai sang khoá chữ ký.
func SigKeyFor(manifestKey string) string { return strings.TrimSuffix(manifestKey, ".json") + ".sig" }

// KeyInfo là thông tin đọc được từ một khoá đối tượng.
type KeyInfo struct {
	Partition int32
	From      int64
	Kind      string // "parquet", "json" hoặc "sig"
}

// ParseKey đọc phân vùng, offset bắt đầu và loại tệp từ khoá; ok=false khi khoá không theo dạng của gói này.
func ParseKey(key string) (KeyInfo, bool) {
	slash := strings.LastIndexByte(key, '/')
	name := key[slash+1:]
	dot := strings.LastIndexByte(name, '.')
	if dot < 0 {
		return KeyInfo{}, false
	}
	kind := name[dot+1:]
	if kind != "parquet" && kind != "json" && kind != "sig" {
		return KeyInfo{}, false
	}
	p, f, ok := strings.Cut(name[:dot], "-")
	if !ok || len(p) != 4 || len(f) != 20 {
		return KeyInfo{}, false
	}
	pn, err1 := strconv.ParseInt(p, 10, 32)
	fn, err2 := strconv.ParseInt(f, 10, 64)
	if err1 != nil || err2 != nil {
		return KeyInfo{}, false
	}
	return KeyInfo{Partition: int32(pn), From: fn, Kind: kind}, true
}

// SHA256Hex là băm SHA-256 dạng hex thường.
func SHA256Hex(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

// EncodeParquet ghi các bản ghi ra Parquet nén zstd. Kết quả xác định: cùng dãy bản ghi luôn cho cùng byte, điều
// kiện để khôi phục dựng lại tệp và so khớp băm trong kê khai đã ký.
func EncodeParquet(rows []Row) ([]byte, error) {
	var buf bytes.Buffer
	w := parquet.NewGenericWriter[Row](&buf,
		parquet.Compression(&zstd.Codec{Level: zstd.DefaultLevel, Concurrency: 1}),
		parquet.CreatedBy("audit-archiver", strconv.Itoa(ManifestVersion), ""),
	)
	if _, err := w.Write(rows); err != nil {
		return nil, fmt.Errorf("archive: ghi Parquet: %w", err)
	}
	if err := w.Close(); err != nil {
		return nil, fmt.Errorf("archive: đóng Parquet: %w", err)
	}
	return buf.Bytes(), nil
}

// DecodeParquet đọc lại toàn bộ bản ghi của một tệp Parquet.
func DecodeParquet(data []byte) ([]Row, error) {
	f, err := parquet.OpenFile(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("archive: mở Parquet: %w", err)
	}
	r := parquet.NewGenericReader[Row](f)
	defer r.Close()
	rows := make([]Row, 0, f.NumRows())
	buf := make([]Row, 1024)
	for {
		n, err := r.Read(buf)
		rows = append(rows, buf[:n]...)
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("archive: đọc Parquet: %w", err)
		}
	}
	for i := range rows {
		rows[i].Timestamp = rows[i].Timestamp.UTC()
	}
	return rows, nil
}

// Params là phần không đổi giữa các lô của một tiến trình.
type Params struct {
	Cell      string
	SignKey   string
	Retention time.Duration
}

// Build dựng lô từ các bản ghi của một phân vùng (đã theo thứ tự offset, cùng topic và phân vùng). from là vị trí đọc
// khi bắt đầu lô, prev là mắt xích trước (rỗng ở lô đầu), now là thời điểm dựng.
func Build(p Params, rows []Row, from int64, prev Link, now time.Time) (*Batch, error) {
	if len(rows) == 0 {
		return nil, errors.New("archive: lô rỗng")
	}
	first, last := rows[0], rows[len(rows)-1]
	for i := range rows {
		if rows[i].Topic != first.Topic || rows[i].Partition != first.Partition {
			return nil, errors.New("archive: lô trộn topic hoặc phân vùng")
		}
		if rows[i].Offset < from || (i > 0 && rows[i].Offset <= rows[i-1].Offset) {
			return nil, fmt.Errorf("archive: offset %d sai thứ tự hoặc trước vị trí bắt đầu %d", rows[i].Offset, from)
		}
	}
	data, err := EncodeParquet(rows)
	if err != nil {
		return nil, err
	}
	base := baseKey(p.Cell, first.Topic, first.Partition, from, first.Timestamp)
	m := Manifest{
		PhienBan:          ManifestVersion,
		Cell:              p.Cell,
		Topic:             first.Topic,
		PhanVung:          first.Partition,
		TuOffset:          from,
		DenOffset:         last.Offset + 1,
		OffsetDau:         first.Offset,
		OffsetCuoi:        last.Offset,
		SoBanGhi:          int64(len(rows)),
		ThoiDiemDau:       first.Timestamp.UTC(),
		ThoiDiemCuoi:      last.Timestamp.UTC(),
		TepDuLieu:         base + ".parquet",
		KichThuoc:         int64(len(data)),
		Sha256:            SHA256Hex(data),
		KeKhaiTruoc:       prev.Key,
		KeKhaiTruocSha256: prev.Sha256,
		KhoaKy:            p.SignKey,
		TaoLuc:            now.UTC().Truncate(time.Millisecond),
		GiuDen:            now.UTC().Add(p.Retention).Truncate(time.Second),
	}
	mb, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return nil, err
	}
	return &Batch{Manifest: m, ManifestKey: base + ".json", ManifestBytes: mb, DataKey: m.TepDuLieu, Data: data}, nil
}

// ParseManifest đọc kê khai và kiểm các trường tối thiểu.
func ParseManifest(b []byte) (Manifest, error) {
	var m Manifest
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&m); err != nil {
		return m, fmt.Errorf("archive: kê khai hỏng: %w", err)
	}
	if m.PhienBan != ManifestVersion || m.TepDuLieu == "" || len(m.Sha256) != 64 || m.DenOffset <= m.TuOffset {
		return m, errors.New("archive: kê khai thiếu trường hoặc sai phiên bản")
	}
	return m, nil
}
