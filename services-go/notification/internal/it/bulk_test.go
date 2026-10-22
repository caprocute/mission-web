// Kiểm thử tích hợp DV-12-04 trên PostgreSQL 17 và Valkey 8 thật: hai tenant có đợt 100.000 và 1.000 người nhận
// cùng lúc, tenant nhỏ gửi xong mà không chờ tenant lớn; số SMS của đợt không vượt giới hạn trong mọi cửa sổ; trong
// lúc đợt chạy, thông báo thường vẫn vào hộp thông báo với p95 ≤ 2 giây.
package it

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"

	"mission/services-go/notification/internal/bulk"
	"mission/services-go/notification/internal/channel"
	"mission/services-go/notification/internal/consume"
	"mission/services-go/notification/internal/crypt"
	"mission/services-go/notification/internal/deliver"
	"mission/services-go/notification/internal/directory"
	"mission/services-go/notification/internal/prefs"
	"mission/services-go/notification/internal/ratelimit"
	"mission/services-go/notification/internal/store"
	"mission/services-go/notification/internal/testdb"
)

type anyPhone struct{}

func (anyPhone) Contact(context.Context, string, string) (directory.Contact, error) {
	return directory.Contact{Phone: "0912345678"}, nil
}

func ids(n int) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = uuid.NewString()
	}
	return out
}

