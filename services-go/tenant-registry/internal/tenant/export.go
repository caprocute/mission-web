package tenant

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/store"
)

// Tác vụ xuất danh sách tenant (SCR-0101 "Xuất", exportConfigTenants → 202 + /api/v1/jobs/{id}).
// Tệp kết quả lưu tạm trong registry.job.result_data; đưa lên kho tệp SVC-08 và điền resultLocation là điểm
// chờ nền móng.

// JobTypeExport là loại tác vụ (common.yaml Job.type: EXPORT).
const JobTypeExport = "EXPORT"

// ExportRequest là ConfigTenantExportRequest.
type ExportRequest struct {
	Cell   string `json:"cell,omitempty"`
	Model  string `json:"model,omitempty"`
	Status string `json:"status,omitempty"`
	Q      string `json:"q,omitempty"`
	Format string `json:"format,omitempty"`
}

// Check kiểm yêu cầu xuất; mặc định XLSX.
func (r *ExportRequest) Check() error {
	if r.Format == "" {
		r.Format = "XLSX"
	}
	if r.Format != "XLSX" && r.Format != "CSV" {
		return &ValidationError{Fields: []FieldError{{Field: "format", Message: "Định dạng chỉ nhận XLSX hoặc CSV"}}}
	}
	return CheckListInput(r.list())
}

func (r ExportRequest) list() ListInput {
	return ListInput{Cell: r.Cell, Model: r.Model, Status: r.Status, Q: r.Q}
}

// JobView là Job của common.yaml.
type JobView struct {
	ID             string          `json:"id"`
	Type           string          `json:"type"`
	Status         string          `json:"status"`
	Progress       int             `json:"progress"`
	Message        string          `json:"message,omitempty"`
	ResultLocation string          `json:"resultLocation,omitempty"`
	Problem        json.RawMessage `json:"problem,omitempty"`
	UpdatedAt      time.Time       `json:"updatedAt"`
}

// JobViewOf dựng JobView.
func JobViewOf(j *store.Job) JobView {
	v := JobView{ID: j.ID.String(), Type: j.Type, Status: j.Status, Progress: j.Progress, UpdatedAt: j.UpdatedAt}
	if j.Message != nil {
		v.Message = *j.Message
	}
	if j.ResultLocation != nil {
		v.ResultLocation = *j.ResultLocation
	}
	if len(j.Problem) > 0 {
		v.Problem = j.Problem
	}
	return v
}

// NewExportJob ghi tác vụ CHO trong tx.
func NewExportJob(ctx context.Context, q store.Querier, a Actor, r ExportRequest) (*store.Job, error) {
	params, _ := json.Marshal(r)
	j := &store.Job{Type: JobTypeExport, Params: params, CreatedBy: a.ID}
	if err := store.InsertJob(ctx, q, j); err != nil {
		return nil, err
	}
	return j, nil
}

// RunExport chạy tác vụ xuất (gọi sau khi giao dịch tạo tác vụ đã commit).
func (s *Service) RunExport(ctx context.Context, pool *pgxpool.Pool, jobID uuid.UUID) error {
	j, err := store.GetJob(ctx, pool, jobID)
	if err != nil {
		return err
	}
	var r ExportRequest
	if err := json.Unmarshal(j.Params, &r); err != nil {
		return s.failJob(ctx, pool, j, "Tham số tác vụ không hợp lệ")
	}
	msg := "Đang lập danh sách tenant"
	j.Status, j.Progress, j.Message = "DANG_CHAY", 10, &msg
	if err := store.UpdateJob(ctx, pool, j); err != nil {
		return err
	}
	rows, err := s.ExportRows(ctx, pool, r.list())
	if err != nil {
		return s.failJob(ctx, pool, j, "Không lập được danh sách tenant")
	}
	var data []byte
	name := "danh-sach-tenant-" + s.Now().Format("20060102-1504")
	ctype := ""
	if r.Format == "CSV" {
		data, name, ctype = ExportCSV(rows), name+".csv", "text/csv; charset=utf-8"
	} else {
		if data, err = ExportXLSX(rows); err != nil {
			return s.failJob(ctx, pool, j, "Không tạo được tệp xuất")
		}
		name, ctype = name+".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
	}
	done := fmt.Sprintf("Đã xuất %d tenant", len(rows))
	j.Status, j.Progress, j.Message, j.ResultName, j.ResultType, j.ResultData = "HOAN_TAT", 100, &done, &name, &ctype, data
	return store.UpdateJob(ctx, pool, j)
}

