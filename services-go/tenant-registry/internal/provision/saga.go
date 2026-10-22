package provision

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/platform/dbx"
	"mission/services-go/tenant-registry/internal/platform/outbox"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

// Step là một bước con của saga cấp phát. Do và Undo phải idempotent: saga có thể chạy lại một bước sau khi
// tiến trình khởi động lại (TL-01: "Mỗi bước idempotent, nên tiến trình có thể chạy lại từ bước thất bại").
// out là đầu ra bền của bước (chỉ id và tham chiếu), được lưu sau mỗi lần thử, kể cả lần lỗi.
type Step interface {
	Name() string
	Label() string // nhãn tiếng Việt cho MSG-01-07 "Cấp phát lỗi ở bước {bước}"
	Do(ctx context.Context, sc *Run, out map[string]string) error
	Undo(ctx context.Context, sc *Run, out map[string]string) error
}

// Saga chạy saga cấp phát và kích hoạt tenant.
type Saga struct {
	Pool      *pgxpool.Pool
	Tenants   *tenant.Service
	Log       *slog.Logger
	Endpoints EndpointResolver
	Keycloak  Keycloak
	DNS       DNS
	Certs     Certificates
	Core      Core
	Org       Org
	Lag       ReplicaLag // nil: không đo được độ trễ bản sao (chỉ cảnh báo)

	// PreSteps là các bước phần 1 (DV-03-04: CSDL hoặc schema, đăng ký pool, KEK) chạy trước phần 2.
	PreSteps []Step
	// MaxAttempts: số lần thử mỗi bước (TL-02a: tối đa 3 lần với thời gian chờ tăng dần).
	MaxAttempts int
	// Backoff: thời gian chờ trước lần thử thứ hai; nhân đôi mỗi lần.
	Backoff time.Duration
	// StepTimeout giới hạn một lần thử của một bước.
	StepTimeout time.Duration
	// RPO: độ trễ bản sao tối đa cho phép khi kích hoạt (QT-01: 15 phút).
	RPO time.Duration
	Now func() time.Time

	wg sync.WaitGroup
}

// Run là ngữ cảnh một lần chạy saga cho một tenant.
type Run struct {
	s          *Saga
	Tenant     *store.Tenant
	Cell       *store.Cell
	Binding    *store.TenantCell
	Onboarding *store.Onboarding
	Domains    []store.Domain
	Idps       []store.Idp
	EP         CellEndpoints
	Actor      tenant.Actor
	state      *tenant.ProvisionState
}

// Out trả đầu ra đã lưu của một bước khác (ví dụ id Organization cho bước kiểm thử khói).
func (r *Run) Out(step string) map[string]string {
	for _, st := range r.state.Steps {
		if st.Name == step {
			return st.Out
		}
	}
	return map[string]string{}
}

// Steps trả danh sách bước theo thứ tự chạy.
func (s *Saga) Steps() []Step {
	return append(append([]Step{}, s.PreSteps...), defaultSteps()...)
}

func (s *Saga) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Saga) log() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

// Start chạy saga của tác vụ jobID ngoài vòng đời request.
func (s *Saga) Start(ctx context.Context, jobID uuid.UUID) {
	ctx = context.WithoutCancel(ctx)
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		if err := s.Execute(ctx, jobID); err != nil {
			s.log().ErrorContext(ctx, "saga cấp phát lỗi", "job", jobID.String(), "err", err.Error())
		}
	}()
}

// Wait chờ các saga đang chạy (tắt mềm, kiểm thử).
func (s *Saga) Wait() { s.wg.Wait() }

// ResumePending tiếp tục các saga còn dở khi khởi động lại.
func (s *Saga) ResumePending(ctx context.Context) error {
	ids, err := store.PendingJobs(ctx, s.Pool, store.JobTypeProvision)
	if err != nil {
		return err
	}
	for _, id := range ids {
		s.Start(ctx, id)
	}
	return nil
}

