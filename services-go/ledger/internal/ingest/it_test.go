package ingest_test

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/testdb"
)

var (
	db      *testdb.DB
	brokers []string
)

// TestMain dựng PostgreSQL 17 (migration ledger thật, vai trò svc_ledger) và Kafka KRaft.
func TestMain(m *testing.M) {
	if os.Getenv("LEDGER_SKIP_IT") != "" {
		os.Exit(m.Run())
	}
	ctx := context.Background()
	pg, err := tcpg.Run(ctx, "postgres:17",
		tcpg.WithDatabase("ledger"), tcpg.WithUsername("postgres"), tcpg.WithPassword(testdb.RandomSecret()),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(90*time.Second)))
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được PostgreSQL:", err)
		os.Exit(1)
	}
	dsn, _ := pg.ConnectionString(ctx, "sslmode=disable")
	if db, err = testdb.Bootstrap(ctx, dsn, true); err != nil {
		fmt.Fprintln(os.Stderr, "bootstrap ledger:", err)
		os.Exit(1)
	}
	kc, err := tckafka.Run(ctx, "confluentinc/confluent-local:7.5.0", tckafka.WithClusterID("ledger-it"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được Kafka:", err)
		os.Exit(1)
	}
	brokers, _ = kc.Brokers(ctx)
	code := m.Run()
	_ = kc.Terminate(ctx)
	_ = pg.Terminate(ctx)
	os.Exit(code)
}

func skipIT(t *testing.T) {
	if db == nil {
		t.Skip("LEDGER_SKIP_IT")
	}
}

func newTopic(t *testing.T, ctx context.Context, name string, partitions int32) *kgo.Client {
	cl, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ProducerLinger(5*time.Millisecond))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(cl.Close)
	adm := kadm.NewClient(cl)
	for _, topic := range []string{name, ingest.DLQTopic} {
		res, err := adm.CreateTopic(ctx, partitions, 1, nil, topic)
		if err == nil {
			err = res.Err
		}
		if err != nil && !errors.Is(err, kerr.TopicAlreadyExists) {
			t.Fatalf("tạo topic %s: %v", topic, err)
		}
	}
	return cl
}

func event(tenant, aggregate uuid.UUID, typ string) ingest.EventInput {
	id := uuid.Must(uuid.NewV7())
	h := sha256.Sum256(id[:])
	return ingest.EventInput{
		EventID: id, TenantID: tenant, EventType: typ, AggregateType: "TASK", AggregateID: aggregate,
		ActorKind: "DICH_VU", OccurredAt: time.Now().UTC(), PayloadHash: h[:], Classification: "C1",
	}
}

func produce(t *testing.T, ctx context.Context, cl *kgo.Client, topic string, evs []ingest.EventInput) {
	t.Helper()
	recs := make([]*kgo.Record, 0, len(evs))
	for _, ev := range evs {
		r, err := ingest.EncodeRecord(ev)
		if err != nil {
			t.Fatal(err)
		}
		r.Topic = topic
		recs = append(recs, r)
	}
	if err := cl.ProduceSync(ctx, recs...).FirstErr(); err != nil {
		t.Fatal(err)
	}
}

func svcPool(t *testing.T, ctx context.Context) *pgxpool.Pool {
	return testdb.Pool(ctx, t, db.SvcDSN)
}

// rows đếm bản ghi của tenant bằng vai trò quản trị (bỏ qua RLS).
func rows(t *testing.T, ctx context.Context, tenant uuid.UUID) (total, distinct int) {
	admin := testdb.Pool(ctx, t, db.AdminDSN)
	if err := admin.QueryRow(ctx,
		`SELECT count(*), count(DISTINCT event_id) FROM ledger.ledger_entry WHERE tenant_id = $1`, tenant).
		Scan(&total, &distinct); err != nil {
		t.Fatal(err)
	}
	return
}

func consumeUntil(t *testing.T, ctx context.Context, c *ingest.Consumer, want int, maxPolls int) int {
	t.Helper()
	got := 0
	for i := 0; i < maxPolls && got < want; i++ {
		pctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		n, err := c.PollOnce(pctx, 2000)
		cancel()
		if err != nil && !errors.Is(err, context.DeadlineExceeded) {
			t.Fatal(err)
		}
		got += n
	}
	return got
}

