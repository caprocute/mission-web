// Package idem hiện thực khoá chống ghi trùng theo TL-03 "Bảng idempotency_key": khoá được chèn trong cùng giao
// dịch với thao tác nghiệp vụ; cùng khoá cùng băm yêu cầu trong 48 giờ trả lại phản hồi đã lưu; cùng khoá khác
// băm trả 422 COMMON-422-IDEMPOTENCY-MISMATCH. Phạm vi khoá là (tenant, người gọi).
// Hai yêu cầu đồng thời cùng khoá: INSERT ... ON CONFLICT chờ giao dịch kia kết thúc, rồi bên sau đọc lại và
// trả phản hồi đã lưu (hoặc tự chạy nếu bên trước rollback).
package idem

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Retention là thời gian giữ khoá (BACKEND.md 3.1, HD-02).
const Retention = 48 * time.Hour

const retentionSQL = "48 hours"

// Scope là phạm vi khoá.
type Scope struct {
	TenantID uuid.UUID
	ActorID  uuid.UUID
	Key      string
}

// Stored là phản hồi đã lưu.
type Stored struct {
	Code        int
	Body        json.RawMessage
	ResourceRef string
}

// ErrMismatch: cùng khoá, khác nội dung.
var ErrMismatch = errors.New("idem: khoá đã dùng cho nội dung khác")

// Hash băm phương thức, đường dẫn và thân yêu cầu đã chuẩn hoá (JSON giải mã rồi mã hoá lại, khoá sắp xếp).
func Hash(method, path string, body []byte) []byte {
	norm := body
	var v any
	if len(body) > 0 && json.Unmarshal(body, &v) == nil {
		if b, err := json.Marshal(v); err == nil {
			norm = b
		}
	}
	h := sha256.New()
	h.Write([]byte(method + " " + path + "\n"))
	h.Write(norm)
	return h.Sum(nil)
}

// Begin ghi khoá ở trạng thái DANG_XU_LY trong tx. Trả Stored khác nil khi khoá đã xong trước đó (phát lại).
func Begin(ctx context.Context, tx pgx.Tx, s Scope, hash []byte) (*Stored, error) {
	if st, err := lookup(ctx, tx, s, hash); st != nil || err != nil {
		return st, err
	}
	tag, err := tx.Exec(ctx, `INSERT INTO registry.idempotency_key (tenant_id, actor_id, idem_key, created_day, request_hash, status)
		VALUES ($1, $2, $3, current_date, $4, 'DANG_XU_LY') ON CONFLICT DO NOTHING`, s.TenantID, s.ActorID, s.Key, hash)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 1 {
		return nil, nil
	}
	// Giao dịch khác vừa commit cùng khoá.
	st, err := lookup(ctx, tx, s, hash)
	if err == nil && st == nil {
		return nil, errors.New("idem: khoá đang được xử lý")
	}
	return st, err
}

func lookup(ctx context.Context, tx pgx.Tx, s Scope, hash []byte) (*Stored, error) {
	var (
		h      []byte
		status string
		code   *int16
		body   []byte
		ref    *string
	)
	err := tx.QueryRow(ctx, `SELECT request_hash, status, response_code, response_body, resource_ref FROM registry.idempotency_key
		WHERE tenant_id = $1 AND actor_id = $2 AND idem_key = $3 AND created_at > now() - $4::interval
		ORDER BY created_at DESC LIMIT 1`, s.TenantID, s.ActorID, s.Key, retentionSQL).Scan(&h, &status, &code, &body, &ref)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if string(h) != string(hash) {
		return nil, ErrMismatch
	}
	if status != "XONG" || code == nil {
		return nil, nil
	}
	st := &Stored{Code: int(*code), Body: body}
	if ref != nil {
		st.ResourceRef = *ref
	}
	return st, nil
}

// Complete lưu phản hồi của lần chạy đầu trong cùng tx.
func Complete(ctx context.Context, tx pgx.Tx, s Scope, code int, body any, resourceRef string) error {
	var b []byte
	if body != nil {
		var err error
		if b, err = json.Marshal(body); err != nil {
			return err
		}
	}
	var ref *string
	if resourceRef != "" {
		ref = &resourceRef
	}
	_, err := tx.Exec(ctx, `UPDATE registry.idempotency_key SET status = 'XONG', response_code = $4, response_body = $5, resource_ref = $6
		WHERE tenant_id = $1 AND actor_id = $2 AND idem_key = $3 AND status = 'DANG_XU_LY'`, s.TenantID, s.ActorID, s.Key, code, b, ref)
	return err
}

// Purge xoá khoá quá hạn giữ (thay pg_partman DROP phân vùng khi chưa có extension); trả số dòng đã xoá.
func Purge(ctx context.Context, pool *pgxpool.Pool) (int64, error) {
	tag, err := pool.Exec(ctx, `DELETE FROM registry.idempotency_key WHERE created_at < now() - $1::interval`, retentionSQL)
	return tag.RowsAffected(), err
}
