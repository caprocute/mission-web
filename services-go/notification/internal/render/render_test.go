package render

import (
	"errors"
	"testing"
)

func TestRender(t *testing.T) {
	tpl := Template{Code: "NV_DUOC_GIAO", Subject: "Đồng chí được giao nhiệm vụ mới", Body: `Nhiệm vụ "{{ title }}", hạn {{dueDate}}.`}
	s, b, err := tpl.Render(map[string]any{"title": "Dự thảo", "dueDate": "30/10/2026"})
	if err != nil {
		t.Fatal(err)
	}
	if s != "Đồng chí được giao nhiệm vụ mới" || b != `Nhiệm vụ "Dự thảo", hạn 30/10/2026.` {
		t.Fatalf("kết quả sai: %q / %q", s, b)
	}
	_, b, _ = tpl.Render(map[string]any{})
	if b != `Nhiệm vụ "", hạn .` {
		t.Fatalf("biến thiếu phải để trống: %q", b)
	}
}

func TestC2Blocked(t *testing.T) {
	cases := []Template{
		{Subject: "Kết quả", Body: "Điểm của đồng chí: {{diem|C2}}"},
		{Subject: "Kết quả", Body: "Số điện thoại {{soDienThoai}}"},
		{Subject: "Xếp loại {{xepLoai}}", Body: ""},
		{Subject: "C3 {{noiDung|c3}}", Body: ""},
	}
	for _, c := range cases {
		if err := c.Validate(); !errors.Is(err, ErrC2Param) {
			t.Fatalf("mẫu %q phải bị chặn vì C2, nhận %v", c.Body+c.Subject, err)
		}
	}
	if err := CheckParams(map[string]any{"title": "x", "so_dien_thoai": "0900"}); !errors.Is(err, ErrC2Param) {
		t.Fatalf("tham số C2 phải bị chặn, nhận %v", err)
	}
}

func TestSyntax(t *testing.T) {
	for _, s := range []string{"{{", "{{a|C1}}", "{{1abc}}", "{{ }}"} {
		if err := (Template{Subject: "x", Body: s}).Validate(); !errors.Is(err, ErrInvalidTemplate) {
			t.Fatalf("mẫu %q phải sai cú pháp, nhận %v", s, err)
		}
	}
	if err := (Template{Subject: " ", Body: ""}).Validate(); !errors.Is(err, ErrInvalidTemplate) {
		t.Fatal("tiêu đề rỗng phải bị từ chối")
	}
}