// Execute chạy (hoặc chạy tiếp) saga của tác vụ PROVISION; chỉ một tiến trình chạy một tenant tại một thời
// điểm nhờ khoá tư vấn mức phiên.
func (s *Saga) Execute(ctx context.Context, jobID uuid.UUID) error {
	job, err := store.GetJob(ctx, s.Pool, jobID)
	if err != nil {
		return err
	}
	if job.Type != store.JobTypeProvision || job.Status == "HOAN_TAT" || job.Status == "THAT_BAI" {
		return nil
	}
	var st tenant.ProvisionState
	if err := json.Unmarshal(job.Params, &st); err != nil {
		return fmt.Errorf("provision: trạng thái saga hỏng: %w", err)
	}
	conn, err := s.Pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	var locked bool
	if err := conn.QueryRow(ctx, `SELECT pg_try_advisory_lock(hashtext('registry.provision'), hashtext($1))`, st.TenantID.String()).Scan(&locked); err != nil {
		return err
	}
	if !locked {
		return nil // tiến trình khác đang chạy saga này
	}
	defer func() {
		_, _ = conn.Exec(context.WithoutCancel(ctx), `SELECT pg_advisory_unlock(hashtext('registry.provision'), hashtext($1))`, st.TenantID.String())
	}()
	// Đọc lại sau khi giữ khoá (tiến trình khác có thể vừa chạy xong).
	if job, err = store.GetJob(ctx, s.Pool, jobID); err != nil {
		return err
	}
	if job.Status == "HOAN_TAT" || job.Status == "THAT_BAI" {
		return nil
	}
	if err := json.Unmarshal(job.Params, &st); err != nil {
		return err
	}
	run, err := s.load(ctx, &st, job.CreatedBy)
	if err != nil {
		return s.abort(ctx, job, &st, "Không đọc được cấu hình tenant", err)
	}
	if run.Tenant.Status != tenant.DangCapPhat {
		return s.abort(ctx, job, &st, "Tenant không ở trạng thái đang cấp phát", nil)
	}
	steps := s.Steps()
	s.ensureStates(&st, steps)
	if st.Phase == tenant.PhaseCompensate {
		return s.compensate(ctx, job, run, steps)
	}
	for i, step := range steps {
		ss := &st.Steps[i]
		if ss.Status == tenant.StepDone {
			continue
		}
		msg := fmt.Sprintf("Đang thực hiện bước %d/%d: %s", i+1, len(steps), step.Label())
		job.Status, job.Progress = "DANG_CHAY", progress(i, len(steps))
		if err := s.save(ctx, job, &st, msg); err != nil {
			return err
		}
		if err := s.attempt(ctx, job, run, step, ss); err != nil {
			st.Phase, st.FailedStep, st.Reason = tenant.PhaseCompensate, step.Name(), reasonOf(err)
			ss.Status = tenant.StepFailed
			s.log().WarnContext(ctx, "bước cấp phát lỗi, bắt đầu bù trừ", "job", job.ID.String(), "tenant_id", st.TenantID.String(),
				"step", step.Name(), "err", err.Error())
			if err := s.save(ctx, job, &st, "Đang hoàn tác các bước đã thực hiện"); err != nil {
				return err
			}
			return s.compensate(ctx, job, run, steps)
		}
		ss.Status = tenant.StepDone
		if err := s.save(ctx, job, &st, msg); err != nil {
			return err
		}
	}
	return s.finish(ctx, job, run)
}

