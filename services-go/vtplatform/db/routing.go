package db

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

// TenancyModel biểu diễn mô hình cô lập dữ liệu khách thuê (ADR-07).
type TenancyModel string

const (
	ModelPool   TenancyModel = "POOL"
	ModelBridge TenancyModel = "BRIDGE"
	ModelSilo   TenancyModel = "SILO"
	ModelAirgap TenancyModel = "AIRGAP"

	PoolDatabaseName = "td_pool"
)

var (
	// ErrTenantNotFound trả về khi không tìm thấy bản ghi định tuyến cho khách thuê.
	ErrTenantNotFound = errors.New("vtplatform/db: tenant route not found")
	// ErrWrongCell trả về khi khách thuê thuộc một cell khác với cell hiện tại (HTTP 421).
	ErrWrongCell = errors.New("vtplatform/db: tenant belongs to another cell")
	// ErrTenantUnavailable trả về khi khách thuê đang chuyển cell hoặc tạm ngưng (HTTP 503).
	ErrTenantUnavailable = errors.New("vtplatform/db: tenant is migrating cell or unavailable")
	// ErrPoolNotConfigured trả về khi không tìm thấy hoặc không thể tạo pool CSDL cho khách thuê.
	ErrPoolNotConfigured = errors.New("vtplatform/db: database pool is not configured")
)

// TenantRoute chứa thông tin định tuyến cell và CSDL của một khách thuê trong bộ nhớ.
type TenantRoute struct {
	TenantID      string
	TenantCode    string
	CellCode      string
	CellAPIBase   string
	TenancyModel  TenancyModel
	DatabaseName  string
	Status        string
	SourceVersion int64
	ExpiresAt     time.Time
}

// DefaultDatabaseName suy diễn tên CSDL chuẩn theo quy ước ADR-07 nếu DatabaseName để trống.
func DefaultDatabaseName(model TenancyModel, tenantCode string) string {
	if strings.EqualFold(string(model), string(ModelPool)) || model == "" {
		return PoolDatabaseName
	}
	sanitized := strings.ReplaceAll(strings.ToLower(strings.TrimSpace(tenantCode)), "-", "_")
	return "td_" + sanitized
}

// IsUnavailable kiểm tra trạng thái khách thuê không thể phục vụ giao dịch (DANG_CHUYEN_CELL, DA_CHAM_DUT).
// Khách thuê TAM_NGUNG không thuộc nhóm này: TL-04 mục 11.4 giữ dữ liệu, chỉ chặn ghi (xem IsSuspended).
func (r TenantRoute) IsUnavailable() bool {
	s := strings.ToUpper(strings.TrimSpace(r.Status))
	return s == "DANG_CHUYEN_CELL" || s == "DA_CHAM_DUT"
}

// IsSuspended kiểm tra khách thuê đang tạm ngừng: vẫn đọc được, lệnh ghi của người dùng bị từ chối 403.
func (r TenantRoute) IsSuspended() bool {
	return strings.EqualFold(strings.TrimSpace(r.Status), "TAM_NGUNG")
}

// suspendedProblem là phản hồi 403 COMMON-403-FORBIDDEN kèm tenantCode, tenantStatus để giao diện hiện thông báo
// tạm ngừng (MSG-00-07), cùng dạng với bộ lọc của thư viện nền Java.
type suspendedProblem struct {
	problem.ProblemDetails
	TenantCode   string `json:"tenantCode"`
	TenantStatus string `json:"tenantStatus"`
}

func isReadMethod(m string) bool {
	return m == http.MethodGet || m == http.MethodHead || m == http.MethodOptions
}

// TenantChangedEvent biểu diễn sự kiện Kafka okr.registry.tenant.v1 (TenantChanged) để cập nhật bộ nhớ đệm.
type TenantChangedEvent struct {
	TenantCode    string   `json:"tenantCode"`
	Status        string   `json:"status"`
	TenancyModel  string   `json:"tenancyModel"`
	CellCode      string   `json:"cellCode"`
	DatabaseName  string   `json:"databaseName"`
	Domains       []string `json:"domains"`
	Flows         []string `json:"flows"`
	SourceVersion int64    `json:"sourceVersion"`
}

// RegistryResolver định nghĩa hợp đồng phân giải định tuyến khách thuê từ dịch vụ tenant-registry.
type RegistryResolver interface {
	ResolveByTenantID(ctx context.Context, tenantID string) (TenantRoute, bool, error)
	ResolveByCode(ctx context.Context, tenantCode string) (TenantRoute, bool, error)
}

