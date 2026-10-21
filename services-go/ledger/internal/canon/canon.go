// Package canon hiện thực bộ chuẩn hoá JSON theo RFC 8785 (JCS) kết hợp chuẩn hoá
// chuỗi Unicode NFC, định dạng dấu thời gian UTC micro-giây, chuỗi số thập phân,
// hàm chọn phân vùng chuỗi băm 0..63 và các hàm tính payload_hash, metadata_hash, entry_hash
// cho SVC-09 Ledger Service (TL-01 §5.6, TL-03 §8, ADR-09, ADR-26, DV-09-02).
package canon

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/google/uuid"
	"golang.org/x/text/unicode/norm"
)

const (
	// ChainPartitionCount là tổng số chuỗi băm độc lập trên mỗi tenant (0..63).
	ChainPartitionCount = 64

	// TimestampLayout là định dạng dấu thời gian chuẩn hoá UTC độ chính xác micro-giây.
	TimestampLayout = "2006-01-02T15:04:05.000000Z"
)

// ZeroHash là 32 byte 0 dùng làm prev_hash cho bản ghi đầu tiên (seq_no = 1) của chuỗi mới.
var ZeroHash = [32]byte{}

var (
	// ErrInvalidJSON báo dữ liệu đầu vào không phải JSON hợp lệ theo RFC 8785.
	ErrInvalidJSON = errors.New("canon: dữ liệu JSON không hợp lệ")
	// ErrDuplicateNFCKey báo hai khoá trong cùng đối tượng JSON trùng nhau sau khi chuẩn hoá NFC.
	ErrDuplicateNFCKey = errors.New("canon: trùng khoá đối tượng sau khi chuẩn hoá Unicode NFC")
	// ErrNonFiniteNumber báo số JSON là NaN hoặc Infinity (bị cấm bởi RFC 8785).
	ErrNonFiniteNumber = errors.New("canon: số không hữu hạn (NaN/Inf) không hợp lệ trong RFC 8785")
	// ErrInvalidDecimalString báo chuỗi tiền/điểm không đúng định dạng thập phân chuẩn.
	ErrInvalidDecimalString = errors.New("canon: chuỗi thập phân không hợp lệ")
)

var decimalRegex = regexp.MustCompile(`^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$`)

// MetadataHeader chứa các trường siêu dữ liệu bất biến được băm thành metadata_hash
// theo TL-03 ("Cấu trúc chuỗi băm") và DV-09-02 mục 3.
type MetadataHeader struct {
	TenantID       string  `json:"tenant_id"`
	ChainPartition int16   `json:"chain_partition"`
	SeqNo          int64   `json:"seq_no"`
	EventType      string  `json:"event_type"`
	AggregateType  string  `json:"aggregate_type"`
	AggregateID    string  `json:"aggregate_id"`
	ActorID        *string `json:"actor_id"`
	ActorKind      string  `json:"actor_kind"`
	OccurredAt     string  `json:"occurred_at"`
	RecordedAt     string  `json:"recorded_at"`
}

// NormalizeNFC chuẩn hoá chuỗi sang dạng dựng sẵn Unicode NFC.
func NormalizeNFC(s string) string {
	return norm.NFC.String(s)
}

// FormatTimestamp chuẩn hoá thời điểm về UTC theo định dạng YYYY-MM-DDThh:mm:ss.ffffffZ.
func FormatTimestamp(t time.Time) string {
	return t.UTC().Truncate(time.Microsecond).Format(TimestampLayout)
}

// ParseAndFormatTimestamp phân tích chuỗi thời gian RFC3339/ISO8601 và trả về dạng chuẩn YYYY-MM-DDThh:mm:ss.ffffffZ.
func ParseAndFormatTimestamp(raw string) (time.Time, string, error) {
	t, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(raw))
	if err != nil {
		return time.Time{}, "", fmt.Errorf("canon: dấu thời gian %q không hợp lệ: %w", raw, err)
	}
	u := t.UTC().Truncate(time.Microsecond)
	return u, u.Format(TimestampLayout), nil
}

// ValidateDecimalString kiểm tra chuỗi số tiền/điểm theo dạng chuỗi thập phân chính xác (không dùng dấu phẩy động).
func ValidateDecimalString(s string) (string, error) {
	trimmed := strings.TrimSpace(s)
	if !decimalRegex.MatchString(trimmed) {
		return "", fmt.Errorf("%w: %q", ErrInvalidDecimalString, s)
	}
	return trimmed, nil
}

