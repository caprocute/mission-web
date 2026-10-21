package move

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/platform/dbx"
	"mission/services-go/tenant-registry/internal/platform/outbox"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

// Runner chạy saga chuyển cell. Sau cắt chuyển (SWITCH_ACTIVE_CELL) chỉ đi tiếp, không quay lui.
type Runner struct {
	Pool    *pgxpool.Pool
	Tenants *tenant.Service
	DB      *PGCells
	Objects ObjectSync // nil: không có kho đối tượng (môi trường không có Ceph)
	Ledger  Ledger     // nil: chưa có ledger-service
	Log     *slog.Logger

	PollEvery      time.Duration // mặc định 1 giây
	CatchUpTimeout time.Duration // mặc định 6 giờ
	FreezeTimeout  time.Duration // mặc định 15 phút (ADR-29: chỉ đọc 5–15 phút)
	LagThreshold   time.Duration // ADR-29: độ trễ < 1 giây mới khoá ghi
	Retention      time.Duration // giữ dữ liệu nguồn 30 ngày
	MaxAttempts    int
	Now            func() time.Time

	wg sync.WaitGroup
}

func (r *Runner) now() time.Time {
	if r.Now != nil {
		return r.Now().UTC()
	}
	return time.Now().UTC()
}

func (r *Runner) log() *slog.Logger {
	if r.Log != nil {
		return r.Log
	}
	return slog.Default()
}

func dur(d, def time.Duration) time.Duration {
	if d > 0 {
		return d
	}
	return def
}

// Start chạy saga nền.
func (r *Runner) Start(ctx context.Context, moveID uuid.UUID) {
	ctx = context.WithoutCancel(ctx)
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		if err := r.Execute(ctx, moveID); err != nil {
			r.log().ErrorContext(ctx, "saga chuyển cell lỗi", "move", moveID.String(), "err", err.Error())
		}
	}()
}

// Wait chờ saga nền.
func (r *Runner) Wait() { r.wg.Wait() }

