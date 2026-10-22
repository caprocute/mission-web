package rt

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/redis/go-redis/v9"
)

// MaxConnsPerUser là số kết nối SSE đồng thời tối đa của một người dùng trên một pod (suy luận: vài thẻ trình
// duyệt và một thiết bị).
const MaxConnsPerUser = 5

// ErrTooManyConnections được trả khi người dùng vượt MaxConnsPerUser.
var ErrTooManyConnections = errors.New("rt: quá số kết nối đồng thời")

// ErrDraining được trả khi pod đang tắt và không nhận kết nối mới.
var ErrDraining = errors.New("rt: máy chủ đang tắt")

// Event là một sự kiện SSE.
type Event struct {
	ID   string
	Name string
	Data string
}

// Sub là một kết nối SSE.
type Sub struct {
	C      chan Event
	done   chan struct{}
	once   sync.Once
	entry  *entry
	tenant string
	user   string
}

// Done đóng khi máy chủ yêu cầu ngắt kết nối (client chậm hoặc pod tắt).
func (s *Sub) Done() <-chan struct{} { return s.done }

func (s *Sub) kick() { s.once.Do(func() { close(s.done) }) }

type entry struct {
	key    string
	mu     sync.Mutex // tuần tự hoá việc đọc stream của người dùng
	lastID string
	subs   map[*Sub]struct{}
}

// Hub giữ kết nối của pod và đọc bus.
type Hub struct {
	R        redis.UniversalClient
	Log      *slog.Logger
	Block    time.Duration
	mu       sync.Mutex
	entries  map[string]*entry
	conns    atomic.Int64
	draining atomic.Bool
	ctx      atomic.Value
}

// NewHub tạo Hub.
func NewHub(r redis.UniversalClient, log *slog.Logger) *Hub {
	if log == nil {
		log = slog.Default()
	}
	return &Hub{R: r, Log: log, Block: 2 * time.Second, entries: map[string]*entry{}}
}

// Connections trả số kết nối đang giữ (metric cho HPA, ngưỡng 20.000 mỗi pod).
func (h *Hub) Connections() int64 { return h.conns.Load() }

// Subscribe đăng ký một kết nối. Mã bắt đầu của người dùng mới tại pod là phần tử cuối của stream, đọc trước khi
// đăng ký; sau khi đăng ký đọc bù một lần để không lỡ phần tử ghi vào giữa hai bước đó.
func (h *Hub) Subscribe(ctx context.Context, tenantID, userID string) (*Sub, error) {
	if h.draining.Load() {
		return nil, ErrDraining
	}
	key := UserStreamKey(tenantID, userID)
	last := "0-0"
	xs, err := h.R.XRevRangeN(ctx, key, "+", "-", 1).Result()
	if err != nil {
		return nil, err
	}
	if len(xs) == 1 {
		last = xs[0].ID
	}
	h.mu.Lock()
	e, ok := h.entries[key]
	if ok && len(e.subs) >= MaxConnsPerUser {
		h.mu.Unlock()
		return nil, ErrTooManyConnections
	}
	if !ok {
		e = &entry{key: key, lastID: last, subs: map[*Sub]struct{}{}}
		h.entries[key] = e
	}
	s := &Sub{C: make(chan Event, 64), done: make(chan struct{}), entry: e, tenant: tenantID, user: userID}
	e.subs[s] = struct{}{}
	h.mu.Unlock()
	h.conns.Add(1)
	if !ok {
		go h.pull(h.runCtx(), e)
	}
	return s, nil
}

func (h *Hub) runCtx() context.Context {
	if c, ok := h.ctx.Load().(context.Context); ok {
		return c
	}
	return context.Background()
}

// Unsubscribe gỡ kết nối.
func (h *Hub) Unsubscribe(s *Sub) {
	h.mu.Lock()
	if _, ok := s.entry.subs[s]; ok {
		delete(s.entry.subs, s)
		h.conns.Add(-1)
		if len(s.entry.subs) == 0 {
			delete(h.entries, s.entry.key)
		}
	}
	h.mu.Unlock()
	s.kick()
}

