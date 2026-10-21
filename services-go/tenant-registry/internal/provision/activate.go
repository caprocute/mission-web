package provision

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/platform/dbx"
	"mission/services-go/tenant-registry/internal/platform/problem"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

// Mã mục kiểm tra tổng thể (config.yaml ReadinessCheck.code).
const (
	CheckCapacity   = "CAPACITY"
	CheckDomain     = "DOMAIN"
	CheckTLS        = "TLS"
	CheckPacks      = "POLICY_PACK_GOLDEN"
	CheckIdp        = "IDP"
	CheckOrgTree    = "ORG_TREE"
	CheckHighDisc   = "HIGH_DISCREPANCY"
	defaultRPO      = 15 * time.Minute
	checkTimeout    = 10 * time.Second
	readyForSanSang = "IDP,ORG_TREE,HIGH_DISCREPANCY"
)

// Readiness là kết quả kiểm tra tổng thể QT-01 bước 9.
type Readiness struct {
	Checks     []tenant.ReadinessCheck
	HighCount  int
	Lag        time.Duration
	LagKnown   bool
	LagWarning string
}

// Passed: mọi mục trong codes (rỗng = mọi mục) đều đạt.
func (r Readiness) Passed(codes ...string) bool {
	for _, c := range r.Checks {
		if (len(codes) == 0 || containsCode(codes, c.Code)) && !c.Passed {
			return false
		}
	}
	return true
}

func containsCode(codes []string, c string) bool {
	for _, x := range codes {
		if x == c {
			return true
		}
	}
	return false
}

// Evaluate chạy kiểm tra tổng thể cho tenant: dung lượng, tên miền, chứng chỉ, Policy Pack đạt test case vàng,
// IdP hoạt động (kết quả kiểm kết nối DV-03-06), cây tổ chức, lệch mức Cao; cộng độ trễ bản sao dự phòng.
func (s *Saga) Evaluate(ctx context.Context, q store.Querier, t *store.Tenant) (Readiness, error) {
	var r Readiness
	add := func(code string, ok bool, detail string) {
		r.Checks = append(r.Checks, tenant.ReadinessCheck{Code: code, Passed: ok, Detail: detail})
	}
	b, err := store.LatestTenantCell(ctx, q, t.ID)
	if err != nil {
		return r, err
	}
	cells, err := store.ListCells(ctx, q, store.CellFilter{})
	if err != nil {
		return r, err
	}
	var cell *store.Cell
	for i := range cells {
		if cells[i].ID == b.CellID {
			cell = &cells[i]
		}
	}
	if cell == nil {
		return r, errors.New("provision: không thấy cell của tenant")
	}
	rules := s.Tenants.Rules
	load := tenant.CellLoad{Code: cell.Code, Capacity: cell.CapacityAccounts, Reserved: cell.Reserved}
	capOK := cell.Status == "HOAT_DONG" && rules.FitsAfterAdding(load, 0)
	add(CheckCapacity, capOK, fmt.Sprintf("Cell %s ở mức %.0f%% dung lượng thiết kế", cell.Code, tenant.LoadAfter(load, 0)))

	ep, err := s.Endpoints.Endpoints(cell.Code, cell.APIBaseURL)
	if err != nil {
		return r, err
	}
	ds, err := store.ListDomains(ctx, q, t.ID)
	if err != nil {
		return r, err
	}
	domOK, tlsOK := len(ds) > 0, len(ds) > 0
	domDetail, tlsDetail := "Tên miền đã trỏ về cell", "Chứng chỉ bảo mật đã sẵn sàng"
	for _, d := range ds {
		cctx, cancel := context.WithTimeout(ctx, checkTimeout)
		got, err := s.DNS.LookupCNAME(cctx, d.Domain)
		cancel()
		if d.VerifiedAt == nil || err != nil || fqdn(got) != fqdn(ep.EdgeHost) {
			domOK, domDetail = false, "Tên miền "+d.Domain+" chưa trỏ về cell"
		}
		if d.TLSCertRef == nil {
			tlsOK, tlsDetail = false, "Tên miền "+d.Domain+" chưa có chứng chỉ bảo mật"
			continue
		}
		cctx, cancel = context.WithTimeout(ctx, checkTimeout)
		ok, err := s.Certs.Ready(cctx, *d.TLSCertRef)
		cancel()
		if err != nil || !ok {
			tlsOK, tlsDetail = false, "Chứng chỉ bảo mật của "+d.Domain+" chưa sẵn sàng"
		}
	}
	add(CheckDomain, domOK, domDetail)
	add(CheckTLS, tlsOK, tlsDetail)

	cctx, cancel := context.WithTimeout(ctx, checkTimeout)
	packs, err := s.Core.PolicyPacks(cctx, ep, t.ID)
	cancel()
	packOK, packDetail := err == nil, "Gói chính sách đạt 100% test case vàng"
	if err != nil {
		packDetail = "Không đọc được gói chính sách đã gắn"
	}
	for _, f := range t.Flows {
		found := false
		for _, p := range packs {
			if p.Flow != f {
				continue
			}
			found = true
			if p.Status != "DA_PHAT_HANH" || p.GoldenPassPct < 100 {
				name := p.PackCode
				if name == "" {
					name = p.PackVersionID.String()
				}
				packOK, packDetail = false, fmt.Sprintf("Gói chính sách %s chưa đạt 100%% test case vàng", name)
			}
		}
		if !found && err == nil {
			packOK, packDetail = false, "Chưa gắn gói chính sách cho mọi luồng"
		}
	}
	add(CheckPacks, packOK, packDetail)

	idps, err := store.ListIdps(ctx, q, t.ID, true)
	if err != nil {
		return r, err
	}
	meta, err := store.LoadIdpMeta(ctx, q, t.ID)
	if err != nil {
		return r, err
	}
	idpOK := false
	for _, i := range idps {
		if m, ok := meta[i.IdpAlias]; ok && m.LastTestOk != nil && *m.LastTestOk {
			idpOK = true
		}
	}
	idpDetail := "Đăng nhập một lần đã kiểm tra kết nối thành công"
	if !idpOK {
		idpDetail = "Chưa có nhà cung cấp định danh nào kiểm tra kết nối thành công"
	}
	add(CheckIdp, idpOK, idpDetail)

	cctx, cancel = context.WithTimeout(ctx, checkTimeout)
	tree, err := s.Org.HasOrgTree(cctx, ep, t.ID)
	cancel()
	treeDetail := "Đã có cây tổ chức"
	if err != nil {
		treeDetail = "Không đọc được cây tổ chức"
	} else if !tree {
		treeDetail = "Chưa nhập cây tổ chức"
	}
	add(CheckOrgTree, err == nil && tree, treeDetail)

	cctx, cancel = context.WithTimeout(ctx, checkTimeout)
	n, err := s.Org.HighDiscrepancies(cctx, ep, t.ID)
	cancel()
	r.HighCount = n
	highDetail := "Không còn lệch mức Cao"
	if err != nil {
		r.HighCount, highDetail = -1, "Không đọc được số lệch mức Cao"
	} else if n > 0 {
		highDetail = fmt.Sprintf("Còn %d mục lệch mức Cao", n)
	}
	add(CheckHighDisc, err == nil && n == 0, highDetail)

	if s.Lag != nil {
		cctx, cancel = context.WithTimeout(ctx, checkTimeout)
		lag, err := s.Lag.Lag(cctx, cell.Code)
		cancel()
		if err == nil {
			r.Lag, r.LagKnown = lag, true
		} else {
			r.LagWarning = "Chưa đo được độ trễ bản sao dự phòng của cell"
		}
	}
	return r, nil
}

