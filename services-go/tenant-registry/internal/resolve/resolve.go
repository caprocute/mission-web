// Package resolve phân giải tenant theo tên miền hoặc mã (QT-00 bước 2, SCR-0001; TL-01 SVC-03) với bộ đệm
// trong tiến trình: mục còn tươi trong TTL (mặc định 30 giây theo TL-03 dòng 1029) thì không hỏi CSDL; khi
// CSDL không truy cập được, phục vụ mục cũ còn trong hạn chịu lỗi (mặc định 300 giây, bằng hạn đệm 5 phút
// mà APISIX được phép giữ), không có thì báo ErrUnavailable (API trả 503 kèm Retry-After: 30).
// Bộ đệm bị vô hiệu khi tenant, tên miền, IdP hoặc gán cell đổi: trong tiến trình qua Invalidate, giữa các
// replica qua PostgreSQL LISTEN/NOTIFY kênh registry_tenant_changed (suy luận: TL chưa ghi cơ chế).
package resolve

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/store"
)

// NotifyChannel là kênh NOTIFY khi dữ liệu phân giải của một tenant đổi (payload: tenant id).
const NotifyChannel = "registry_tenant_changed"

// Lỗi phân giải.
var (
	ErrNotFound    = errors.New("resolve: không có tenant hoặc tenant chưa gắn cell")
	ErrUnavailable = errors.New("resolve: CSDL không truy cập được và không có đệm")
)

// Idp là IdP đang hoạt động của tenant.
type Idp struct {
	Alias        string
	Protocol     string
	Issuer       string
	IsVneid      bool
	EmailDomains []string
}

// Resolution là kết quả phân giải.
type Resolution struct {
	TenantID     uuid.UUID
	TenantCode   string
	TenantName   string
	Status       string
	TenancyModel string
	CellID       uuid.UUID
	CellCode     string
	CellURL      string
	Idps         []Idp
}

// Loader đọc dữ liệu phân giải từ nguồn (CSDL).
type Loader interface {
	ByHost(ctx context.Context, host string) (*Resolution, error)
	ByTenant(ctx context.Context, tenant string) (*Resolution, error)
}

type entry struct {
	res       *Resolution // nil: không tồn tại (đệm âm, chỉ dùng khi còn tươi)
	fetchedAt time.Time
}

// Options cấu hình bộ đệm.
type Options struct {
	TTL      time.Duration // độ tươi, mặc định 30 giây
	StaleMax time.Duration // hạn phục vụ mục cũ khi CSDL lỗi, mặc định 300 giây
	Timeout  time.Duration // hạn mỗi lần đọc CSDL, mặc định 2 giây
	Now      func() time.Time
}

// Service là bộ phân giải có đệm.
type Service struct {
	loader Loader
	opt    Options
	mu     sync.RWMutex
	cache  map[string]entry
	keys   map[uuid.UUID]map[string]struct{}
}

// New tạo Service.
func New(l Loader, o Options) *Service {
	if o.TTL == 0 {
		o.TTL = 30 * time.Second
	}
	if o.StaleMax == 0 {
		o.StaleMax = 300 * time.Second
	}
	if o.Timeout == 0 {
		o.Timeout = 2 * time.Second
	}
	if o.Now == nil {
		o.Now = time.Now
	}
	return &Service{loader: l, opt: o, cache: map[string]entry{}, keys: map[uuid.UUID]map[string]struct{}{}}
}

// NormalizeHost chuẩn hoá tên miền: chữ thường, bỏ cổng và dấu chấm cuối.
func NormalizeHost(h string) string {
	h = strings.ToLower(strings.TrimSpace(h))
	if i := strings.LastIndexByte(h, ':'); i >= 0 && !strings.Contains(h[i:], "]") {
		h = h[:i]
	}
	return strings.TrimSuffix(h, ".")
}

// ByHost phân giải theo tên miền.
func (s *Service) ByHost(ctx context.Context, host string) (*Resolution, error) {
	host = NormalizeHost(host)
	return s.get(ctx, "h:"+host, func(c context.Context) (*Resolution, error) { return s.loader.ByHost(c, host) })
}

// ByTenant phân giải theo mã tenant hoặc tenant_id.
func (s *Service) ByTenant(ctx context.Context, tenant string) (*Resolution, error) {
	tenant = strings.TrimSpace(tenant)
	if id, err := uuid.Parse(tenant); err == nil {
		tenant = id.String()
	} else {
		tenant = strings.ToLower(tenant)
	}
	return s.get(ctx, "t:"+tenant, func(c context.Context) (*Resolution, error) { return s.loader.ByTenant(c, tenant) })
}

func (s *Service) get(ctx context.Context, key string, load func(context.Context) (*Resolution, error)) (*Resolution, error) {
	now := s.opt.Now()
	s.mu.RLock()
	e, ok := s.cache[key]
	s.mu.RUnlock()
	if ok && now.Sub(e.fetchedAt) < s.opt.TTL {
		if e.res == nil {
			return nil, ErrNotFound
		}
		return e.res, nil
	}
	lctx, cancel := context.WithTimeout(ctx, s.opt.Timeout)
	defer cancel()
	res, err := load(lctx)
	switch {
	case err == nil:
		s.put(key, res, now)
		return res, nil
	case errors.Is(err, ErrNotFound):
		s.put(key, nil, now)
		return nil, ErrNotFound
	}
	// Lỗi CSDL: phục vụ mục cũ còn trong hạn chịu lỗi.
	if ok && e.res != nil && now.Sub(e.fetchedAt) < s.opt.StaleMax {
		return e.res, nil
	}
	return nil, ErrUnavailable
}