func TestBulkFairnessAndRate(t *testing.T) {
	if testing.Short() {
		t.Skip("kiểm thử tích hợp cần Docker")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	pg, err := tcpg.Run(ctx, "postgres:17", tcpg.WithDatabase("notify"), tcpg.WithUsername("postgres"),
		tcpg.WithPassword(uuid.NewString()), tcpg.BasicWaitStrategies())
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, pg)
	adminDSN, _ := pg.ConnectionString(ctx, "sslmode=disable")
	db, err := testdb.Bootstrap(ctx, adminDSN, "notify")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, db.SvcDSN+"&pool_max_conns=40")
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	admin, err := pgx.Connect(ctx, adminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close(ctx)
	rdb := redis.NewClient(&redis.Options{Addr: startValkey(t, ctx)})
	defer rdb.Close()

	big, small := uuid.NewString(), uuid.NewString()
	bigUsers, smallUsers := ids(100_000), ids(1_000)
	// Người nhận bật SMS cho nhóm DANH_GIA (mẫu HS_NHAC_NOP).
	for tenant, users := range map[string][]string{big: bigUsers, small: smallUsers} {
		if _, err := admin.Exec(ctx, `INSERT INTO notify.channel_pref (tenant_id, user_id, category, channels)
			SELECT $1, u, 'DANH_GIA', ARRAY['INAPP', 'SMS']::varchar(8)[] FROM unnest($2::uuid[]) u`, tenant, users); err != nil {
			t.Fatal(err)
		}
	}

	proc, err := consume.NewProcessor(store.Beginner{Pool: pool}, nil)
	if err != nil {
		t.Fatal(err)
	}
	proc.BulkThreshold = 500
	proc.Writer.External = []string{"SMS"}
	event := func(t *testing.T, tenant string, users []string, prio string) time.Duration {
		t.Helper()
		b, err := consume.Encode(consume.TopicNotifyRequested, consume.YeuCauThongBao{RecipientIDs: users,
			TemplateCode: "HS_NHAC_NOP", Params: `{"periodName":"năm 2026"}`, Priority: prio})
		if err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		if _, err := proc.Process(ctx, consume.Message{Topic: consume.TopicNotifyRequested, Value: b, Timestamp: start,
			Headers: map[string]string{"id": uuid.NewString(), "tenantid": tenant}}); err != nil {
			t.Fatal(err)
		}
		return time.Since(start)
	}
	if d := event(t, big, bigUsers, "THUONG"); d > 5*time.Second {
		t.Fatalf("ghi đợt 100.000 người nhận chặn consumer %v", d)
	}
	event(t, small, smallUsers, "THUONG")
	var jobs int
	_ = admin.QueryRow(ctx, `SELECT count(*) FROM notify.bulk_job`).Scan(&jobs)
	if jobs != 2 {
		t.Fatalf("phải có hai đợt, có %d", jobs)
	}

	// Cổng SMS giả lập ghi thời điểm nhận; giới hạn thu nhỏ 200 tin mỗi cửa sổ 1 giây.
	var mu sync.Mutex
	var stamps []time.Time
	smsSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		stamps = append(stamps, time.Now())
		mu.Unlock()
		_, _ = w.Write([]byte(`{"code":"0","messageId":"m"}`))
	}))
	defer smsSrv.Close()
	const limit = 200
	limits := ratelimit.Limits{Window: time.Second, Per: map[string]int{"SMS": limit}}
	kr, _ := crypt.NewLocalKeyring(make([]byte, 32))
	field := crypt.Field{Keys: kr}
	w := &deliver.Worker{Pool: pool, Prefs: prefs.Store{Pool: pool, Field: field}, Directory: anyPhone{}, Field: field,
		Lanes: []deliver.Lane{{Channel: "SMS", Sender: channel.ViettelSMS{BaseURL: smsSrv.URL}, Concurrency: 16,
			Breaker: channel.NewBreaker(5, time.Second)}},
		Batch: 100, Poll: 20 * time.Millisecond, Limiter: ratelimit.Valkey{R: rdb, Limits: limits}, Limits: limits}
	x := &bulk.Expander{Pool: pool, Writer: proc.Writer, Chunk: 1000, Poll: 20 * time.Millisecond}
	runCtx, stop := context.WithCancel(ctx)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); x.Run(runCtx) }()
	go func() { defer wg.Done(); w.Run(runCtx) }()

	sent := func(tenant string) int {
		var n int
		_ = admin.QueryRow(ctx, `SELECT count(*) FROM notify.delivery WHERE tenant_id = $1 AND channel = 'SMS'
			AND status = 'DA_GUI'`, tenant).Scan(&n)
		return n
	}
	// Thông báo thường trong lúc đợt chạy: thời gian từ khi nhận sự kiện tới khi có trong hộp thông báo.
	var lat []time.Duration
	deadline := time.Now().Add(90 * time.Second)
	for sent(small) < len(smallUsers) {
		if time.Now().After(deadline) {
			stop()
			rows, _ := admin.Query(ctx, `SELECT tenant_id = $1, channel, status, batch_id IS NOT NULL, coalesce(last_error, ''), count(*)
				FROM notify.delivery GROUP BY 1, 2, 3, 4, 5`, small)
			for rows.Next() {
				v, _ := rows.Values()
				t.Log(v...)
			}
			rows.Close()
			var done, total int
			_ = admin.QueryRow(ctx, `SELECT sum(done), sum(total) FROM notify.bulk_job`).Scan(&done, &total)
			t.Log("bulk", done, total)
			t.Fatalf("tenant nhỏ chưa gửi xong: %d/%d", sent(small), len(smallUsers))
		}
		lat = append(lat, event(t, small, []string{uuid.NewString()}, "THUONG"))
		time.Sleep(100 * time.Millisecond)
	}
	bigDone := sent(big)
	stop()
	wg.Wait()
	if bigDone >= len(bigUsers)/2 {
		t.Fatalf("tenant nhỏ phải xong trước khi tenant lớn đi được nửa đợt, tenant lớn đã gửi %d", bigDone)
	}
	sort.Slice(lat, func(i, j int) bool { return lat[i] < lat[j] })
	if p95 := lat[len(lat)*95/100]; p95 > 2*time.Second {
		t.Fatalf("p95 thông báo thường %v", p95)
	}
	// Lượt gửi được cấp theo đồng hồ Valkey; lần gửi thật đến cổng chậm hơn lúc cấp tối đa khoảng một lô, nên
	// kiểm cửa sổ dài 1 giây trừ 100 ms độ trễ đó.
	mu.Lock()
	defer mu.Unlock()
	sort.Slice(stamps, func(i, j int) bool { return stamps[i].Before(stamps[j]) })
	worst, j := 0, 0
	for i := range stamps {
		for stamps[i].Sub(stamps[j]) >= 900*time.Millisecond {
			j++
		}
		worst = max(worst, i-j+1)
	}
	if worst > limit {
		t.Fatalf("có cửa sổ gửi %d tin, vượt giới hạn %d", worst, limit)
	}
	t.Logf("SMS đã gửi %d (tenant lớn %d), cửa sổ nhiều nhất %d, p95 thông báo thường %v, %d mẫu",
		len(stamps), bigDone, worst, lat[len(lat)*95/100], len(lat))
}