// ResumePending chạy tiếp các lệnh DANG_CHUYEN khi khởi động lại.
func (r *Runner) ResumePending(ctx context.Context) error {
	rows, err := r.Pool.Query(ctx, `SELECT id FROM registry.job WHERE job_type = $1 AND params->>'status' = $2`, JobType, DangChuyen)
	if err != nil {
		return err
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
	if err != nil {
		return err
	}
	for _, id := range ids {
		r.Start(ctx, id)
	}
	return nil
}

type run struct {
	r    *Runner
	job  *store.Job
	st   *State
	src  Loc
	dst  Loc
	name string
	a    tenant.Actor
}

// save ghi trạng thái saga, giữ các trường do request huỷ ghi đồng thời (cờ huỷ, rowVersion).
func (x *run) save(ctx context.Context, msg string) error {
	ctx = context.WithoutCancel(ctx)
	return dbx.InTx(ctx, x.r.Pool, func(tx pgx.Tx) error {
		return x.saveTx(ctx, tx, msg)
	})
}

func (x *run) saveTx(ctx context.Context, tx pgx.Tx, msg string) error {
	j, err := store.GetJobForUpdate(ctx, tx, x.job.ID)
	if err != nil {
		return err
	}
	var db State
	if err := json.Unmarshal(j.Params, &db); err != nil {
		return err
	}
	if db.CancelRequestedBy != nil {
		x.st.CancelRequestedBy = db.CancelRequestedBy
	}
	if db.CancelConfirmedBy != nil {
		x.st.CancelConfirmedBy = db.CancelConfirmedBy
	}
	if db.RowVersion > x.st.RowVersion {
		x.st.RowVersion = db.RowVersion
	}
	return save(ctx, tx, x.job, x.st, msg)
}

func (x *run) stepState(name string) *tenant.StepState {
	for i := range x.st.Steps {
		if x.st.Steps[i].Name == name {
			return &x.st.Steps[i]
		}
	}
	x.st.Steps = append(x.st.Steps, tenant.StepState{Name: name, Status: tenant.StepPending, Out: map[string]string{}})
	return &x.st.Steps[len(x.st.Steps)-1]
}

// errCancelled: người dùng đã xác nhận huỷ.
var errCancelled = errors.New("move: đã xác nhận huỷ")

// refresh đọc lại cờ huỷ từ CSDL (lệnh huỷ ghi từ request khác).
func (x *run) refresh(ctx context.Context) error {
	j, err := store.GetJob(ctx, x.r.Pool, x.job.ID)
	if err != nil {
		return err
	}
	var st State
	if err := json.Unmarshal(j.Params, &st); err != nil {
		return err
	}
	x.st.CancelRequestedBy, x.st.CancelConfirmedBy, x.st.RowVersion = st.CancelRequestedBy, st.CancelConfirmedBy, st.RowVersion
	if st.Failure != "" {
		x.st.Failure = st.Failure
	}
	if x.st.CancelConfirmedBy != nil && x.st.SwitchedAt == nil {
		return errCancelled
	}
	return nil
}

// Execute chạy (hoặc chạy tiếp) saga của lệnh chuyển; khoá tư vấn bảo đảm một tiến trình mỗi lệnh.
func (r *Runner) Execute(ctx context.Context, moveID uuid.UUID) error {
	conn, err := r.Pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	var locked bool
	if err := conn.QueryRow(ctx, `SELECT pg_try_advisory_lock(hashtext('registry.move'), hashtext($1))`, moveID.String()).Scan(&locked); err != nil {
		return err
	}
	if !locked {
		return nil
	}
	defer func() {
		_, _ = conn.Exec(context.WithoutCancel(ctx), `SELECT pg_advisory_unlock(hashtext('registry.move'), hashtext($1))`, moveID.String())
	}()
	j, err := store.GetJob(ctx, r.Pool, moveID)
	if err != nil {
		return err
	}
	var st State
	if err := json.Unmarshal(j.Params, &st); err != nil {
		return err
	}
	if j.Type != JobType || st.Status != DangChuyen {
		return nil
	}
	x := &run{r: r, job: j, st: &st, src: Loc{Cell: st.FromCell, DB: st.FromDB}, dst: Loc{Cell: st.ToCell, DB: st.ToDB}, name: Name(moveID)}
	if st.ApprovedBy != nil {
		x.a = tenant.Actor{ID: *st.ApprovedBy, PlatformAdmin: true}
	}
	x.job.Status = "DANG_CHAY"
	if st.CancelConfirmedBy != nil && st.SwitchedAt == nil {
		return x.rollback(ctx, DaHuy, "Đã huỷ theo đề nghị")
	}
	type stepFn func(context.Context) error
	fns := map[string]stepFn{StepProvisionTarget: x.provisionTarget, StepSnapshot: x.snapshot, StepCatchUp: x.catchUp,
		StepFreeze: x.freeze, StepSwitch: x.switchCell, StepReopen: x.reopen}
	for i, name := range Steps {
		ss := x.stepState(name)
		if ss.Status == tenant.StepDone {
			continue
		}
		cur := name
		st.CurrentStep = &cur
		j.Progress = i * 100 / len(Steps)
		if err := x.save(ctx, fmt.Sprintf("Đang thực hiện bước %d/%d", i+1, len(Steps))); err != nil {
			return err
		}
		if st.SwitchedAt == nil {
			if err := x.refresh(ctx); errors.Is(err, errCancelled) {
				return x.rollback(ctx, DaHuy, "Đã huỷ theo đề nghị")
			}
		}
		var err error
		for a := 1; a <= max(1, r.MaxAttempts); a++ {
			ss.Attempts++
			if err = fns[name](ctx); err == nil || errors.Is(err, errCancelled) || isPermanent(err) {
				break
			}
			ss.Error = err.Error()
			_ = x.save(ctx, "Bước "+name+" lỗi, đang thử lại")
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(dur(r.PollEvery, time.Second) * time.Duration(a)):
			}
		}
		if errors.Is(err, errCancelled) {
			return x.rollback(ctx, DaHuy, "Đã huỷ theo đề nghị")
		}
		if err != nil {
			ss.Status, ss.Error = tenant.StepFailed, err.Error()
			r.log().ErrorContext(ctx, "bước chuyển cell lỗi", "move", moveID.String(), "tenant_id", st.TenantID.String(), "step", name, "err", err.Error())
			if st.SwitchedAt != nil {
				// Đã cắt chuyển: không quay lui, giữ DANG_CHUYEN để vận hành xử lý rồi chạy tiếp (runbook).
				return x.save(ctx, "Bước "+name+" lỗi sau khi cắt chuyển. Cần xử lý theo hướng dẫn vận hành.")
			}
			return x.rollback(ctx, ThatBai, "Chuyển cell lỗi ở bước "+name+": "+userReason(err))
		}
		ss.Status, ss.Error = tenant.StepDone, ""
		if err := x.save(ctx, ""); err != nil {
			return err
		}
	}
	return nil
}