func (s *Service) put(key string, res *Resolution, now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cache[key] = entry{res: res, fetchedAt: now}
	if res != nil {
		set := s.keys[res.TenantID]
		if set == nil {
			set = map[string]struct{}{}
			s.keys[res.TenantID] = set
		}
		set[key] = struct{}{}
	}
}

// Invalidate xoá mọi mục đệm của tenant và mọi mục đệm âm (tên miền mới có thể vừa được gán).
func (s *Service) Invalidate(tenantID uuid.UUID) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for k := range s.keys[tenantID] {
		delete(s.cache, k)
	}
	delete(s.keys, tenantID)
	for k, e := range s.cache {
		if e.res == nil {
			delete(s.cache, k)
		}
	}
}

// Flush xoá toàn bộ đệm (khi mất kết nối LISTEN có thể đã lỡ thông báo).
func (s *Service) Flush() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cache = map[string]entry{}
	s.keys = map[uuid.UUID]map[string]struct{}{}
}

// Listen nghe kênh NotifyChannel và vô hiệu đệm; tự kết nối lại, chạy tới khi ctx kết thúc.
func (s *Service) Listen(ctx context.Context, pool *pgxpool.Pool, log *slog.Logger) {
	for ctx.Err() == nil {
		err := s.listenOnce(ctx, pool)
		if ctx.Err() != nil {
			return
		}
		log.Warn("mất kết nối nghe thay đổi registry, kết nối lại", "err", err)
		s.Flush()
		select {
		case <-ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}
	}
}

func (s *Service) listenOnce(ctx context.Context, pool *pgxpool.Pool) error {
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, "LISTEN "+NotifyChannel); err != nil {
		return err
	}
	s.Flush()
	for {
		n, err := conn.Conn().WaitForNotification(ctx)
		if err != nil {
			// Huỷ LISTEN trước khi trả kết nối về pool.
			_, _ = conn.Exec(context.Background(), "UNLISTEN *")
			return err
		}
		if id, err := uuid.Parse(n.Payload); err == nil {
			s.Invalidate(id)
		} else {
			s.Flush()
		}
	}
}

// Notify gửi thông báo vô hiệu đệm trong giao dịch q (gửi khi giao dịch commit).
func Notify(ctx context.Context, q store.Querier, tenantID uuid.UUID) error {
	_, err := q.Exec(ctx, `SELECT pg_notify($1, $2)`, NotifyChannel, tenantID.String())
	return err
}

// ---------------------------------------------------------------- loader CSDL

// DBLoader đọc từ schema registry.
type DBLoader struct {
	Pool *pgxpool.Pool
	Now  func() time.Time
}

// ByHost tra registry.tenant_domain rồi dựng kết quả.
func (l *DBLoader) ByHost(ctx context.Context, host string) (*Resolution, error) {
	id, err := store.TenantIDByDomain(ctx, l.Pool, host)
	if errors.Is(err, store.ErrNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	t, err := store.GetTenant(ctx, l.Pool, id)
	if err != nil {
		return nil, l.mapNF(err)
	}
	return l.build(ctx, t)
}

// ByTenant tra theo tenant_id (uuid) hoặc registry.tenant.code.
func (l *DBLoader) ByTenant(ctx context.Context, tenant string) (*Resolution, error) {
	var (
		t   *store.Tenant
		err error
	)
	if id, e := uuid.Parse(tenant); e == nil {
		t, err = store.GetTenant(ctx, l.Pool, id)
	} else {
		t, err = store.GetTenantByCode(ctx, l.Pool, tenant)
	}
	if err != nil {
		return nil, l.mapNF(err)
	}
	return l.build(ctx, t)
}

func (l *DBLoader) mapNF(err error) error {
	if errors.Is(err, store.ErrNotFound) {
		return ErrNotFound
	}
	return err
}

func (l *DBLoader) build(ctx context.Context, t *store.Tenant) (*Resolution, error) {
	now := time.Now
	if l.Now != nil {
		now = l.Now
	}
	tc, err := store.CurrentTenantCell(ctx, l.Pool, t.ID, now())
	if err != nil {
		return nil, l.mapNF(err)
	}
	idps, err := store.ListIdps(ctx, l.Pool, t.ID, true)
	if err != nil {
		return nil, err
	}
	r := &Resolution{TenantID: t.ID, TenantCode: t.Code, TenantName: t.Name, Status: t.Status, TenancyModel: t.TenancyModel,
		CellID: tc.CellID, CellCode: tc.CellCode, CellURL: tc.CellURL}
	for _, i := range idps {
		r.Idps = append(r.Idps, Idp{Alias: i.IdpAlias, Protocol: i.Protocol, Issuer: i.Issuer, IsVneid: i.IsVneid, EmailDomains: i.EmailDomains})
	}
	return r, nil
}