// attempt chạy một bước, thử lại tối đa MaxAttempts lần với thời gian chờ tăng dần.
func (s *Saga) attempt(ctx context.Context, job *store.Job, run *Run, step Step, ss *tenant.StepState) error {
	maxA := s.attempts()
	var last error
	for ss.Attempts < maxA {
		ss.Attempts++
		if ss.Out == nil {
			ss.Out = map[string]string{}
		}
		sctx, cancel := s.stepCtx(ctx)
		err := step.Do(sctx, run, ss.Out)
		cancel()
		if err == nil {
			ss.Error = ""
			return nil
		}
		last = err
		ss.Error = reasonOf(err)
		_ = s.save(ctx, job, run.state, fmt.Sprintf("Bước %s lỗi lần %d, đang thử lại", step.Label(), ss.Attempts))
		if !retryable(err) || ss.Attempts >= maxA {
			break
		}
		wait := s.Backoff
		if wait <= 0 {
			wait = 2 * time.Second
		}
		wait = time.Duration(float64(wait) * math.Pow(2, float64(ss.Attempts-1)))
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(wait):
		}
	}
	if last == nil {
		last = errors.New("provision: đã hết số lần thử")
	}
	return last
}

func (s *Saga) attempts() int {
	if s.MaxAttempts <= 0 {
		return 3
	}
	return s.MaxAttempts
}

func (s *Saga) stepCtx(ctx context.Context) (context.Context, context.CancelFunc) {
	if s.StepTimeout > 0 {
		return context.WithTimeout(ctx, s.StepTimeout)
	}
	return context.WithTimeout(ctx, 10*time.Minute)
}

// compensate chạy bù trừ theo thứ tự ngược cho mọi bước đã xong hoặc đã lỗi (bước lỗi có thể đã tạo một
// phần tài nguyên), rồi đưa tenant về KHOI_TAO và ghi lý do (MSG-01-07).
func (s *Saga) compensate(ctx context.Context, job *store.Job, run *Run, steps []Step) error {
	st := run.state
	for i := len(steps) - 1; i >= 0; i-- {
		ss := &st.Steps[i]
		if ss.Status != tenant.StepDone && ss.Status != tenant.StepFailed {
			continue
		}
		var err error
		for a := 1; a <= s.attempts(); a++ {
			if ss.Out == nil {
				ss.Out = map[string]string{}
			}
			sctx, cancel := s.stepCtx(ctx)
			err = steps[i].Undo(sctx, run, ss.Out)
			cancel()
			if err == nil {
				break
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(s.Backoff):
			}
		}
		if err != nil {
			st.Phase = tenant.PhaseCompensateKO
			msg := fmt.Sprintf("Hoàn tác bước %s chưa thành công. Cần xử lý theo hướng dẫn vận hành.", steps[i].Label())
			s.log().ErrorContext(ctx, "bù trừ saga cấp phát lỗi", "job", job.ID.String(), "tenant_id", st.TenantID.String(),
				"step", steps[i].Name(), "err", err.Error())
			job.Status, job.Problem = "THAT_BAI", problemJSON(500, "provision-compensation-failed", "Hoàn tác cấp phát chưa thành công",
				"CONFIG-500-PROVISION-COMPENSATION", "", msg)
			return s.save(ctx, job, st, msg)
		}
		ss.Status = tenant.StepCompensated
		if err := s.save(ctx, job, st, "Đang hoàn tác các bước đã thực hiện"); err != nil {
			return err
		}
	}
	label := st.FailedStep
	for _, x := range steps {
		if x.Name() == st.FailedStep {
			label = x.Label()
		}
	}
	msg := truncate(fmt.Sprintf("Cấp phát lỗi ở bước %s: %s. Hệ thống đã hoàn tác các bước trước.", label, strings.TrimSuffix(st.Reason, ".")), 2000)
	return dbx.InTx(ctx, s.Pool, func(tx pgx.Tx) error {
		t, err := store.GetTenantForUpdate(ctx, tx, st.TenantID)
		if err != nil {
			return err
		}
		if t.Status == tenant.DangCapPhat {
			if err := s.Tenants.Transition(ctx, tx, run.Actor, t, t.RowVersion, tenant.KhoiTao, msg); err != nil {
				return err
			}
		}
		now := s.now().UTC()
		st.Phase, st.FinishedAt = tenant.PhaseCompensated, &now
		code, status, slug, msgCode := "CONFIG-502-PROVISION-FAILED", 502, "msg-01-07", "MSG-01-07"
		if strings.HasPrefix(st.Reason, "Gói chính sách") {
			code, status = "CONFIG-422-POLICY-PACK-NOT-READY", 422
		}
		job.Status, job.Progress = "THAT_BAI", 100
		job.Problem = problemJSON(status, slug, "Cấp phát tenant chưa thành công", code, msgCode, msg)
		return s.save(ctx, job, st, msg, tx)
	})
}