// Replay đọc các phần tử sau mã after còn trong stream. gap = true khi after cũ hơn phần tử đầu còn giữ (có thể
// đã bị cắt), client cần tải lại hộp thông báo.
func (h *Hub) Replay(ctx context.Context, tenantID, userID, after string) (events []Event, gap bool, err error) {
	key := UserStreamKey(tenantID, userID)
	first, err := h.R.XRangeN(ctx, key, "-", "+", 1).Result()
	if err != nil {
		return nil, false, err
	}
	if len(first) == 1 && Less(after, first[0].ID) {
		// Phần tử ngay sau `after` có thể đã bị cắt; không biết chắc nên báo tải lại.
		gap = true
	}
	xs, err := h.R.XRange(ctx, key, "("+after, "+").Result()
	if err != nil {
		return nil, gap, err
	}
	for _, x := range xs {
		events = append(events, toEvent(x))
	}
	return events, gap, nil
}

func toEvent(x redis.XMessage) Event {
	name, _ := x.Values["event"].(string)
	data, _ := x.Values["data"].(string)
	return Event{ID: x.ID, Name: name, Data: data}
}

// Run đọc chặn BusShards stream bus tới khi ctx kết thúc.
func (h *Hub) Run(ctx context.Context) {
	h.ctx.Store(ctx)
	var wg sync.WaitGroup
	for i := 0; i < BusShards; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			h.readBus(ctx, n)
		}(i)
	}
	wg.Wait()
}

func (h *Hub) readBus(ctx context.Context, n int) {
	key := BusKey(n)
	// Bắt đầu từ phần tử cuối hiện có thay cho "$" để không lỡ tín hiệu ghi vào giữa hai lần đọc chặn.
	last := "0-0"
	for ctx.Err() == nil {
		xs, err := h.R.XRevRangeN(ctx, key, "+", "-", 1).Result()
		if err == nil {
			if len(xs) == 1 {
				last = xs[0].ID
			}
			break
		}
		h.Log.Error("đọc bus thông báo thất bại", "bus", key, "err", err.Error())
		time.Sleep(500 * time.Millisecond)
	}
	for ctx.Err() == nil {
		res, err := h.R.XRead(ctx, &redis.XReadArgs{Streams: []string{key, last}, Count: 500, Block: h.Block}).Result()
		if errors.Is(err, redis.Nil) {
			continue
		}
		if err != nil {
			if ctx.Err() == nil {
				h.Log.Error("đọc bus thông báo thất bại", "bus", key, "err", err.Error())
				time.Sleep(500 * time.Millisecond)
			}
			continue
		}
		woken := map[string]bool{}
		for _, st := range res {
			for _, m := range st.Messages {
				last = m.ID
				t, _ := m.Values["t"].(string)
				u, _ := m.Values["u"].(string)
				woken[UserStreamKey(t, u)] = true
			}
		}
		for k := range woken {
			h.mu.Lock()
			e := h.entries[k]
			h.mu.Unlock()
			if e != nil {
				go h.pull(ctx, e)
			}
		}
	}
}

// pull đọc stream của người dùng từ mã cuối đã gửi và phân phát cho mọi kết nối của người đó tại pod.
func (h *Hub) pull(ctx context.Context, e *entry) {
	e.mu.Lock()
	defer e.mu.Unlock()
	xs, err := h.R.XRange(ctx, e.key, "("+e.lastID, "+").Result()
	if err != nil {
		h.Log.Error("đọc stream người dùng thất bại", "err", err.Error())
		return
	}
	if len(xs) == 0 {
		return
	}
	e.lastID = xs[len(xs)-1].ID
	h.mu.Lock()
	subs := make([]*Sub, 0, len(e.subs))
	for s := range e.subs {
		subs = append(subs, s)
	}
	h.mu.Unlock()
	for _, x := range xs {
		ev := toEvent(x)
		for _, s := range subs {
			select {
			case s.C <- ev:
			default:
				// Client không đọc kịp: ngắt để client nối lại và phát lại theo Last-Event-ID.
				s.kick()
			}
		}
	}
}

// Drain ngừng nhận kết nối mới và đóng dần kết nối hiện có trong khoảng over (TL-04: 30 giây).
func (h *Hub) Drain(over time.Duration) {
	h.draining.Store(true)
	h.mu.Lock()
	var subs []*Sub
	for _, e := range h.entries {
		for s := range e.subs {
			subs = append(subs, s)
		}
	}
	h.mu.Unlock()
	if len(subs) == 0 {
		return
	}
	step := over / time.Duration(len(subs)+1)
	for _, s := range subs {
		s.kick()
		time.Sleep(step)
	}
}
