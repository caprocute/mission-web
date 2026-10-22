// Kiểm thử tích hợp DV-12-02 trên Valkey 8 và PostgreSQL 17 thật: hai pod dùng chung Valkey, người dùng kết nối
// pod A, thông báo sinh ở pod B vẫn tới; nối lại với Last-Event-ID nhận đủ phần bị lỡ; mã quá cũ nhận `unread`
// kèm `reload`; giới hạn kết nối mỗi người dùng; tắt pod đóng mọi kết nối và từ chối kết nối mới. Kiểm thử tải
// chỉ chạy khi đặt NOTIFY_LOAD_CONNS (ví dụ 5000).
package it

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/notification/internal/api"
	"mission/services-go/notification/internal/consume"
	"mission/services-go/notification/internal/contract"
	"mission/services-go/notification/internal/rt"
	"mission/services-go/notification/internal/store"
	"mission/services-go/notification/internal/testdb"
)

type sseEvent struct {
	ID, Name, Data string
	At             time.Time
}

// openSSE mở luồng và trả kênh sự kiện; dòng chú thích bị bỏ qua, dòng retry được báo bằng sự kiện tên "retry".
func openSSE(t testing.TB, url, tok, lastID string) (chan sseEvent, *http.Response, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	rq, _ := http.NewRequestWithContext(ctx, "GET", url+"/api/v1/notifications/stream", nil)
	rq.Header.Set("Authorization", "Bearer "+tok)
	if lastID != "" {
		rq.Header.Set("Last-Event-ID", lastID)
	}
	rs, err := sseClient.Do(rq)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	ch := make(chan sseEvent, 64)
	if rs.StatusCode != 200 {
		cancel()
		close(ch)
		return ch, rs, func() {}
	}
	go func() {
		defer close(ch)
		br := bufio.NewReader(rs.Body)
		var ev sseEvent
		for {
			line, err := br.ReadString('\n')
			if err != nil {
				return
			}
			line = strings.TrimRight(line, "\n")
			switch {
			case line == "":
				if ev.Name != "" {
					ev.At = time.Now()
					ch <- ev
				}
				ev = sseEvent{}
			case strings.HasPrefix(line, "retry: "):
				ev.Name = "retry"
				ev.Data = strings.TrimPrefix(line, "retry: ")
			case strings.HasPrefix(line, "id: "):
				ev.ID = strings.TrimPrefix(line, "id: ")
			case strings.HasPrefix(line, "event: "):
				ev.Name = strings.TrimPrefix(line, "event: ")
			case strings.HasPrefix(line, "data: "):
				ev.Data = strings.TrimPrefix(line, "data: ")
			}
		}
	}()
	return ch, rs, func() { cancel(); rs.Body.Close() }
}

var sseClient = &http.Client{Transport: &http.Transport{MaxConnsPerHost: 0, MaxIdleConnsPerHost: 10000}}

func next(t testing.TB, ch chan sseEvent, d time.Duration) sseEvent {
	t.Helper()
	select {
	case ev, ok := <-ch:
		if !ok {
			t.Fatal("luồng SSE đã đóng")
		}
		return ev
	case <-time.After(d):
		t.Fatalf("hết %v chờ sự kiện SSE", d)
	}
	return sseEvent{}
}

func startValkey(t testing.TB, ctx context.Context) string {
	t.Helper()
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{Image: "valkey/valkey:8", ExposedPorts: []string{"6379/tcp"},
			WaitingFor: wait.ForListeningPort("6379/tcp")},
		Started: true})
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, c)
	ep, err := c.Endpoint(ctx, "")
	if err != nil {
		t.Fatal(err)
	}
	return ep
}