// finish ghi kết quả thành công: DANG_CAP_PHAT → DANG_CAU_HINH (MSG-01-06), sys.tenant.provisioned.v1 và
// EVT-21 thông báo đầu mối quản trị tenant qua outbox trong cùng giao dịch.
func (s *Saga) finish(ctx context.Context, job *store.Job, run *Run) error {
	st := run.state
	return dbx.InTx(ctx, s.Pool, func(tx pgx.Tx) error {
		t, err := store.GetTenantForUpdate(ctx, tx, st.TenantID)
		if err != nil {
			return err
		}
		if t.Status != tenant.DangCapPhat {
			return fmt.Errorf("provision: tenant đã đổi trạng thái %s", t.Status)
		}
		now := s.now().UTC()
		minutes := int(math.Ceil(now.Sub(st.StartedAt).Minutes()))
		if minutes < 1 {
			minutes = 1
		}
		msg := fmt.Sprintf("Cấp phát tenant thành công trong %d phút. Đã gửi thư cho đầu mối quản trị tenant.", minutes)
		if err := s.Tenants.Transition(ctx, tx, run.Actor, t, t.RowVersion, tenant.DangCauHinh, msg); err != nil {
			return err
		}
		if _, err := outbox.Append(ctx, tx, outbox.Envelope{TenantID: t.ID, AggregateType: "tenant", AggregateID: t.ID,
			AggregateVersion: int64(t.RowVersion), EventType: "TenantProvisioned", Topic: outbox.TopicTenantProvisioned,
			Payload: map[string]any{"tenantCode": t.Code, "cellCode": run.Binding.CellCode, "tenancyModel": t.TenancyModel,
				"databaseName": run.Binding.DatabaseName, "provisionedAt": now.UnixMicro()},
			TraceParent: run.Actor.TraceParent, ActorID: run.Actor.ID, Classification: "C1"}); err != nil {
			return err
		}
		if err := notify(ctx, tx, run, t, "TENANT_PROVISIONED", map[string]any{"tenantName": t.Name, "minutes": minutes,
			"domain": primaryDomain(run.Domains)}); err != nil {
			return err
		}
		st.Phase, st.FinishedAt = tenant.PhaseDone, &now
		job.Status, job.Progress, job.Problem = "HOAN_TAT", 100, nil
		return s.save(ctx, job, st, msg, tx)
	})
}

// notify ghi EVT-21 (notify.message.requested.v1) cho tài khoản đầu mối quản trị tenant; params không chứa C2.
func notify(ctx context.Context, tx pgx.Tx, run *Run, t *store.Tenant, template string, params map[string]any) error {
	uid := run.Out(StepOrganization)["adminUserId"]
	if uid == "" {
		return nil
	}
	p, _ := json.Marshal(params)
	_, err := outbox.Append(ctx, tx, outbox.Envelope{TenantID: t.ID, AggregateType: "tenant", AggregateID: t.ID,
		AggregateVersion: int64(t.RowVersion), EventType: "YeuCauThongBao", Topic: outbox.TopicNotifyRequested,
		EventKey: t.ID.String() + ":" + uid,
		Payload: map[string]any{"recipientIds": []string{uid}, "templateCode": template, "params": string(p),
			"deepLink": "/quan-tri/tenant/" + t.ID.String(), "priority": "CAO", "aggregateVersion": t.RowVersion},
		TraceParent: run.Actor.TraceParent, ActorID: run.Actor.ID, Classification: "C1"})
	return err
}

