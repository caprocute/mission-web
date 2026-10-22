// Package render dựng tiêu đề và nội dung thông báo từ notify.template (TL-03 "Schema notify").
// Biến trong mẫu viết {{tenBien}}; mẫu đánh dấu biến C2 bằng {{tenBien|C2}} bị từ chối, vì nội dung
// thông báo không được chứa dữ liệu C2 (TL-03: chỉ nói "Có hồ sơ cần thẩm định" kèm đường dẫn).
// Tên biến thuộc danh sách C2 đã biết cũng bị từ chối dù không gắn nhãn.
package render

import (
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
)

// ErrC2Param được trả khi mẫu hoặc tham số chứa dữ liệu C2.
var ErrC2Param = errors.New("render: mẫu thông báo không được chứa tham số C2")

// ErrInvalidTemplate được trả khi mẫu sai cú pháp.
var ErrInvalidTemplate = errors.New("render: mẫu thông báo sai cú pháp")

var placeholder = regexp.MustCompile(`\{\{\s*([^{}]*?)\s*\}\}`)
var varName = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9_]{0,47}$`)

// c2Keys là tên tham số mang dữ liệu C2 theo phân loại của TL-03 (định danh cá nhân, liên lạc, kết quả
// đánh giá, nội dung nhận xét). So khớp không phân biệt hoa thường.
var c2Keys = map[string]bool{
	"cccd": true, "socccd": true, "sodinhdanh": true, "identitynumber": true,
	"sodienthoai": true, "phone": true, "phonenumber": true, "email": true, "diachi": true, "address": true,
	"ngaysinh": true, "dateofbirth": true,
	"diem": true, "score": true, "totalscore": true, "xeploai": true, "rating": true, "grade": true,
	"nhanxet": true, "comment": true, "noidungdanhgia": true, "reviewcontent": true,
}

// Template là một dòng notify.template đã chọn.
type Template struct {
	Code    string
	Channel string
	Locale  string
	Subject string
	Body    string
}

// Variables trả tên biến trong chuỗi mẫu, kiểm cú pháp và nhãn C2.
func Variables(text string) ([]string, error) {
	if strings.Count(text, "{{") != strings.Count(text, "}}") {
		return nil, fmt.Errorf("%w: dấu ngoặc biến không cân", ErrInvalidTemplate)
	}
	seen := map[string]bool{}
	var out []string
	for _, m := range placeholder.FindAllStringSubmatch(text, -1) {
		raw := m[1]
		name, label, hasLabel := strings.Cut(raw, "|")
		name = strings.TrimSpace(name)
		if hasLabel {
			switch strings.ToUpper(strings.TrimSpace(label)) {
			case "C2", "C3":
				return nil, fmt.Errorf("%w: biến %q", ErrC2Param, name)
			default:
				return nil, fmt.Errorf("%w: nhãn %q không hợp lệ", ErrInvalidTemplate, label)
			}
		}
		if !varName.MatchString(name) {
			return nil, fmt.Errorf("%w: tên biến %q", ErrInvalidTemplate, name)
		}
		if IsC2Key(name) {
			return nil, fmt.Errorf("%w: biến %q", ErrC2Param, name)
		}
		if !seen[name] {
			seen[name] = true
			out = append(out, name)
		}
	}
	return out, nil
}

// IsC2Key cho biết tên tham số thuộc danh sách C2 đã biết.
func IsC2Key(name string) bool {
	return c2Keys[strings.ToLower(strings.ReplaceAll(name, "_", ""))]
}

// Validate kiểm tiêu đề và nội dung của mẫu.
func (t Template) Validate() error {
	if strings.TrimSpace(t.Subject) == "" {
		return fmt.Errorf("%w: tiêu đề rỗng", ErrInvalidTemplate)
	}
	if _, err := Variables(t.Subject); err != nil {
		return err
	}
	_, err := Variables(t.Body)
	return err
}

// CheckParams từ chối tham số có tên C2.
func CheckParams(params map[string]any) error {
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if IsC2Key(k) {
			return fmt.Errorf("%w: tham số %q", ErrC2Param, k)
		}
	}
	return nil
}

// Render thay biến bằng giá trị trong params; biến thiếu được để trống.
func (t Template) Render(params map[string]any) (subject, body string, err error) {
	if err := t.Validate(); err != nil {
		return "", "", err
	}
	if err := CheckParams(params); err != nil {
		return "", "", err
	}
	return fill(t.Subject, params), fill(t.Body, params), nil
}

func fill(text string, params map[string]any) string {
	return placeholder.ReplaceAllStringFunc(text, func(m string) string {
		name := strings.TrimSpace(placeholder.FindStringSubmatch(m)[1])
		v, ok := params[name]
		if !ok || v == nil {
			return ""
		}
		return fmt.Sprint(v)
	})
}