// HTTPRegistryResolver gọi GET /api/v1/registry/resolve của tenant-registry.
type HTTPRegistryResolver struct {
	BaseURL    string
	HTTPClient *http.Client
	DefaultTTL time.Duration
}

// NewHTTPRegistryResolver tạo bộ phân giải HTTP với cấu hình mặc định an toàn.
func NewHTTPRegistryResolver(baseURL string, timeout time.Duration) *HTTPRegistryResolver {
	if timeout <= 0 {
		timeout = 2 * time.Second
	}
	return &HTTPRegistryResolver{
		BaseURL:    strings.TrimRight(baseURL, "/"),
		HTTPClient: &http.Client{Timeout: timeout},
		DefaultTTL: 5 * time.Minute,
	}
}

func (h *HTTPRegistryResolver) ResolveByTenantID(ctx context.Context, tenantID string) (TenantRoute, bool, error) {
	return h.resolve(ctx, "tenantId", tenantID)
}

func (h *HTTPRegistryResolver) ResolveByCode(ctx context.Context, tenantCode string) (TenantRoute, bool, error) {
	return h.resolve(ctx, "code", tenantCode)
}

func (h *HTTPRegistryResolver) resolve(ctx context.Context, param, value string) (TenantRoute, bool, error) {
	u := fmt.Sprintf("%s/api/v1/registry/resolve?%s=%s", h.BaseURL, param, url.QueryEscape(value))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return TenantRoute{}, false, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := h.HTTPClient.Do(req)
	if err != nil {
		return TenantRoute{}, false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return TenantRoute{}, false, nil
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return TenantRoute{}, false, fmt.Errorf("tenant-registry returned HTTP %d", resp.StatusCode)
	}
	var payload struct {
		TenantID     string `json:"tenantId"`
		TenantCode   string `json:"tenantCode"`
		Status       string `json:"status"`
		TenancyModel string `json:"tenancyModel"`
		Cell         struct {
			Code       string `json:"code"`
			APIBaseURL string `json:"apiBaseUrl"`
		} `json:"cell"`
		DatabaseName    string `json:"databaseName"`
		CacheTTLSeconds int64  `json:"cacheTtlSeconds"`
		SourceVersion   int64  `json:"sourceVersion"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return TenantRoute{}, false, err
	}
	model := TenancyModel(strings.ToUpper(strings.TrimSpace(payload.TenancyModel)))
	if model == "" {
		model = ModelPool
	}
	dbName := strings.TrimSpace(payload.DatabaseName)
	if dbName == "" {
		dbName = DefaultDatabaseName(model, payload.TenantCode)
	}
	cellCode := strings.TrimSpace(payload.Cell.Code)
	if cellCode == "" {
		cellCode = "cell-shared-01"
	}
	ttl := h.DefaultTTL
	if payload.CacheTTLSeconds > 0 {
		ttl = time.Duration(payload.CacheTTLSeconds) * time.Second
	}
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	return TenantRoute{
		TenantID:      strings.ToLower(strings.TrimSpace(payload.TenantID)),
		TenantCode:    strings.ToLower(strings.TrimSpace(payload.TenantCode)),
		CellCode:      cellCode,
		CellAPIBase:   payload.Cell.APIBaseURL,
		TenancyModel:  model,
		DatabaseName:  dbName,
		Status:        payload.Status,
		SourceVersion: payload.SourceVersion,
		ExpiresAt:     time.Now().Add(ttl),
	}, true, nil
}

// Directory lưu trữ bảng ánh xạ khách thuê sang cell và CSDL trong bộ nhớ (< 10 µs mỗi lần tra cứu).
type Directory struct {
	mu       sync.RWMutex
	byID     map[string]TenantRoute
	byCode   map[string]TenantRoute
	resolver RegistryResolver
	nowFunc  func() time.Time
	eventTTL time.Duration
}

// NewDirectory khởi tạo danh bạ định tuyến trong bộ nhớ.
func NewDirectory(resolver RegistryResolver) *Directory {
	return &Directory{
		byID:     make(map[string]TenantRoute),
		byCode:   make(map[string]TenantRoute),
		resolver: resolver,
		nowFunc:  time.Now,
		eventTTL: 5 * time.Minute,
	}
}

// Put cập nhật bản ghi định tuyến vào bộ nhớ đệm (bỏ qua nếu SourceVersion nhỏ hơn bản ghi hiện có).
func (d *Directory) Put(route TenantRoute) {
	codeKey := strings.ToLower(strings.TrimSpace(route.TenantCode))
	idKey := strings.ToLower(strings.TrimSpace(route.TenantID))
	if route.TenancyModel == "" {
		route.TenancyModel = ModelPool
	}
	if route.DatabaseName == "" {
		route.DatabaseName = DefaultDatabaseName(route.TenancyModel, codeKey)
	}
	if route.Status == "" {
		route.Status = "DANG_VAN_HANH"
	}
	if route.ExpiresAt.IsZero() {
		route.ExpiresAt = d.nowFunc().Add(d.eventTTL)
	}
	route.TenantCode = codeKey
	route.TenantID = idKey

	d.mu.Lock()
	defer d.mu.Unlock()
	if existing, ok := d.byCode[codeKey]; ok {
		if existing.SourceVersion > 0 && route.SourceVersion > 0 && route.SourceVersion < existing.SourceVersion {
			return
		}
		if idKey == "" && existing.TenantID != "" {
			idKey = existing.TenantID
			route.TenantID = idKey
		}
	}
	if codeKey != "" {
		d.byCode[codeKey] = route
	}
	if idKey != "" {
		d.byID[idKey] = route
	}
}

// ApplyEvent xử lý sự kiện TenantChanged từ Kafka okr.registry.tenant.v1.
func (d *Directory) ApplyEvent(ev TenantChangedEvent) {
	codeKey := strings.ToLower(strings.TrimSpace(ev.TenantCode))
	model := TenancyModel(strings.ToUpper(strings.TrimSpace(ev.TenancyModel)))
	if model == "" {
		model = ModelPool
	}
	dbName := strings.TrimSpace(ev.DatabaseName)
	if dbName == "" {
		dbName = DefaultDatabaseName(model, codeKey)
	}
	d.mu.RLock()
	existing, hasExisting := d.byCode[codeKey]
	d.mu.RUnlock()
	cellCode := strings.TrimSpace(ev.CellCode)
	if cellCode == "" {
		if hasExisting && existing.CellCode != "" {
			cellCode = existing.CellCode
		} else {
			cellCode = "cell-shared-01"
		}
	}
	var tenantID, cellAPI string
	if hasExisting {
		tenantID = existing.TenantID
		cellAPI = existing.CellAPIBase
	}
	d.Put(TenantRoute{
		TenantID:      tenantID,
		TenantCode:    codeKey,
		CellCode:      cellCode,
		CellAPIBase:   cellAPI,
		TenancyModel:  model,
		DatabaseName:  dbName,
		Status:        ev.Status,
		SourceVersion: ev.SourceVersion,
		ExpiresAt:     d.nowFunc().Add(d.eventTTL),
	})
}

// Resolve tra cứu định tuyến theo tenantID trong bộ nhớ đệm hoặc qua RegistryResolver.
func (d *Directory) Resolve(ctx context.Context, tenantID string) (TenantRoute, bool, error) {
	idKey := strings.ToLower(strings.TrimSpace(tenantID))
	now := d.nowFunc()

	d.mu.RLock()
	cached, ok := d.byID[idKey]
	d.mu.RUnlock()
	if ok && now.Before(cached.ExpiresAt) {
		return cached, true, nil
	}
	if d.resolver == nil {
		if ok {
			return cached, true, nil
		}
		return TenantRoute{}, false, nil
	}
	resolved, found, err := d.resolver.ResolveByTenantID(ctx, idKey)
	if err != nil {
		if ok {
			return cached, true, nil
		}
		return TenantRoute{}, false, err
	}
	if !found {
		return TenantRoute{}, false, nil
	}
	d.Put(resolved)
	return resolved, true, nil
}

// PoolFactory khởi tạo TxBeginner (pool kết nối CSDL) cho một tên CSDL cụ thể (ví dụ "td_pool", "td_hcm_a").
type PoolFactory func(ctx context.Context, databaseName string) (TxBeginner, error)

// PoolRouter định tuyến kết nối CSDL theo khách thuê hiện hành trong context (ADR-07).
type PoolRouter struct {
	directory     *Directory
	localCellCode string
	factory       PoolFactory
	mu            sync.RWMutex
	pools         map[string]TxBeginner
}

// NewPoolRouter tạo bộ định tuyến pool CSDL cho cell hiện tại.
func NewPoolRouter(directory *Directory, localCellCode string, defaultPool TxBeginner, factory PoolFactory) *PoolRouter {
	pools := make(map[string]TxBeginner)
	if defaultPool != nil {
		pools[PoolDatabaseName] = defaultPool
	}
	if strings.TrimSpace(localCellCode) == "" {
		localCellCode = "cell-shared-01"
	}
	return &PoolRouter{
		directory:     directory,
		localCellCode: localCellCode,
		factory:       factory,
		pools:         pools,
	}
}

// ResolveDatabaseName xác định tên CSDL đích và kiểm tra cell / trạng thái của khách thuê trong ctx.
func (r *PoolRouter) ResolveDatabaseName(ctx context.Context) (string, TenantRoute, error) {
	tid, ok := tenant.FromContext(ctx)
	if !ok {
		return "", TenantRoute{}, tenant.ErrMissingTenant
	}
	route, found, err := r.directory.Resolve(ctx, tid)
	if err != nil {
		return "", TenantRoute{}, err
	}
	if !found {
		return "", TenantRoute{}, ErrTenantNotFound
	}
	if route.IsUnavailable() {
		return "", route, ErrTenantUnavailable
	}
	if !strings.EqualFold(r.localCellCode, route.CellCode) {
		return "", route, ErrWrongCell
	}
	return route.DatabaseName, route, nil
}

// PoolForContext trả về TxBeginner tương ứng với khách thuê trong ctx (tạo lười nếu chưa có).
func (r *PoolRouter) PoolForContext(ctx context.Context) (TxBeginner, error) {
	dbName, _, err := r.ResolveDatabaseName(ctx)
	if err != nil {
		return nil, err
	}
	r.mu.RLock()
	pool, ok := r.pools[dbName]
	r.mu.RUnlock()
	if ok && pool != nil {
		return pool, nil
	}
	if r.factory == nil {
		return nil, ErrPoolNotConfigured
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if pool, ok = r.pools[dbName]; ok && pool != nil {
		return pool, nil
	}
	created, err := r.factory(ctx, dbName)
	if err != nil {
		return nil, err
	}
	r.pools[dbName] = created
	return created, nil
}

// InRoutedTx mở giao dịch trên đúng pool CSDL của khách thuê trong ctx và đặt SET LOCAL app.tenant_id.
func (r *PoolRouter) InRoutedTx(ctx context.Context, fn func(ctx context.Context, tx Tx) error) error {
	pool, err := r.PoolForContext(ctx)
	if err != nil {
		return err
	}
	return InTx(ctx, pool, fn)
}

// CellRoutingMiddleware kiểm tra cell và trạng thái của khách thuê ở tầng HTTP cho dịch vụ Go:
// trả về 503 nếu khách thuê đang chuyển cell hoặc đã chấm dứt, 403 cho lệnh ghi của khách thuê tạm ngừng
// (lệnh đọc đi tiếp) và 421 nếu gửi nhầm cell.
func CellRoutingMiddleware(directory *Directory, localCellCode string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			tid, ok := tenant.FromContext(r.Context())
			if !ok {
				next.ServeHTTP(w, r)
				return
			}
			route, found, err := directory.Resolve(r.Context(), tid)
			if err != nil || !found {
				next.ServeHTTP(w, r)
				return
			}
			traceID := otel.TraceIDFromContext(r.Context())
			if route.IsUnavailable() {
				w.Header().Set("Retry-After", "30")
				problem.Write(w, problem.ProblemDetails{
					Type:     "https://errors.okrkpi.viettel.vn/service-unavailable",
					Title:    "Dịch vụ tạm thời không khả dụng",
					Status:   http.StatusServiceUnavailable,
					Detail:   fmt.Sprintf("Đơn vị '%s' đang chuyển đổi hạ tầng hoặc tạm ngưng (%s).", route.TenantCode, route.Status),
					Instance: problem.SafeInstanceURI(r.URL.Path),
					Code:     "COMMON-503-SERVICE-UNAVAILABLE",
					TraceID:  traceID,
				})
				return
			}
			if route.IsSuspended() && !isReadMethod(r.Method) {
				pd := problem.Forbidden(
					"Đơn vị của bạn đang tạm ngừng sử dụng hệ thống. Bạn vẫn xem được dữ liệu nhưng chưa thể cập nhật cho tới khi đơn vị được mở lại.",
					r.URL.Path, traceID)
				w.Header().Set("Content-Type", problem.ContentType)
				w.Header().Set("X-Content-Type-Options", "nosniff")
				w.WriteHeader(http.StatusForbidden)
				_ = json.NewEncoder(w).Encode(suspendedProblem{ProblemDetails: pd, TenantCode: route.TenantCode, TenantStatus: route.Status})
				return
			}
			if !strings.EqualFold(localCellCode, route.CellCode) {
				w.Header().Set("X-Target-Cell", route.CellCode)
				pd := problem.FromCode(
					"REGISTRY-421-TENANT-MOVED",
					fmt.Sprintf("Đơn vị '%s' thuộc cell '%s', không phải cell '%s'.", route.TenantCode, route.CellCode, localCellCode),
					nil,
					r.URL.Path,
					traceID,
				)
				pd.Status = http.StatusMisdirectedRequest
				problem.Write(w, pd)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