func TestRealtimeStream(t *testing.T) {
	if testing.Short() {
		t.Skip("kiểm thử tích hợp cần Docker")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	addr := startValkey(t, ctx)
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
	pool, err := pgxpool.New(ctx, db.SvcDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	quiet := slog.New(slog.NewTextHandler(io.Discard, nil))

	// Pod A giữ kết nối; pod B chạy consumer và đẩy thông báo. Hai pod chỉ chung Valkey và CSDL.
	rdbA := redis.NewClient(&redis.Options{Addr: addr, PoolSize: 64})
	rdbB := redis.NewClient(&redis.Options{Addr: addr})
	defer rdbA.Close()
	defer rdbB.Close()
	hubA := rt.NewHub(rdbA, quiet)
	hubA.Block = 500 * time.Millisecond
	hubCtx, hubStop := context.WithCancel(ctx)
	defer hubStop()
	go hubA.Run(hubCtx)
	notifierB := &rt.Notifier{Pub: rt.Publisher{R: rdbB}, Reader: store.Reader{Pool: pool}, Log: quiet}
	notifierA := &rt.Notifier{Pub: rt.Publisher{R: rdbA}, Reader: store.Reader{Pool: pool}, Log: quiet}
	srvA := httptest.NewServer(api.Handler(api.Deps{Inbox: store.Reader{Pool: pool}, Stream: hubA, Unread: notifierA.Unread, Log: quiet}))
	defer srvA.Close()

	procB, err := consume.NewProcessor(store.Beginner{Pool: pool}, quiet)
	if err != nil {
		t.Fatal(err)
	}
	procB.Created = func(ctx context.Context, tenantID string, items []consume.Created) {
		by := map[string][]string{}
		for _, it := range items {
			by[it.RecipientID] = append(by[it.RecipientID], it.NotificationID)
		}
		notifierB.Notify(ctx, tenantID, by)
	}
	send := func(t *testing.T, tenantID string, users []string, code string) {
		t.Helper()
		b, _ := consume.Encode(consume.TopicNotifyRequested, consume.YeuCauThongBao{RecipientIDs: users, TemplateCode: code,
			Params: `{"dueDate":"15/01/2027"}`, Priority: "THUONG", AggregateVersion: 1})
		if _, err := procB.Process(ctx, consume.Message{Topic: consume.TopicNotifyRequested, Value: b, Timestamp: time.Now(),
			Headers: map[string]string{"id": uuid.NewString(), "tenantid": tenantID}}); err != nil {
			t.Fatal(err)
		}
	}
	tok := token(tenantA, user1)
	time.Sleep(300 * time.Millisecond) // để bus reader của pod A lấy mã bắt đầu

	var lastID string
	t.Run("kết nối pod A nhận thông báo sinh ở pod B", func(t *testing.T) {
		ch, rs, closeFn := openSSE(t, srvA.URL, tok, "")
		defer closeFn()
		if rs.Header.Get("Content-Type") != "text/event-stream; charset=utf-8" || rs.Header.Get("Cache-Control") != "no-cache" {
			t.Fatalf("tiêu đề luồng sai: %v", rs.Header)
		}
		if ev := next(t, ch, 2*time.Second); ev.Name != "retry" || ev.Data != "5000" {
			t.Fatalf("đầu luồng phải là retry: 5000, nhận %+v", ev)
		}
		start := time.Now()
		send(t, tenantA, []string{user1}, "OKR_NHAC_CHECKIN")
		ev := next(t, ch, 3*time.Second)
		if ev.Name != rt.EventNotification || ev.ID == "" {
			t.Fatalf("cần sự kiện notification, nhận %+v", ev)
		}
		var n store.Notification
		if err := json.Unmarshal([]byte(ev.Data), &n); err != nil || n.Title != "Đến kỳ cập nhật tiến độ OKR" {
			t.Fatalf("dữ liệu notification sai: %s", ev.Data)
		}
		spec, err := contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi"))
		if err != nil {
			t.Fatal(err)
		}
		_ = spec // lược đồ sự kiện SSE kiểm qua lược đồ Notification ở kiểm thử hộp thông báo
		u := next(t, ch, 2*time.Second)
		if u.Name != rt.EventUnread || u.Data != `{"unreadCount":1}` {
			t.Fatalf("cần sự kiện unread, nhận %+v", u)
		}
		t.Logf("độ trễ phân phát giữa hai pod: %v", ev.At.Sub(start))
		lastID = u.ID
		// Đánh dấu đã đọc ở API phát unread mới.
		rq, _ := http.NewRequest("POST", srvA.URL+"/api/v1/notifications/read", strings.NewReader(`{"all":true}`))
		rq.Header.Set("Authorization", "Bearer "+tok)
		rq.Header.Set("Content-Type", "application/json")
		if rs, err := http.DefaultClient.Do(rq); err != nil || rs.StatusCode != 200 {
			t.Fatalf("đánh dấu đã đọc lỗi: %v", err)
		}
		if u := next(t, ch, 2*time.Second); u.Name != rt.EventUnread || u.Data != `{"unreadCount":0}` {
			t.Fatalf("đánh dấu đã đọc phải phát unread 0, nhận %+v", u)
		}
	})

	t.Run("nối lại với Last-Event-ID nhận đủ phần bị lỡ", func(t *testing.T) {
		// Trong lúc mất kết nối có hai thông báo mới (mỗi cái kèm unread) cộng một unread từ đánh dấu đã đọc.
		send(t, tenantA, []string{user1}, "OKR_NHAC_CHECKIN")
		send(t, tenantA, []string{user1}, "THONG_BAO_CHUNG")
		ch, _, closeFn := openSSE(t, srvA.URL, tok, lastID)
		defer closeFn()
		next(t, ch, 2*time.Second) // retry
		var names []string
		for i := 0; i < 5; i++ {
			names = append(names, next(t, ch, 2*time.Second).Name)
		}
		if strings.Join(names, ",") != "unread,notification,unread,notification,unread" {
			t.Fatalf("phát lại sai thứ tự hoặc thiếu: %v", names)
		}
		select {
		case ev := <-ch:
			t.Fatalf("không được phát trùng: %+v", ev)
		case <-time.After(700 * time.Millisecond):
		}
	})

	t.Run("mã quá cũ thì báo tải lại", func(t *testing.T) {
		pub := rt.Publisher{R: rdbB}
		for i := 0; i < rt.KeepEntries+40; i++ {
			if _, err := pub.Publish(ctx, tenantA, user2, rt.EventJob, []byte(`{"id":"`+uuid.NewString()+`","status":"DANG_CHAY","progress":`+strconv.Itoa(i%100)+`}`)); err != nil {
				t.Fatal(err)
			}
		}
		// Mọi phần tử đều mới hơn 10 phút nên chưa bị cắt; giả lập phần tử cũ bằng mã 1-0 đã bị cắt.
		if _, err := rdbB.XTrimMinID(ctx, rt.UserStreamKey(tenantA, user2), strconv.FormatInt(time.Now().UnixMilli()-1, 10)+"-0").Result(); err != nil {
			t.Fatal(err)
		}
		pub.Publish(ctx, tenantA, user2, rt.EventJob, []byte(`{"id":"x","status":"XONG","progress":100}`))
		ch, _, closeFn := openSSE(t, srvA.URL, token(tenantA, user2), "1-0")
		defer closeFn()
		next(t, ch, 2*time.Second)
		if ev := next(t, ch, 2*time.Second); ev.Name != rt.EventUnread || !strings.Contains(ev.Data, `"reload":true`) {
			t.Fatalf("mã quá cũ phải nhận unread reload, nhận %+v", ev)
		}
		if ev := next(t, ch, 2*time.Second); ev.Name != rt.EventJob {
			t.Fatalf("sau reload vẫn phát phần còn giữ, nhận %+v", ev)
		}
		n, _ := rdbB.XLen(ctx, rt.UserStreamKey(tenantA, user2)).Result()
		if n > int64(rt.KeepEntries)+40 {
			t.Fatalf("stream phải bị cắt, còn %d phần tử", n)
		}
	})

	t.Run("giới hạn kết nối mỗi người dùng", func(t *testing.T) {
		tk := token(tenantB, user3)
		var closers []func()
		for i := 0; i < rt.MaxConnsPerUser; i++ {
			ch, rs, c := openSSE(t, srvA.URL, tk, "")
			if rs.StatusCode != 200 {
				t.Fatalf("kết nối %d bị từ chối", i+1)
			}
			next(t, ch, 2*time.Second)
			closers = append(closers, c)
		}
		_, rs, _ := openSSE(t, srvA.URL, tk, "")
		b, _ := io.ReadAll(rs.Body)
		if rs.StatusCode != 429 {
			t.Fatalf("kết nối thứ %d phải trả 429, nhận %d", rt.MaxConnsPerUser+1, rs.StatusCode)
		}
		spec, _ := contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi"))
		if err := spec.Validate("modules/platform.yaml", "/notifications/stream", "get", 429, rs.Header.Get("Content-Type"), b); err != nil {
			t.Fatal(err)
		}
		for _, c := range closers {
			c()
		}
	})

	t.Run("tắt pod đóng mọi kết nối và từ chối kết nối mới", func(t *testing.T) {
		var chans []chan sseEvent
		for i := 0; i < 20; i++ {
			ch, _, c := openSSE(t, srvA.URL, token(tenantB, uuid.NewString()), "")
			defer c()
			next(t, ch, 2*time.Second)
			chans = append(chans, ch)
		}
		start := time.Now()
		go hubA.Drain(2 * time.Second)
		for _, ch := range chans {
			for range ch { // chờ luồng đóng
			}
		}
		if d := time.Since(start); d > 3*time.Second {
			t.Fatalf("đóng kết nối mất %v", d)
		}
		_, rs, _ := openSSE(t, srvA.URL, tok, "")
		if rs.StatusCode != 503 {
			t.Fatalf("pod đang tắt phải trả 503, nhận %d", rs.StatusCode)
		}
	})
}

// TestStreamLoad mở NOTIFY_LOAD_CONNS kết nối trên một pod, phát 500 sự kiện mỗi giây trong 10 giây và đo độ trễ
// phân phát p95 cùng bộ nhớ heap tăng thêm mỗi kết nối (gồm cả phía client trong cùng tiến trình nên là cận trên).
func TestStreamLoad(t *testing.T) {
	n, _ := strconv.Atoi(os.Getenv("NOTIFY_LOAD_CONNS"))
	if n == 0 {
		t.Skip("đặt NOTIFY_LOAD_CONNS để chạy kiểm thử tải")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	addr := startValkey(t, ctx)
	quiet := slog.New(slog.NewTextHandler(io.Discard, nil))
	rdb := redis.NewClient(&redis.Options{Addr: addr, PoolSize: 256})
	defer rdb.Close()
	hub := rt.NewHub(rdb, quiet)
	go hub.Run(ctx)
	srv := httptest.NewServer(api.Handler(api.Deps{Stream: hub, Log: quiet}))
	defer srv.Close()
	time.Sleep(300 * time.Millisecond)

	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)
	users := make([]string, n)
	chans := make([]chan sseEvent, n)
	closers := make([]func(), n)
	// httptest.Server.Close chờ mọi yêu cầu kết thúc nên phải đóng luồng trước.
	defer func() {
		for _, c := range closers {
			if c != nil {
				c()
			}
		}
	}()
	var wg sync.WaitGroup
	sem := make(chan struct{}, 200)
	for i := range users {
		users[i] = uuid.NewString()
		wg.Add(1)
		sem <- struct{}{}
		go func(i int) {
			defer wg.Done()
			defer func() { <-sem }()
			ch, _, c := openSSE(t, srv.URL, token(tenantA, users[i]), "")
			closers[i] = c
			next(t, ch, 10*time.Second)
			chans[i] = ch
		}(i)
	}
	wg.Wait()
	runtime.GC()
	var after runtime.MemStats
	runtime.ReadMemStats(&after)
	t.Logf("%d kết nối, heap tăng %.1f KB mỗi kết nối, goroutine %d", hub.Connections(),
		float64(after.HeapAlloc-before.HeapAlloc)/float64(n)/1024, runtime.NumGoroutine())

	var mu sync.Mutex
	var lat []time.Duration
	for i := range chans {
		go func(ch chan sseEvent) {
			for ev := range ch {
				var p struct{ Sent int64 }
				if json.Unmarshal([]byte(ev.Data), &p) == nil && p.Sent > 0 {
					mu.Lock()
					lat = append(lat, ev.At.Sub(time.Unix(0, p.Sent)))
					mu.Unlock()
				}
			}
		}(chans[i])
	}
	pub := rt.Publisher{R: rdb}
	const rate, secs = 500, 10
	tick := time.NewTicker(time.Second / rate)
	for i := 0; i < rate*secs; i++ {
		<-tick.C
		u := users[i%n]
		go pub.Publish(ctx, tenantA, u, rt.EventJob, []byte(fmt.Sprintf(`{"sent":%d}`, time.Now().UnixNano())))
	}
	tick.Stop()
	time.Sleep(3 * time.Second)
	mu.Lock()
	defer mu.Unlock()
	sort.Slice(lat, func(i, j int) bool { return lat[i] < lat[j] })
	if len(lat) < rate*secs*99/100 {
		t.Fatalf("chỉ nhận %d/%d sự kiện", len(lat), rate*secs)
	}
	p95 := lat[len(lat)*95/100]
	t.Logf("nhận %d sự kiện, p50 %v, p95 %v, p99 %v", len(lat), lat[len(lat)/2], p95, lat[len(lat)*99/100])
	if p95 > 2*time.Second {
		t.Fatalf("p95 %v vượt 2 giây (PERF-22)", p95)
	}
}