// ChainPartition chọn phân vùng chuỗi băm 0..63 từ aggregate_id bằng cách lấy 64 bit thấp
// (8 byte cuối theo thứ tự big-endian) của SHA-256(lowercase(aggregate_id)) mod 64 (TL-03, DV-09-02).
func ChainPartition(aggregateID uuid.UUID) int16 {
	canonicalID := strings.ToLower(aggregateID.String())
	sum := sha256.Sum256([]byte(canonicalID))
	low64 := binary.BigEndian.Uint64(sum[24:32])
	return int16(low64 % ChainPartitionCount)
}

// ChainPartitionFromString phân tích UUID từ chuỗi và tính phân vùng chuỗi 0..63.
func ChainPartitionFromString(aggregateID string) (int16, error) {
	parsed, err := uuid.Parse(strings.TrimSpace(aggregateID))
	if err != nil {
		return 0, fmt.Errorf("canon: aggregate_id %q không phải UUID hợp lệ: %w", aggregateID, err)
	}
	return ChainPartition(parsed), nil
}

// CanonicalizeJSON chuẩn hoá một tài liệu JSON theo RFC 8785 (JCS) kết hợp chuẩn hoá Unicode NFC
// trên toàn bộ khoá và giá trị chuỗi trước khi sắp xếp và băm.
func CanonicalizeJSON(raw []byte) ([]byte, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()

	val, err := parseValue(dec)
	if err != nil {
		return nil, err
	}
	// Đảm bảo không còn token thừa phía sau tài liệu JSON.
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("%w: còn dữ liệu thừa sau giá trị JSON gốc", ErrInvalidJSON)
	}

	var buf bytes.Buffer
	if err := writeCanonical(&buf, val); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// CanonicalizeValue tuần tự hoá một giá trị Go bất kỳ sang JSON rồi chuẩn hoá theo RFC 8785 + NFC.
func CanonicalizeValue(v any) ([]byte, error) {
	raw, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("canon: marshal giá trị thất bại: %w", err)
	}
	return CanonicalizeJSON(raw)
}

// PayloadHash chuẩn hoá JSON tải trọng sự kiện theo RFC 8785 + NFC và trả về SHA-256 32 byte kèm bản chuẩn hoá.
func PayloadHash(rawJSON []byte) ([32]byte, []byte, error) {
	canonBytes, err := CanonicalizeJSON(rawJSON)
	if err != nil {
		return [32]byte{}, nil, err
	}
	return sha256.Sum256(canonBytes), canonBytes, nil
}

// MetadataHash chuẩn hoá MetadataHeader theo RFC 8785 + NFC và trả về SHA-256 32 byte kèm chuỗi JSON chuẩn hoá.
func MetadataHash(hdr MetadataHeader) ([32]byte, []byte, error) {
	if hdr.ChainPartition < 0 || hdr.ChainPartition >= ChainPartitionCount {
		return [32]byte{}, nil, fmt.Errorf("canon: chain_partition %d ngoài khoảng 0..63", hdr.ChainPartition)
	}
	if hdr.SeqNo < 1 {
		return [32]byte{}, nil, fmt.Errorf("canon: seq_no %d phải >= 1", hdr.SeqNo)
	}
	tid, err := uuid.Parse(strings.TrimSpace(hdr.TenantID))
	if err != nil {
		return [32]byte{}, nil, fmt.Errorf("canon: tenant_id không hợp lệ: %w", err)
	}
	aid, err := uuid.Parse(strings.TrimSpace(hdr.AggregateID))
	if err != nil {
		return [32]byte{}, nil, fmt.Errorf("canon: aggregate_id không hợp lệ: %w", err)
	}
	_, occStr, err := ParseAndFormatTimestamp(hdr.OccurredAt)
	if err != nil {
		return [32]byte{}, nil, fmt.Errorf("canon: occurred_at không hợp lệ: %w", err)
	}
	_, recStr, err := ParseAndFormatTimestamp(hdr.RecordedAt)
	if err != nil {
		return [32]byte{}, nil, fmt.Errorf("canon: recorded_at không hợp lệ: %w", err)
	}

	var normActorID *string
	if hdr.ActorID != nil && strings.TrimSpace(*hdr.ActorID) != "" {
		parsedActor, err := uuid.Parse(strings.TrimSpace(*hdr.ActorID))
		if err != nil {
			return [32]byte{}, nil, fmt.Errorf("canon: actor_id không hợp lệ: %w", err)
		}
		s := strings.ToLower(parsedActor.String())
		normActorID = &s
	}

	normalized := MetadataHeader{
		TenantID:       strings.ToLower(tid.String()),
		ChainPartition: hdr.ChainPartition,
		SeqNo:          hdr.SeqNo,
		EventType:      NormalizeNFC(strings.TrimSpace(hdr.EventType)),
		AggregateType:  NormalizeNFC(strings.TrimSpace(hdr.AggregateType)),
		AggregateID:    strings.ToLower(aid.String()),
		ActorID:        normActorID,
		ActorKind:      NormalizeNFC(strings.TrimSpace(hdr.ActorKind)),
		OccurredAt:     occStr,
		RecordedAt:     recStr,
	}

	canonBytes, err := CanonicalizeValue(normalized)
	if err != nil {
		return [32]byte{}, nil, err
	}
	return sha256.Sum256(canonBytes), canonBytes, nil
}