// abort kết thúc tác vụ khi không chạy được (không bù trừ vì chưa làm gì).
func (s *Saga) abort(ctx context.Context, job *store.Job, st *tenant.ProvisionState, msg string, err error) error {
	if err != nil {
		s.log().ErrorContext(ctx, "saga cấp phát dừng", "job", job.ID.String(), "err", err.Error())
	}
	job.Status, job.Problem = "THAT_BAI", problemJSON(409, "tenant-state", msg, "CONFIG-409-TENANT-STATE", "", msg)
	return s.save(ctx, job, st, msg)
}

func (s *Saga) ensureStates(st *tenant.ProvisionState, steps []Step) {
	have := map[string]tenant.StepState{}
	for _, x := range st.Steps {
		have[x.Name] = x
	}
	out := make([]tenant.StepState, 0, len(steps))
	for _, x := range steps {
		if v, ok := have[x.Name()]; ok {
			out = append(out, v)
			continue
		}
		out = append(out, tenant.StepState{Name: x.Name(), Status: tenant.StepPending, Out: map[string]string{}})
	}
	st.Steps = out
	if st.Phase == "" {
		st.Phase = tenant.PhaseRun
	}
}

// save ghi trạng thái saga vào registry.job (qua tx nếu có).
func (s *Saga) save(ctx context.Context, job *store.Job, st *tenant.ProvisionState, msg string, tx ...pgx.Tx) error {
	b, err := json.Marshal(st)
	if err != nil {
		return err
	}
	job.Params, job.Message = b, &msg
	var q store.Querier = s.Pool
	if len(tx) > 0 {
		q = tx[0]
	}
	return store.UpdateJobState(context.WithoutCancel(ctx), q, job)
}

// load đọc cấu hình tenant cho một lần chạy.
func (s *Saga) load(ctx context.Context, st *tenant.ProvisionState, actor uuid.UUID) (*Run, error) {
	t, err := store.GetTenant(ctx, s.Pool, st.TenantID)
	if err != nil {
		return nil, err
	}
	b, err := store.LatestTenantCell(ctx, s.Pool, t.ID)
	if err != nil {
		return nil, err
	}
	c, err := store.GetCell(ctx, s.Pool, b.CellID)
	if err != nil {
		return nil, err
	}
	o, err := store.GetOnboarding(ctx, s.Pool, t.ID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	ds, err := store.ListDomains(ctx, s.Pool, t.ID)
	if err != nil {
		return nil, err
	}
	idps, err := store.ListIdps(ctx, s.Pool, t.ID, true)
	if err != nil {
		return nil, err
	}
	ep, err := s.Endpoints.Endpoints(c.Code, c.APIBaseURL)
	if err != nil {
		return nil, err
	}
	return &Run{s: s, Tenant: t, Cell: c, Binding: b, Onboarding: o, Domains: ds, Idps: idps, EP: ep,
		Actor: tenant.Actor{ID: actor, PlatformAdmin: true}, state: st}, nil
}

func progress(i, n int) int {
	if n == 0 {
		return 0
	}
	return 5 + i*90/n
}

// reasonOf trả lý do hiển thị cho người dùng (không lộ chi tiết kỹ thuật).
func reasonOf(err error) string {
	var pn *PackNotReadyError
	if errors.As(err, &pn) {
		return pn.Error()
	}
	var se *StepError
	if errors.As(err, &se) && se.Reason != "" {
		return se.Reason
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "Hệ thống liên quan phản hồi quá thời gian chờ"
	}
	return "Hệ thống liên quan chưa sẵn sàng"
}

func problemJSON(status int, slug, title, code, msgCode, detail string) json.RawMessage {
	p := map[string]any{"type": "https://okrkpi.vn/problems/" + slug, "title": title, "status": status, "code": code, "detail": detail}
	if msgCode != "" {
		p["msg"] = msgCode
	}
	b, _ := json.Marshal(p)
	return b
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

func primaryDomain(ds []store.Domain) string {
	for _, d := range ds {
		if d.Kind == "PRIMARY" {
			return d.Domain
		}
	}
	return ""
}