// permanentError: lỗi không nên thử lại (cấu trúc CSDL đích sai, so khớp lệch).
type permanentError struct{ error }

func isPermanent(err error) bool {
	var p permanentError
	var se *SchemaError
	return errors.As(err, &p) || errors.As(err, &se)
}

func userReason(err error) string {
	var se *SchemaError
	if errors.As(err, &se) {
		return se.Error()
	}
	var p permanentError
	if errors.As(err, &p) {
		return p.Error()
	}
	return "hệ thống liên quan chưa sẵn sàng"
}

// ---------------------------------------------------------------- các bước

// provisionTarget: liệt kê bảng có tenant_id ở nguồn, kiểm cấu trúc đích, tạo phân vùng của tenant ở đích, bảo
// đảm replica identity chứa tenant_id ở nguồn.
func (x *run) provisionTarget(ctx context.Context) error {
	db := x.r.DB
	tables, err := db.Tables(ctx, x.src)
	if err != nil {
		return err
	}
	x.st.Tables = tables
	created, err := db.PrepareTarget(ctx, x.src, x.dst, x.st.TenantID, tables)
	x.st.CreatedPartitions = appendUnique(x.st.CreatedPartitions, created...)
	if err != nil {
		return err
	}
	changed, err := db.EnsureReplicaIdentity(ctx, x.src, tables)
	x.st.ReplicaIdentity = appendUnique(x.st.ReplicaIdentity, changed...)
	return err
}

func appendUnique(a []string, b ...string) []string {
	for _, v := range b {
		found := false
		for _, w := range a {
			if w == v {
				found = true
			}
		}
		if !found {
			a = append(a, v)
		}
	}
	return a
}

// snapshot: publication có bộ lọc dòng ở nguồn, subscription ở đích (sao chép ban đầu), đồng bộ đối tượng lần đầu.
func (x *run) snapshot(ctx context.Context) error {
	if err := x.r.DB.Publish(ctx, x.src, x.name, x.st.TenantID, x.st.Tables); err != nil {
		return err
	}
	if err := x.r.DB.Subscribe(ctx, x.src, x.dst, x.name); err != nil {
		return err
	}
	if x.r.Objects != nil {
		if _, err := x.r.Objects.Sync(ctx, x.st.FromCell, x.st.ToCell, x.st.TenantID); err != nil {
			return err
		}
	}
	return nil
}

// catchUp: chờ mọi bảng đồng bộ xong và độ trễ dưới ngưỡng (ADR-29: < 1 giây).
func (x *run) catchUp(ctx context.Context) error {
	deadline := x.r.now().Add(dur(x.r.CatchUpTimeout, 6*time.Hour))
	threshold := dur(x.r.LagThreshold, time.Second).Seconds()
	for {
		pr, err := x.r.DB.CatchUp(ctx, x.src, x.dst, x.name)
		if err != nil {
			return err
		}
		if pr.TablesTotal > 0 && pr.TablesReady == pr.TablesTotal && pr.Streaming && pr.LagSeconds < threshold {
			return nil
		}
		if x.r.now().After(deadline) {
			return fmt.Errorf("move: chưa bắt kịp sau thời gian chờ (%d/%d bảng, trễ %.1f giây)", pr.TablesReady, pr.TablesTotal, pr.LagSeconds)
		}
		if err := x.wait(ctx); err != nil {
			return err
		}
	}
}

