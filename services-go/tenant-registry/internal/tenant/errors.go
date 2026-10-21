package tenant

import (
	"errors"
	"fmt"
)

// Lỗi nghiệp vụ; tầng API ánh xạ sang Problem Details có type trỏ BR hoặc MSG và mã <MODULE>-<HTTP>-<TÊN>.
var (
	// ErrSiloRequired: BR-01-01.
	ErrSiloRequired = errors.New("tenant: BR-01-01 bắt buộc mô hình silo")
	// ErrSiloKek: SCR-0102 trường 9, silo bắt buộc khoá riêng.
	ErrSiloKek = errors.New("tenant: silo bắt buộc khoá mã hoá riêng")
	// ErrNotFound: không có tenant.
	ErrNotFound = errors.New("tenant: không tìm thấy")
	// ErrForbidden: người gọi không có quyền với tenant hoặc với trường đang sửa.
	ErrForbidden = errors.New("tenant: không có quyền")
)

// CapacityError: BR-01-02, cell vượt 85% sau khi thêm (MSG-01-03 khi không còn cell nào phù hợp).
type CapacityError struct {
	Cell      string // rỗng: không còn cell phù hợp (MSG-01-03)
	Model     string
	LoadAfter float64
}

func (e *CapacityError) Error() string {
	if e.Cell == "" {
		return fmt.Sprintf("tenant: không còn cell %s nào dưới ngưỡng", e.Model)
	}
	return fmt.Sprintf("tenant: cell %s sẽ ở mức %.1f%%", e.Cell, e.LoadAfter)
}

// DuplicateError: trùng mã, tên, mã hồ sơ hoặc tên miền (MSG-01-04 khi là tên miền).
type DuplicateError struct {
	Field string // code, name, onboardingCode, domain
	Value string
}

func (e *DuplicateError) Error() string { return "tenant: trùng " + e.Field + " " + e.Value }

// StateError: thao tác không hợp với trạng thái hiện tại.
type StateError struct{ Status string }

func (e *StateError) Error() string {
	return "tenant: trạng thái " + e.Status + " không cho thao tác này"
}

// VersionError: If-Match lệch row_version.
type VersionError struct{ Sent, Current int }

func (e *VersionError) Error() string {
	return fmt.Sprintf("tenant: lệch phiên bản %d/%d", e.Sent, e.Current)
}

// FieldError là lỗi một trường đầu vào.
type FieldError struct{ Field, Message, Rule string }

// ValidationError gom lỗi theo trường.
type ValidationError struct{ Fields []FieldError }

func (e *ValidationError) Error() string {
	return fmt.Sprintf("tenant: %d trường không hợp lệ", len(e.Fields))
}

// CellUnavailableError: cell đích không ở trạng thái HOAT_DONG.
type CellUnavailableError struct{ Cell string }

func (e *CellUnavailableError) Error() string {
	return "tenant: cell " + e.Cell + " không hoạt động"
}

// PackMissingError: BR-01-04, tenant thuộc cả hai luồng phải gắn đủ một gói cho mỗi luồng.
type PackMissingError struct{ Flow string }

func (e *PackMissingError) Error() string { return "tenant: thiếu Policy Pack cho luồng " + e.Flow }

type validator struct{ errs []FieldError }

func (v *validator) add(field, msg string) {
	v.errs = append(v.errs, FieldError{Field: field, Message: msg})
}

func (v *validator) err() error {
	if len(v.errs) == 0 {
		return nil
	}
	return &ValidationError{Fields: v.errs}
}