// EntryHash tính SHA-256(prev_hash || payload_hash || metadata_hash) theo đúng công thức TL-03 L4100.
func EntryHash(prevHash, payloadHash, metadataHash [32]byte) [32]byte {
	var buf [96]byte
	copy(buf[0:32], prevHash[:])
	copy(buf[32:64], payloadHash[:])
	copy(buf[64:96], metadataHash[:])
	return sha256.Sum256(buf[:])
}

// Hex32 mã hoá mảng băm 32 byte sang chuỗi hex chữ thường.
func Hex32(h [32]byte) string {
	return hex.EncodeToString(h[:])
}

// ParseHex32 giải mã chuỗi hex 64 ký tự thành mảng 32 byte.
func ParseHex32(s string) ([32]byte, error) {
	var out [32]byte
	b, err := hex.DecodeString(strings.TrimSpace(s))
	if err != nil {
		return out, err
	}
	if len(b) != 32 {
		return out, fmt.Errorf("canon: độ dài băm phải đúng 32 byte, nhận %d byte", len(b))
	}
	copy(out[:], b)
	return out, nil
}

type jcsObjectEntry struct {
	key   string
	utf16 []uint16
	val   any
}

func parseValue(dec *json.Decoder) (any, error) {
	tok, err := dec.Token()
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidJSON, err)
	}
	return parseToken(dec, tok)
}

func parseToken(dec *json.Decoder, tok json.Token) (any, error) {
	switch v := tok.(type) {
	case json.Delim:
		switch v {
		case '{':
			var entries []jcsObjectEntry
			seenNFC := make(map[string]struct{})
			for dec.More() {
				keyTok, err := dec.Token()
				if err != nil {
					return nil, fmt.Errorf("%w: đọc khoá đối tượng lỗi: %v", ErrInvalidJSON, err)
				}
				rawKey, ok := keyTok.(string)
				if !ok || !utf8.ValidString(rawKey) {
					return nil, fmt.Errorf("%w: khoá đối tượng không phải chuỗi UTF-8 hợp lệ", ErrInvalidJSON)
				}
				nfcKey := NormalizeNFC(rawKey)
				if _, exists := seenNFC[nfcKey]; exists {
					return nil, fmt.Errorf("%w: %q", ErrDuplicateNFCKey, nfcKey)
				}
				seenNFC[nfcKey] = struct{}{}

				childVal, err := parseValue(dec)
				if err != nil {
					return nil, err
				}
				entries = append(entries, jcsObjectEntry{
					key:   nfcKey,
					utf16: utf16.Encode([]rune(nfcKey)),
					val:   childVal,
				})
			}
			endTok, err := dec.Token()
			if err != nil || endTok != json.Delim('}') {
				return nil, fmt.Errorf("%w: thiếu dấu đóng đối tượng '}'", ErrInvalidJSON)
			}
			// Sắp xếp khoá theo thứ tự từ điển của đơn vị mã UTF-16 (RFC 8785 §3.2.3).
			sort.Slice(entries, func(i, j int) bool {
				return compareUTF16(entries[i].utf16, entries[j].utf16) < 0
			})
			return entries, nil

		case '[':
			var arr []any
			for dec.More() {
				elem, err := parseValue(dec)
				if err != nil {
					return nil, err
				}
				arr = append(arr, elem)
			}
			endTok, err := dec.Token()
			if err != nil || endTok != json.Delim(']') {
				return nil, fmt.Errorf("%w: thiếu dấu đóng mảng ']'", ErrInvalidJSON)
			}
			return arr, nil

		default:
			return nil, fmt.Errorf("%w: dấu phân cách không hợp lệ %v", ErrInvalidJSON, v)
		}

	case string:
		if !utf8.ValidString(v) {
			return nil, fmt.Errorf("%w: chuỗi không phải UTF-8 hợp lệ", ErrInvalidJSON)
		}
		return NormalizeNFC(v), nil

	case bool:
		return v, nil

	case nil:
		return nil, nil

	case json.Number:
		return v, nil

	case float64:
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return nil, ErrNonFiniteNumber
		}
		return json.Number(strconv.FormatFloat(v, 'g', -1, 64)), nil

	default:
		return nil, fmt.Errorf("%w: kiểu token không hỗ trợ %T", ErrInvalidJSON, tok)
	}
}

