package move

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ChainHead là đầu một chuỗi băm của tenant (ledger.chain_head: chain_partition, last_seq_no, last_hash).
type ChainHead struct {
	Partition int    `json:"chainPartition"`
	LastSeqNo int64  `json:"lastSeqNo"`
	LastHash  string `json:"lastHash"` // hex 64 ký tự
}

// Handover là gói chuyển giao sổ cái: đầu 64 chuỗi ở cell nguồn và mã niêm phong cuối (TL-03 "Sổ cái bất biến":
// chuỗi ở cell đích nối tiếp từ entry_hash cuối; bản ghi TENANT_MOVED_IN mang mã niêm phong cuối của nguồn).
type Handover struct {
	SealID string      `json:"sealId"`
	Heads  []ChainHead `json:"heads"`
}

// Ledger là cổng phối hợp với ledger-service (SVC-09) của hai cell.
type Ledger interface {
	// Seal: cell nguồn khoá chuỗi của tenant, ghi và niêm phong bản ghi chuyển giao, trả đầu chuỗi.
	Seal(ctx context.Context, cell string, tenantID, moveID uuid.UUID, toCell string) (*Handover, error)
	// Accept: cell đích khởi tạo chain_head từ đầu chuỗi nguồn và ghi bản ghi TENANT_MOVED_IN.
	Accept(ctx context.Context, cell string, tenantID, moveID uuid.UUID, fromCell string, h *Handover) error
	// Unseal: quay lui ở cell nguồn khi chưa cắt chuyển.
	Unseal(ctx context.Context, cell string, tenantID, moveID uuid.UUID) error
}

// LedgerHTTP gọi API nội bộ của ledger-service (suy luận đường dẫn, chưa có trong contracts/; wiki trang 14):
// POST {base}/internal/v1/tenants/{id}/handover/{seal|accept|unseal}.
type LedgerHTTP struct {
	// BaseURL trả địa chỉ ledger-service của cell (mẫu có {cell}).
	BaseURL string
	Token   func(ctx context.Context, cell string) (string, error)
	HTTP    *http.Client
}

func (l *LedgerHTTP) post(ctx context.Context, cell string, tenantID uuid.UUID, op string, body, out any) error {
	b, _ := json.Marshal(body)
	u := strings.TrimRight(strings.ReplaceAll(l.BaseURL, "{cell}", strings.ToLower(cell)), "/") +
		"/internal/v1/tenants/" + tenantID.String() + "/handover/" + op
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-Id", tenantID.String())
	if l.Token != nil {
		tok, err := l.Token(ctx, cell)
		if err != nil {
			return err
		}
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	c := l.HTTP
	if c == nil {
		c = &http.Client{Timeout: time.Minute}
	}
	resp, err := c.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	rb, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		return fmt.Errorf("ledger-service %s %s: HTTP %d", cell, op, resp.StatusCode)
	}
	if out != nil {
		return json.Unmarshal(rb, out)
	}
	return nil
}

// Seal hiện thực Ledger.Seal.
func (l *LedgerHTTP) Seal(ctx context.Context, cell string, tenantID, moveID uuid.UUID, toCell string) (*Handover, error) {
	var h Handover
	err := l.post(ctx, cell, tenantID, "seal", map[string]any{"moveId": moveID, "toCell": toCell}, &h)
	if err == nil && (h.SealID == "" || len(h.Heads) == 0) {
		err = fmt.Errorf("ledger-service %s: gói chuyển giao rỗng", cell)
	}
	return &h, err
}

// Accept hiện thực Ledger.Accept.
func (l *LedgerHTTP) Accept(ctx context.Context, cell string, tenantID, moveID uuid.UUID, fromCell string, h *Handover) error {
	return l.post(ctx, cell, tenantID, "accept", map[string]any{"moveId": moveID, "fromCell": fromCell, "sealId": h.SealID, "heads": h.Heads}, nil)
}

// Unseal hiện thực Ledger.Unseal.
func (l *LedgerHTTP) Unseal(ctx context.Context, cell string, tenantID, moveID uuid.UUID) error {
	return l.post(ctx, cell, tenantID, "unseal", map[string]any{"moveId": moveID}, nil)
}