func (s *Service) failJob(ctx context.Context, pool *pgxpool.Pool, j *store.Job, msg string) error {
	prob, _ := json.Marshal(map[string]any{"type": "https://okrkpi.vn/problems/internal", "title": msg, "status": 500, "code": "COMMON-500-INTERNAL"})
	j.Status, j.Message, j.Problem = "THAT_BAI", &msg, prob
	return store.UpdateJob(ctx, pool, j)
}

// StatusLabel là nhãn nghiệp vụ của trạng thái tenant (không in mã thô ra tệp cho người dùng).
func StatusLabel(s string) string {
	switch s {
	case KhoiTao:
		return "Khởi tạo"
	case DangCapPhat:
		return "Đang cấp phát"
	case DangCauHinh:
		return "Đang cấu hình"
	case SanSang:
		return "Sẵn sàng"
	case DangVanHanh:
		return "Đang vận hành"
	case TamNgung:
		return "Tạm ngừng"
	case DangChuyenCell:
		return "Đang chuyển cell"
	case DaChamDut:
		return "Đã chấm dứt"
	}
	return s
}

var exportHeader = []string{"Mã tenant", "Tên tenant", "Tên miền", "Cell", "Mô hình thuê bao", "Số tài khoản", "Trạng thái"}

func exportRecord(r TenantRow) []string {
	return []string{r.Code, r.Name, r.Domain, r.CellCode, r.TenancyModel, strconv.Itoa(r.AccountCount), StatusLabel(r.Status)}
}

// ExportCSV ghi CSV UTF-8 có BOM (mở đúng dấu tiếng Việt trong Excel). Ô bắt đầu bằng = + - @ được thêm
// dấu nháy để chặn chèn công thức.
func ExportCSV(rows []TenantRow) []byte {
	var b bytes.Buffer
	b.WriteString("\ufeff")
	w := csv.NewWriter(&b)
	_ = w.Write(exportHeader)
	for _, r := range rows {
		rec := exportRecord(r)
		for i, c := range rec {
			if c != "" && strings.ContainsRune("=+-@\t\r", rune(c[0])) {
				rec[i] = "'" + c
			}
		}
		_ = w.Write(rec)
	}
	w.Flush()
	return b.Bytes()
}

func xmlEscape(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch r {
		case '&':
			b.WriteString("&amp;")
		case '<':
			b.WriteString("&lt;")
		case '>':
			b.WriteString("&gt;")
		case '"':
			b.WriteString("&quot;")
		default:
			if r < 0x20 && r != '\t' && r != '\n' && r != '\r' {
				continue
			}
			b.WriteRune(r)
		}
	}
	return b.String()
}

// ExportXLSX ghi tệp XLSX tối giản một trang tính (chuỗi nội tuyến, không công thức) bằng thư viện chuẩn.
func ExportXLSX(rows []TenantRow) ([]byte, error) {
	var sheet strings.Builder
	sheet.WriteString(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>`)
	writeRow := func(n int, cells []string, numeric map[int]bool) {
		fmt.Fprintf(&sheet, `<row r="%d">`, n)
		for i, c := range cells {
			ref := fmt.Sprintf("%c%d", 'A'+i, n)
			if numeric[i] {
				fmt.Fprintf(&sheet, `<c r="%s"><v>%s</v></c>`, ref, xmlEscape(c))
				continue
			}
			fmt.Fprintf(&sheet, `<c r="%s" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>`, ref, xmlEscape(c))
		}
		sheet.WriteString(`</row>`)
	}
	writeRow(1, exportHeader, nil)
	for i, r := range rows {
		writeRow(i+2, exportRecord(r), map[int]bool{5: true})
	}
	sheet.WriteString(`</sheetData></worksheet>`)
	files := []struct{ name, body string }{
		{"[Content_Types].xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>`},
		{"_rels/.rels", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`},
		{"xl/workbook.xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Tenant" sheetId="1" r:id="rId1"/></sheets></workbook>`},
		{"xl/_rels/workbook.xml.rels", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>`},
		{"xl/worksheets/sheet1.xml", sheet.String()},
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, f := range files {
		w, err := zw.Create(f.name)
		if err != nil {
			return nil, err
		}
		if _, err := w.Write([]byte(f.body)); err != nil {
			return nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