// wait chờ một nhịp, kiểm cờ huỷ.
func (x *run) wait(ctx context.Context) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(dur(x.r.PollEvery, time.Second)):
	}
	return x.refresh(ctx)
}

// freeze: (đợi tới plannedAt) khoá ghi tenant (sự kiện cho cổng, rào chặn ở CSDL nguồn), chờ đích nhận đủ WAL,
// so khớp số dòng và tổng băm từng bảng, đồng bộ và so khớp đối tượng.
func (x *run) freeze(ctx context.Context) error {
	for x.st.PlannedAt != nil && x.r.now().Before(*x.st.PlannedAt) && x.st.ReadOnlyFrom == nil {
		if err := x.wait(ctx); err != nil {
			return err
		}
	}
	if !x.st.WriteLocked {
		if err := x.signal(ctx, true); err != nil {
			return err
		}
	}
	if err := x.r.DB.Fence(ctx, x.src, x.name, x.st.TenantID, x.st.Tables); err != nil {
		return err
	}
	lsn, err := x.r.DB.CurrentLSN(ctx, x.src)
	if err != nil {
		return err
	}
	deadline := x.r.now().Add(dur(x.r.FreezeTimeout, 15*time.Minute))
	for {
		ok, err := x.r.DB.Flushed(ctx, x.src, x.name, lsn)
		if err != nil {
			return err
		}
		if ok {
			break
		}
		if x.r.now().After(deadline) {
			return permanentError{errors.New("cell đích chưa nhận đủ dữ liệu trong thời gian khoá ghi")}
		}
		if err := x.wait(ctx); err != nil {
			return err
		}
	}
	// Áp dụng ở đích có thể chậm hơn xác nhận vài nhịp: so khớp lại tới khi khớp hoặc hết hạn.
	for {
		checks, ok, err := x.verify(ctx)
		if err != nil {
			return err
		}
		x.st.Verification = checks
		if ok {
			break
		}
		if x.r.now().After(deadline) {
			return permanentError{errors.New("số dòng hoặc tổng băm giữa hai cell không khớp")}
		}
		if err := x.wait(ctx); err != nil {
			return err
		}
	}
	if x.r.Objects != nil {
		if _, err := x.r.Objects.Sync(ctx, x.st.FromCell, x.st.ToCell, x.st.TenantID); err != nil {
			return err
		}
		rep, err := x.r.Objects.Verify(ctx, x.st.FromCell, x.st.ToCell, x.st.TenantID)
		if err != nil {
			return err
		}
		x.st.Objects = &rep
		if !rep.OK {
			return permanentError{errors.New("tệp minh chứng giữa hai cell chưa khớp")}
		}
	}
	return nil
}

func (x *run) verify(ctx context.Context) ([]TableCheck, bool, error) {
	s, err := x.r.DB.Checksums(ctx, x.src, x.st.TenantID, x.st.Tables)
	if err != nil {
		return nil, false, err
	}
	d, err := x.r.DB.Checksums(ctx, x.dst, x.st.TenantID, x.st.Tables)
	if err != nil {
		return nil, false, err
	}
	all := true
	var out []TableCheck
	for _, t := range x.st.Tables {
		a, b := s[t.Key()], d[t.Key()]
		ok := a.Rows == b.Rows && sumEqual(a.Sum, b.Sum)
		all = all && ok
		out = append(out, TableCheck{Table: t.Key(), SourceRows: a.Rows, TargetRows: b.Rows, SourceSum: a.Sum, TargetSum: b.Sum, OK: ok})
	}
	return out, all, nil
}