func (s *Saga) rpo() time.Duration {
	if s.RPO > 0 {
		return s.RPO
	}
	return defaultRPO
}

// Activate: activateConfigTenant (QT-01 bước 9, BR-01-10). Chạy kiểm tra tổng thể; còn lệch mức Cao trả 422
// CONFIG-422-HIGH-DISCREPANCY (MSG-01-12); mục khác chưa đạt trả 422 CONFIG-422-READINESS-FAILED; độ trễ bản
// sao vượt RPO 15 phút trả 422 CONFIG-422-RPO-EXCEEDED (dưới ngưỡng chỉ cảnh báo trong nhật ký trạng thái).
// Chuyển SAN_SANG → DANG_VAN_HANH hoặc TAM_NGUNG → DANG_VAN_HANH; tenant DANG_CAU_HINH đạt mọi kiểm tra thì
// đi qua SAN_SANG trong cùng giao dịch (suy luận: "đủ điều kiện → SAN_SANG, tự động kiểm tra"). Phát EVT-21.
func (s *Saga) Activate(ctx context.Context, tx pgx.Tx, a tenant.Actor, id uuid.UUID, expected int, reason string) (*tenant.ConfigTenant, error) {
	if !a.PlatformAdmin {
		return nil, tenant.ErrForbidden
	}
	t, err := store.GetTenantForUpdate(ctx, tx, id)
	if errors.Is(err, store.ErrNotFound) {
		return nil, tenant.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if t.RowVersion != expected {
		return nil, &tenant.VersionError{Sent: expected, Current: t.RowVersion}
	}
	if t.Status != tenant.SanSang && t.Status != tenant.TamNgung && t.Status != tenant.DangCauHinh {
		return nil, &tenant.StateError{Status: t.Status}
	}
	rd, err := s.Evaluate(ctx, tx, t)
	if err != nil {
		return nil, err
	}
	if rd.HighCount > 0 {
		return nil, problem.New(http.StatusUnprocessableEntity, "br-01-10", "Còn lệch mức Cao", "CONFIG-422-HIGH-DISCREPANCY").
			WithMsg("MSG-01-12").WithDetail("Còn %d mục lệch mức Cao. Tenant chưa thể kích hoạt.", rd.HighCount).
			WithErrors(failed(rd)...)
	}
	if !rd.Passed() {
		return nil, problem.New(http.StatusUnprocessableEntity, "readiness-failed", "Chưa đạt kiểm tra tổng thể", "CONFIG-422-READINESS-FAILED").
			WithDetail("Tenant chưa đạt kiểm tra tổng thể nên chưa thể kích hoạt.").WithErrors(failed(rd)...)
	}
	note := strings.TrimSpace(reason)
	if rd.LagKnown && rd.Lag > s.rpo() {
		return nil, problem.New(http.StatusUnprocessableEntity, "rpo-exceeded", "Bản sao dự phòng đang trễ", "CONFIG-422-RPO-EXCEEDED").
			WithDetail("Bản sao dự phòng của cell đang trễ %d phút, vượt ngưỡng %d phút. Vui lòng kích hoạt lại sau.",
				int(rd.Lag.Minutes()), int(s.rpo().Minutes()))
	}
	warn := rd.LagWarning
	if rd.LagKnown && rd.Lag > 0 {
		warn = fmt.Sprintf("Bản sao dự phòng của cell đang trễ %d giây, dưới ngưỡng %d phút", int(rd.Lag.Seconds()), int(s.rpo().Minutes()))
	}
	if warn != "" {
		s.log().WarnContext(ctx, "kích hoạt tenant khi bản sao dự phòng trễ", "tenant_id", id.String(), "detail", warn)
	}
	if t.Status == tenant.DangCauHinh {
		if err := s.Tenants.Transition(ctx, tx, a, t, t.RowVersion, tenant.SanSang, "Đạt kiểm tra tổng thể"); err != nil {
			return nil, err
		}
	}
	msg := "Kích hoạt tenant"
	if note != "" {
		msg += ": " + note
	}
	if warn != "" {
		msg += ". Lưu ý: " + warn
	}
	if err := s.Tenants.Transition(ctx, tx, a, t, t.RowVersion, tenant.DangVanHanh, truncate(msg, 2000)); err != nil {
		return nil, err
	}
	if err := s.notifyActivated(ctx, tx, a, t); err != nil {
		return nil, err
	}
	v, err := s.Tenants.Get(ctx, tx, a, id)
	if err != nil {
		return nil, err
	}
	v.ReadinessChecks = rd.Checks
	return v, nil
}

func failed(rd Readiness) []problem.FieldError {
	var out []problem.FieldError
	for _, c := range rd.Checks {
		if !c.Passed {
			out = append(out, problem.FieldError{Field: "readinessChecks." + c.Code, Message: c.Detail})
		}
	}
	return out
}

// notifyActivated phát EVT-21 cho đầu mối quản trị tenant (tài khoản do saga tạo ở bước Organization).
func (s *Saga) notifyActivated(ctx context.Context, tx pgx.Tx, a tenant.Actor, t *store.Tenant) error {
	j, err := store.LatestJobForTenant(ctx, tx, store.JobTypeProvision, t.ID)
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	var st tenant.ProvisionState
	if err := json.Unmarshal(j.Params, &st); err != nil {
		return err
	}
	run := &Run{Actor: a, state: &st}
	return notify(ctx, tx, run, t, "TENANT_ACTIVATED", map[string]any{"tenantName": t.Name})
}

// PromoteReady tự chuyển tenant DANG_CAU_HINH → SAN_SANG khi IdP hoạt động, đã có cây tổ chức và lệch mức Cao
// bằng 0 (TL-02a QT-01 "Trạng thái đối tượng": tự động kiểm tra). Trả số tenant đã chuyển.
func (s *Saga) PromoteReady(ctx context.Context) (int, error) {
	ids, err := store.TenantIDsByStatus(ctx, s.Pool, tenant.DangCauHinh)
	if err != nil {
		return 0, err
	}
	n := 0
	for _, id := range ids {
		err := dbx.InTx(ctx, s.Pool, func(tx pgx.Tx) error {
			t, err := store.GetTenantForUpdate(ctx, tx, id)
			if err != nil || t.Status != tenant.DangCauHinh {
				return err
			}
			rd, err := s.Evaluate(ctx, tx, t)
			if err != nil {
				return err
			}
			if !rd.Passed(strings.Split(readyForSanSang, ",")...) {
				return nil
			}
			n++
			return s.Tenants.Transition(ctx, tx, tenant.Actor{ID: store.SystemActor}, t, t.RowVersion, tenant.SanSang,
				"Đủ điều kiện: nhà cung cấp định danh hoạt động, đã có cây tổ chức, không còn lệch mức Cao")
		})
		if err != nil {
			s.log().WarnContext(ctx, "kiểm tra sẵn sàng tenant lỗi", "tenant_id", id.String(), "err", err.Error())
		}
	}
	return n, nil
}

// RunPromoter chạy PromoteReady định kỳ.
func (s *Saga) RunPromoter(ctx context.Context, every time.Duration, log *slog.Logger) {
	if every <= 0 {
		return
	}
	tk := time.NewTicker(every)
	defer tk.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tk.C:
			if n, err := s.PromoteReady(ctx); err != nil {
				log.WarnContext(ctx, "chuyển tenant sẵn sàng lỗi", "err", err.Error())
			} else if n > 0 {
				log.InfoContext(ctx, "đã chuyển tenant sang sẵn sàng", "count", n)
			}
		}
	}
}