// TestMuoiNghinSuKienTrungNamPhanTram: 10.000 sự kiện có 5% trùng event_id, một phần đã được ghi trực tiếp
// trước (giả lập dừng giữa lô sau khi cam kết CSDL, trước khi xác nhận offset) và consumer bị khởi động
// lại giữa chừng: sổ cái có đúng số bản ghi duy nhất, mọi chuỗi liên tục và prev_hash khớp.
func TestMuoiNghinSuKienTrungNamPhanTram(t *testing.T) {
	skipIT(t)
	ctx := context.Background()
	topic := "it.ledger.append.v1"
	cl := newTopic(t, ctx, topic, 24)
	pool := svcPool(t, ctx)
	ing := ingest.NewIngester(pool, nil, nil)

	tenants := []uuid.UUID{uuid.New(), uuid.New()}
	aggregates := make([]uuid.UUID, 400)
	for i := range aggregates {
		aggregates[i] = uuid.New()
	}
	types := []string{"task.task.assigned.v1", "task.task.progressed.v1", "product.evidence.submitted.v1", "sys.audit.v1"}
	unique := make([]ingest.EventInput, 0, 9500)
	for i := 0; i < 9500; i++ {
		unique = append(unique, event(tenants[i%2], aggregates[i%len(aggregates)], types[i%len(types)]))
	}
	all := append([]ingest.EventInput(nil), unique...)
	for i := 0; i < 500; i++ {
		all = append(all, unique[(i*17)%len(unique)])
	}

	// Lô đầu của một chuỗi đã vào CSDL nhưng offset chưa xác nhận.
	first := unique[0]
	var pre []ingest.EventInput
	for _, ev := range unique {
		if ev.TenantID == first.TenantID && ev.ChainPartition() == first.ChainPartition() && len(pre) < 20 {
			pre = append(pre, ev)
		}
	}
	if _, err := ing.AppendBatch(ctx, first.TenantID, first.ChainPartition(), pre); err != nil {
		t.Fatal(err)
	}
	produce(t, ctx, cl, topic, all)

	c1, err := ingest.NewConsumer(brokers, topic, "ledger-it-1", ing, nil)
	if err != nil {
		t.Fatal(err)
	}
	got := consumeUntil(t, ctx, c1, 3000, 50)
	c1.Close() // khởi động lại giữa chừng
	c2, err := ingest.NewConsumer(brokers, topic, "ledger-it-1", ing, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c2.Close()
	got += consumeUntil(t, ctx, c2, len(unique)-len(pre)-got, 200)
	if got != len(unique)-len(pre) {
		t.Fatalf("consumer ghi %d bản ghi, cần %d", got, len(unique)-len(pre))
	}

	for _, tn := range tenants {
		total, distinct := rows(t, ctx, tn)
		if total != len(unique)/2 || distinct != total {
			t.Fatalf("tenant %s: %d bản ghi, %d event_id khác nhau, cần %d", tn, total, distinct, len(unique)/2)
		}
		var sum int64
		for chain := int16(0); chain < canon.ChainPartitionCount; chain++ {
			n, err := ing.VerifyPartitionChain(ctx, tn, chain)
			if err != nil {
				t.Fatal(err)
			}
			sum += n
		}
		if sum != int64(len(unique)/2) {
			t.Fatalf("kiểm chuỗi được %d bản ghi", sum)
		}
	}
	stats := ing.Stats()
	t.Logf("độ trễ p99 %.1f ms, %.0f bản ghi/giây", stats["ledger.ingest.lag_p99_ms"], stats["ledger.ingest.entries_per_second"])
}

// TestChuoiBiKhoaDungRoiMoLai: chuỗi bị khoá ghi thì consumer dừng partition, không ghi tiếp; bản ghi
// DANH_DAU_SU_CO mở khoá, consumer đọc lại và ghi nối tiếp sau bản ghi đánh dấu.
func TestChuoiBiKhoaDungRoiMoLai(t *testing.T) {
	skipIT(t)
	ctx := context.Background()
	topic := "it.ledger.locked.v1"
	cl := newTopic(t, ctx, topic, 1)
	ing := ingest.NewIngester(svcPool(t, ctx), nil, nil)
	tenant, agg := uuid.New(), uuid.New()
	chain := canon.ChainPartition(agg)
	before := event(tenant, agg, "esign.signature.completed.v1")
	if _, err := ing.AppendBatch(ctx, tenant, chain, []ingest.EventInput{before}); err != nil {
		t.Fatal(err)
	}
	if err := ing.LockPartition(ctx, tenant, chain, "PHA_CHUOI_IT"); err != nil {
		t.Fatal(err)
	}
	ev := event(tenant, agg, "esign.signature.completed.v1")
	produce(t, ctx, cl, topic, []ingest.EventInput{ev})
	c, err := ingest.NewConsumer(brokers, topic, "ledger-it-2", ing, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	var lockErr error
	for i := 0; i < 20 && lockErr == nil; i++ {
		pctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		_, lockErr = c.PollOnce(pctx, 100)
		cancel()
		if errors.Is(lockErr, context.DeadlineExceeded) {
			lockErr = nil
		}
	}
	if !errors.Is(lockErr, ingest.ErrPartitionLocked) || c.Paused() != 1 {
		t.Fatalf("cần dừng chuỗi bị khoá, nhận %v, %d partition dừng", lockErr, c.Paused())
	}
	if total, _ := rows(t, ctx, tenant); total != 1 {
		t.Fatalf("chuỗi bị khoá vẫn ghi: %d bản ghi", total)
	}
	if _, err := ing.AppendIncidentRecoveryMarker(ctx, ingest.IncidentRecoveryCmd{}); !errors.Is(err, ingest.ErrUnauthorizedAdminCommand) {
		t.Fatalf("lệnh thiếu người thực hiện phải bị từ chối: %v", err)
	}
	marker, err := ing.AppendIncidentRecoveryMarker(ctx, ingest.IncidentRecoveryCmd{
		TenantID: tenant, ChainPartition: chain, LastValidSealID: uuid.New(), IncidentRef: "SC-2026-01",
		AdminActorID: uuid.New(), AuditReason: "Biên bản xử lý sự cố số 01",
	})
	if err != nil || marker.SeqNo != 2 {
		t.Fatalf("đánh dấu sự cố: %v %+v", err, marker)
	}
	ingest.ResumeUnlockedForTest(ctx, c)
	if c.Paused() != 0 {
		t.Fatal("partition chưa được đọc lại")
	}
	if n := consumeUntil(t, ctx, c, 1, 20); n != 1 {
		t.Fatalf("sau khi mở khoá ghi %d bản ghi", n)
	}
	if n, err := ing.VerifyPartitionChain(ctx, tenant, chain); err != nil || n != 3 {
		t.Fatalf("chuỗi sau sự cố: %d, %v", n, err)
	}
}

// TestBanGhiHongVaoThuChet: bản ghi C2 mang trường ngoài danh sách cho phép không vào sổ cái, chuyển thư
// chết; bản ghi hợp lệ cùng lượt vẫn được ghi và offset được xác nhận.
func TestBanGhiHongVaoThuChet(t *testing.T) {
	skipIT(t)
	ctx := context.Background()
	topic := "it.ledger.dlq.v1"
	cl := newTopic(t, ctx, topic, 1)
	ing := ingest.NewIngester(svcPool(t, ctx), nil, nil)
	tenant := uuid.New()
	good := event(tenant, uuid.New(), "appraisal.rating.decided.v1")
	bad := event(tenant, uuid.New(), "oversight.complaint.filed.v1")
	bad.Classification = "C2"
	bad.Metadata = map[string]any{"noi_dung_khieu_nai": "văn bản rõ"}
	produce(t, ctx, cl, topic, []ingest.EventInput{good, bad})
	c, err := ingest.NewConsumer(brokers, topic, "ledger-it-3", ing, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if n := consumeUntil(t, ctx, c, 1, 20); n != 1 {
		t.Fatalf("ghi %d bản ghi", n)
	}
	if total, _ := rows(t, ctx, tenant); total != 1 {
		t.Fatalf("sổ cái có %d bản ghi", total)
	}
	dlq, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumeTopics(ingest.DLQTopic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()))
	if err != nil {
		t.Fatal(err)
	}
	defer dlq.Close()
	pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	found := false
	for !found && pctx.Err() == nil {
		dlq.PollFetches(pctx).EachRecord(func(r *kgo.Record) {
			for _, h := range r.Headers {
				if h.Key == "id" && string(h.Value) == bad.EventID.String() {
					found = true
				}
			}
		})
	}
	if !found {
		t.Fatal("không thấy bản ghi C2 ở thư chết")
	}
}

// TestTenantChuyenDen: TENANT_MOVED_IN nối tiếp seq_no và entry_hash cuối của cell nguồn, bản ghi sau
// nối tiếp bình thường; không ghi được lần hai hay lên chuỗi đã có bản ghi.
func TestTenantChuyenDen(t *testing.T) {
	skipIT(t)
	ctx := context.Background()
	ing := ingest.NewIngester(svcPool(t, ctx), nil, nil)
	tenant := uuid.New()
	origin := sha256.Sum256([]byte("entry cuối ở cell nguồn"))
	cmd := ingest.TenantMovedInCmd{TenantID: tenant, ChainPartition: 7, OriginCell: "CELL-SHARED-01",
		LastSealID: uuid.New(), OriginLastEntryHash: origin[:], OriginLastSeqNo: 100,
		AdminActorID: uuid.New(), AuditReason: "Quyết định chuyển cell số 12"}
	moved, err := ing.AppendTenantMovedIn(ctx, cmd)
	if err != nil {
		t.Fatal(err)
	}
	if moved.SeqNo != 101 || string(moved.PrevHash) != string(origin[:]) {
		t.Fatalf("TENANT_MOVED_IN: seq %d", moved.SeqNo)
	}
	var agg uuid.UUID
	for agg = uuid.New(); canon.ChainPartition(agg) != 7; agg = uuid.New() {
	}
	next, err := ing.AppendBatch(ctx, tenant, 7, []ingest.EventInput{event(tenant, agg, "config.period.locked.v1")})
	if err != nil || next.LastSeqNo != 102 {
		t.Fatalf("bản ghi sau: %v %+v", err, next)
	}
	if n, err := ing.VerifyPartitionChain(ctx, tenant, 7); err != nil || n != 2 {
		t.Fatalf("kiểm chuỗi: %d %v", n, err)
	}
	if _, err := ing.AppendTenantMovedIn(ctx, cmd); err == nil {
		t.Fatal("ghi TENANT_MOVED_IN lần hai phải lỗi")
	}
}

// TestDoThongLuongLo200 đo cục bộ thông lượng ghi lô 200 (chỉ ghi nhận số đo, giai đoạn này không chạy
// kiểm thử tải, YC-PT-19).
func TestDoThongLuongLo200(t *testing.T) {
	skipIT(t)
	ctx := context.Background()
	ing := ingest.NewIngester(svcPool(t, ctx), nil, nil)
	tenant := uuid.New()
	byChain := map[int16][]ingest.EventInput{}
	for len(byChain) < 16 || len(byChain[0]) < 200 {
		agg := uuid.New()
		c := canon.ChainPartition(agg)
		if c >= 16 || len(byChain[c]) >= 200 {
			continue
		}
		for i := 0; i < 200 && len(byChain[c]) < 200; i++ {
			byChain[c] = append(byChain[c], event(tenant, agg, "task.task.progressed.v1"))
		}
		if len(byChain) == 16 {
			full := true
			for _, v := range byChain {
				full = full && len(v) == 200
			}
			if full {
				break
			}
		}
	}
	start := time.Now()
	errs := make(chan error, len(byChain))
	for c, evs := range byChain {
		go func(c int16, evs []ingest.EventInput) {
			_, err := ing.AppendBatch(ctx, tenant, c, evs)
			errs <- err
		}(c, evs)
	}
	for range byChain {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
	d := time.Since(start)
	t.Logf("ghi %d bản ghi (16 chuỗi, lô 200) trong %v: %.0f bản ghi/giây", 16*200, d, float64(16*200)/d.Seconds())
}