func sumEqual(a, b string) bool {
	x, ok1 := new(big.Int).SetString(a, 10)
	y, ok2 := new(big.Int).SetString(b, 10)
	return ok1 && ok2 && x.Cmp(y) == 0
}

// signal ghi sự kiện khoá/mở ghi (registry.tenant.changed.v1, cổng APISIX trả 503 + Retry-After khi khoá).
func (x *run) signal(ctx context.Context, locked bool) error {
	return dbx.InTx(ctx, x.r.Pool, func(tx pgx.Tx) error {
		t, err := store.GetTenant(ctx, tx, x.st.TenantID)
		if err != nil {
			return err
		}
		kind := "TENANT_WRITE_UNLOCKED"
		if locked {
			kind = "TENANT_WRITE_LOCKED"
			now := x.r.now()
			x.st.ReadOnlyFrom = &now
		}
		if err := x.publish(ctx, tx, t, kind, map[string]any{"writeLocked": locked, "retryAfterSeconds": 60}); err != nil {
			return err
		}
		x.st.WriteLocked = locked
		return x.saveTx(ctx, tx, "")
	})
}

func (x *run) publish(ctx context.Context, tx pgx.Tx, t *store.Tenant, kind string, extra map[string]any) error {
	payload := map[string]any{"tenantId": t.ID, "tenantCode": t.Code, "status": t.Status, "tenancyModel": t.TenancyModel,
		"sourceVersion": t.RowVersion, "moveId": x.job.ID}
	for k, v := range extra {
		payload[k] = v
	}
	if _, err := outbox.Append(ctx, tx, outbox.Envelope{TenantID: t.ID, AggregateType: "tenant", AggregateID: t.ID,
		AggregateVersion: int64(t.RowVersion), EventType: kind, Topic: outbox.TopicTenantChanged, Payload: payload,
		ActorID: x.a.ID, Classification: "C1"}); err != nil {
		return err
	}
	return resolve.Notify(ctx, tx, t.ID)
}

// switchCell: chuyển giao sổ cái, đóng gắn cell cũ và mở gắn cell mới trong một giao dịch (ràng buộc
// ex_tenant_cell__no_overlap bảo đảm không chồng lấn), rồi gỡ sao chép và khôi phục replica identity ở nguồn.
func (x *run) switchCell(ctx context.Context) error {
	if x.st.SwitchedAt == nil {
		if x.r.Ledger != nil && x.st.Ledger == nil {
			h, err := x.r.Ledger.Seal(ctx, x.st.FromCell, x.st.TenantID, x.job.ID, x.st.ToCell)
			if err != nil {
				return err
			}
			x.st.Ledger = h
			if err := x.save(ctx, ""); err != nil {
				return err
			}
		}
		if x.r.Ledger != nil {
			if err := x.r.Ledger.Accept(ctx, x.st.ToCell, x.st.TenantID, x.job.ID, x.st.FromCell, x.st.Ledger); err != nil {
				return err
			}
		}
		err := dbx.InTx(ctx, x.r.Pool, func(tx pgx.Tx) error {
			t, err := store.GetTenantForUpdate(ctx, tx, x.st.TenantID)
			if err != nil {
				return err
			}
			now := x.r.now()
			cur, err := store.CurrentTenantCell(ctx, tx, t.ID, now)
			if err != nil {
				return err
			}
			if cur.CellID != x.st.FromCellID {
				return permanentError{errors.New("gắn cell hiện hành không phải cell nguồn")}
			}
			if !now.After(cur.ValidFrom) {
				now = cur.ValidFrom.Add(time.Microsecond)
			}
			if err := store.CloseTenantCell(ctx, tx, cur.ID, now, x.a.ID); err != nil {
				return err
			}
			if err := store.InsertTenantCell(ctx, tx, &store.TenantCell{TenantID: t.ID, CellID: x.st.ToCellID, DatabaseName: x.st.ToDB,
				ValidFrom: now, TenancyModel: tenant.Lower(x.st.TargetModel), DataRegion: x.st.DataRegion}, x.a.ID); err != nil {
				return err
			}
			if t.TenancyModel != x.st.TargetModel {
				t.TenancyModel, t.UpdatedBy = x.st.TargetModel, x.a.ID
				if err := store.UpdateTenant(ctx, tx, t, t.RowVersion); err != nil {
					return err
				}
			}
			if err := x.publish(ctx, tx, t, "TENANT_CELL_SWITCHED", map[string]any{"cellCode": x.st.ToCell, "databaseName": x.st.ToDB,
				"previousCellCode": x.st.FromCell, "writeLocked": true}); err != nil {
				return err
			}
			x.st.SwitchedAt = &now
			return x.saveTx(ctx, tx, "Đã chuyển danh bạ sang cell đích")
		})
		if err != nil {
			return err
		}
	}
	if err := x.r.DB.DropSubscription(ctx, x.dst, x.name); err != nil {
		return err
	}
	if err := x.r.DB.DropPublication(ctx, x.src, x.name); err != nil {
		return err
	}
	return x.r.DB.RestoreReplicaIdentity(ctx, x.src, x.st.ReplicaIdentity)
}