func compareUTF16(a, b []uint16) int {
	minLen := len(a)
	if len(b) < minLen {
		minLen = len(b)
	}
	for i := 0; i < minLen; i++ {
		if a[i] != b[i] {
			if a[i] < b[i] {
				return -1
			}
			return 1
		}
	}
	switch {
	case len(a) < len(b):
		return -1
	case len(a) > len(b):
		return 1
	default:
		return 0
	}
}

func writeCanonical(buf *bytes.Buffer, v any) error {
	switch val := v.(type) {
	case nil:
		buf.WriteString("null")
		return nil

	case bool:
		if val {
			buf.WriteString("true")
		} else {
			buf.WriteString("false")
		}
		return nil

	case string:
		writeJCSString(buf, val)
		return nil

	case json.Number:
		formatted, err := formatJCSNumber(val.String())
		if err != nil {
			return err
		}
		buf.WriteString(formatted)
		return nil

	case []any:
		buf.WriteByte('[')
		for i, elem := range val {
			if i > 0 {
				buf.WriteByte(',')
			}
			if err := writeCanonical(buf, elem); err != nil {
				return err
			}
		}
		buf.WriteByte(']')
		return nil

	case []jcsObjectEntry:
		buf.WriteByte('{')
		for i, entry := range val {
			if i > 0 {
				buf.WriteByte(',')
			}
			writeJCSString(buf, entry.key)
			buf.WriteByte(':')
			if err := writeCanonical(buf, entry.val); err != nil {
				return err
			}
		}
		buf.WriteByte('}')
		return nil

	default:
		return fmt.Errorf("%w: kiểu nội bộ không hỗ trợ %T", ErrInvalidJSON, v)
	}
}

// writeJCSString ghi chuỗi JSON theo RFC 8785 §3.2.2.2 (chỉ thoát các ký tự điều khiển 0x00..0x1F, '"' và '\\').
func writeJCSString(buf *bytes.Buffer, s string) {
	buf.WriteByte('"')
	for _, r := range s {
		switch r {
		case '\b':
			buf.WriteString(`\b`)
		case '\t':
			buf.WriteString(`\t`)
		case '\n':
			buf.WriteString(`\n`)
		case '\f':
			buf.WriteString(`\f`)
		case '\r':
			buf.WriteString(`\r`)
		case '"':
			buf.WriteString(`\"`)
		case '\\':
			buf.WriteString(`\\`)
		default:
			if r >= 0 && r <= 0x1F {
				buf.WriteString(`\u00`)
				const hexDigits = "0123456789abcdef"
				buf.WriteByte(hexDigits[(r>>4)&0x0F])
				buf.WriteByte(hexDigits[r&0x0F])
			} else {
				buf.WriteRune(r)
			}
		}
	}
	buf.WriteByte('"')
}

// formatJCSNumber định dạng số theo chuẩn ECMAScript Number.prototype.toString() (RFC 8785 §3.2.2.3).
func formatJCSNumber(raw string) (string, error) {
	// Nếu là số nguyên 64-bit chính xác trong vùng an toàn của IEEE-754 (|n| <= 2^53 - 1),
	// có thể in trực tiếp dạng nguyên (trừ -0 -> 0).
	if i64, err := strconv.ParseInt(raw, 10, 64); err == nil {
		if i64 == 0 {
			return "0", nil
		}
		const maxSafeInteger = (1 << 53) - 1
		if i64 >= -maxSafeInteger && i64 <= maxSafeInteger {
			return strconv.FormatInt(i64, 10), nil
		}
	}

	f, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return "", fmt.Errorf("%w: số %q không hợp lệ: %v", ErrInvalidJSON, raw, err)
	}
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return "", ErrNonFiniteNumber
	}
	if f == 0 {
		// Bao gồm cả -0.0 -> "0"
		return "0", nil
	}

	absF := math.Abs(f)
	if absF >= 1e-6 && absF < 1e21 {
		s := strconv.FormatFloat(f, 'f', -1, 64)
		return s, nil
	}

	// Ngoài dải [1e-6, 1e21), ECMAScript dùng ký hiệu khoa học với chữ 'e' thường và không có số 0 đứng đầu số mũ.
	s := strconv.FormatFloat(f, 'e', -1, 64)
	if idx := strings.IndexByte(s, 'e'); idx != -1 && len(s) >= idx+4 {
		sign := s[idx+1] // '+' hoặc '-'
		expPart := strings.TrimLeft(s[idx+2:], "0")
		if expPart == "" {
			expPart = "0"
		}
		s = s[:idx+1] + string(sign) + expPart
	}
	return s, nil
}