// reopen: mở ghi ở cell đích (DANG_CHUYEN_CELL → DANG_VAN_HANH), ghi thời gian chỉ đọc, hạn giữ dữ liệu nguồn.
func (x *run) reopen(ctx context.Context) error {
	return dbx.InTx(ctx, x.r.Pool, func(tx pgx.Tx) error {
		t, err := store.GetTenantForUpdate(ctx, tx, x.st.TenantID)
		if err != nil {
			return err
		}
		now := x.r.now()
		x.st.ReadOnlyTo = &now
		if x.st.ReadOnlyFrom != nil {
			x.st.ReadOnlySeconds = now.Sub(*x.st.ReadOnlyFrom).Seconds()
		}
		retain := now.Add(dur(x.r.Retention, 30*24*time.Hour))
		x.st.RetainUntil = &retain
		if t.Status == tenant.DangChuyenCell {
			reason := fmt.Sprintf("Hoàn tất chuyển từ %s sang %s, chỉ đọc %.0f giây", x.st.FromCell, x.st.ToCell, x.st.ReadOnlySeconds)
			if err := x.r.Tenants.Transition(ctx, tx, x.a, t, t.RowVersion, tenant.DangVanHanh, reason); err != nil {
				return err
			}
		}
		if err := x.publish(ctx, tx, t, "TENANT_WRITE_UNLOCKED", map[string]any{"writeLocked": false, "cellCode": x.st.ToCell}); err != nil {
			return err
		}
		x.st.WriteLocked = false
		for i := range x.st.Steps {
			if x.st.Steps[i].Name == StepReopen {
				x.st.Steps[i].Status = tenant.StepDone
			}
		}
		x.st.Status, x.st.CurrentStep = HoanTat, nil
		x.st.RowVersion++
		return x.saveTx(ctx, tx, fmt.Sprintf("Đã chuyển sang %s, thời gian chỉ đọc %.0f giây", x.st.ToCell, x.st.ReadOnlySeconds))
	})
}

// rollback quay lại trạng thái cũ trước cắt chuyển: gỡ sao chép, xoá dữ liệu đã chép ở đích, gỡ rào chặn và
// khôi phục replica identity ở nguồn, mở khoá sổ cái nguồn, tenant về DANG_VAN_HANH.
func (x *run) rollback(ctx context.Context, status, msg string) error {
	ctx = context.WithoutCancel(ctx)
	db := x.r.DB
	steps := []func() error{
		func() error { return db.DropSubscription(ctx, x.dst, x.name) },
		func() error { return db.DropPublication(ctx, x.src, x.name) },
		func() error { return db.Unfence(ctx, x.src, x.name, x.st.Tables) },
		func() error { return db.RestoreReplicaIdentity(ctx, x.src, x.st.ReplicaIdentity) },
		func() error { _, err := db.DeleteTenantRows(ctx, x.dst, x.st.TenantID, x.st.Tables); return err },
		func() error { return db.DropTables(ctx, x.dst, x.st.CreatedPartitions) },
		func() error {
			if x.r.Ledger == nil || x.st.Ledger == nil {
				return nil
			}
			return x.r.Ledger.Unseal(ctx, x.st.FromCell, x.st.TenantID, x.job.ID)
		},
		func() error {
			if x.r.Objects == nil {
				return nil
			}
			_, err := x.r.Objects.Purge(ctx, x.st.ToCell, x.st.TenantID)
			return err
		},
	}
	for _, f := range steps {
		if err := f(); err != nil {
			x.st.Failure = "Quay lui chưa xong: " + err.Error()
			_ = x.save(ctx, "Quay lui chưa xong. Cần xử lý theo hướng dẫn vận hành.")
			return err
		}
	}
	return dbx.InTx(ctx, x.r.Pool, func(tx pgx.Tx) error {
		t, err := store.GetTenantForUpdate(ctx, tx, x.st.TenantID)
		if err != nil {
			return err
		}
		if t.Status == tenant.DangChuyenCell {
			if err := x.r.Tenants.Transition(ctx, tx, x.a, t, t.RowVersion, tenant.DangVanHanh, truncate(msg, 2000)); err != nil {
				return err
			}
		}
		if x.st.WriteLocked {
			if err := x.publish(ctx, tx, t, "TENANT_WRITE_UNLOCKED", map[string]any{"writeLocked": false}); err != nil {
				return err
			}
			x.st.WriteLocked = false
			now := x.r.now()
			x.st.ReadOnlyTo = &now
		}
		if x.st.Failure == "" {
			x.st.Failure = msg
		}
		x.st.Status, x.st.CurrentStep = status, nil
		x.st.RowVersion++
		x.job.Problem = nil
		return x.saveTx(ctx, tx, msg)
	})
}

// ---------------------------------------------------------------- dọn dữ liệu nguồn sau 30 ngày

// PurgeExpired xoá dữ liệu tenant ở cell nguồn của các lần chuyển đã quá hạn giữ (TL-01: giữ 30 ngày rồi xoá
// theo quy trình có biên bản); biên bản (số dòng, thời điểm) ghi vào trạng thái lệnh chuyển.
func (r *Runner) PurgeExpired(ctx context.Context) (int, error) {
	rows, err := r.Pool.Query(ctx, `SELECT id FROM registry.job WHERE job_type = $1 AND params->>'status' = $2
		AND params->>'purgedAt' IS NULL AND (params->>'retainUntil')::timestamptz < $3`, JobType, HoanTat, r.now())
	if err != nil {
		return 0, err
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
	if err != nil {
		return 0, err
	}
	n := 0
	for _, id := range ids {
		j, err := store.GetJob(ctx, r.Pool, id)
		if err != nil {
			return n, err
		}
		var st State
		if err := json.Unmarshal(j.Params, &st); err != nil {
			return n, err
		}
		src := Loc{Cell: st.FromCell, DB: st.FromDB}
		deleted, err := r.DB.PurgeSource(ctx, src, Name(id), st.TenantID, st.Tables)
		if err != nil {
			return n, err
		}
		if r.Objects != nil {
			if _, err := r.Objects.Purge(ctx, st.FromCell, st.TenantID); err != nil {
				return n, err
			}
		}
		now := r.now()
		st.PurgedAt, st.PurgedRows = &now, deleted
		if err := save(ctx, r.Pool, j, &st, fmt.Sprintf("Đã xoá %d dòng dữ liệu ở cell nguồn %s sau thời hạn giữ", deleted, st.FromCell)); err != nil {
			return n, err
		}
		r.log().InfoContext(ctx, "đã dọn dữ liệu cell nguồn sau chuyển cell", "move", id.String(), "tenant_id", st.TenantID.String(),
			"cell", st.FromCell, "rows", deleted)
		n++
	}
	return n, nil
}
