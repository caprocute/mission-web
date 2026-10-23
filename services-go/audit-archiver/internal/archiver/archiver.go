// Package archiver là vòng tiêu thụ của audit-archiver (INF-24; TL-04 "Kafka: MirrorMaker 2" đoạn audit-archiver,
// "Bảng lịch sao lưu" dòng sys.audit.v1, G6): đọc sys.audit.v1 (và topic kiểm toán khác nếu cấu hình), gom lô theo
// phân vùng trong cửa sổ thời gian hoặc ngưỡng kích thước, ghi kê khai đã ký, chữ ký và tệp Parquet lên bucket
// Object Lock, rồi mới xác nhận offset.
//
// Bảo đảm không mất, không trùng dựa trên kho là nguồn sự thật:
//   - Offset xác nhận mang metadata là khoá kê khai cuối đã lưu. Khi nhận phân vùng, dịch vụ đọc kê khai đó, liệt
//     kê các kê khai ghi sau nó (sự cố giữa lúc tải lên và lúc xác nhận), kiểm chuỗi băm và dải offset liên tục,
//     rồi tiếp tục từ offset kết thúc của kê khai cuối trong kho chứ không theo offset Kafka.
//   - Thứ tự ghi là kê khai, chữ ký, dữ liệu. Kê khai có mà thiếu dữ liệu thì dịch vụ đọc lại đúng dải offset trong
//     Kafka, dựng lại Parquet (mã hoá xác định) và chỉ tải lên khi SHA-256 khớp kê khai đã ký.
//   - Mọi lần ghi đều không đè (If-None-Match và so byte), nên tiến trình chạy lại ghi cùng tên đối tượng cho cùng
//     dải offset mà không sinh phiên bản mới.
package archiver

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/kmsg"

	"mission/services-go/audit-archiver/internal/archive"
	"mission/services-go/audit-archiver/internal/metrics"
	"mission/services-go/audit-archiver/internal/objstore"
)

// Signer ký byte kê khai (OpenBao Transit).
type Signer interface {
	Sign(ctx context.Context, data []byte) (string, error)
}

// Store là bucket Object Lock.
type Store interface {
	PutLocked(ctx context.Context, key string, data []byte, contentType string) (bool, error)
	Get(ctx context.Context, key string) ([]byte, error)
	Exists(ctx context.Context, key string) (bool, error)
	CheckLocked(ctx context.Context, key string, minLeft time.Duration) error
	List(ctx context.Context, prefix, startAfter string, fn func(key string) error) error
}

// Config là cấu hình vòng tiêu thụ.
type Config struct {
	Brokers   []string
	KafkaOpts []kgo.Opt // TLS, SASL… và tuỳ chọn kiểm thử
	Group     string
	Topics    []string
	Cell      string
	SignKey   string
	Retention time.Duration
	// Window là tuổi tối đa của bản ghi cũ nhất trong lô trước khi ghi; mặc định 4 phút để tổng độ trễ từ sự kiện
	// tới tệp (gồm ký và tải lên) nằm trong 5 phút của TL-04.
	Window     time.Duration
	MaxRecords int
	MaxBytes   int
	// Tick là chu kỳ kiểm lô đến hạn khi không có bản ghi mới.
	Tick time.Duration
}

func (c *Config) defaults() {
	if c.Group == "" {
		c.Group = "audit-archiver"
	}
	if len(c.Topics) == 0 {
		c.Topics = []string{"sys.audit.v1"}
	}
	if c.Window <= 0 {
		c.Window = 4 * time.Minute
	}
	if c.MaxRecords <= 0 {
		c.MaxRecords = 200_000
	}
	if c.MaxBytes <= 0 {
		c.MaxBytes = 64 << 20
	}
	if c.Tick <= 0 {
		c.Tick = time.Second
	}
}

type tp struct {
	topic     string
	partition int32
}

type pending struct {
	batch *archive.Batch
	sig   string
	n     int // số bản ghi đầu của rows thuộc lô
}

type partState struct {
	pos       int64 // vị trí đọc sau kê khai cuối trong kho
	prev      archive.Link
	rows      []archive.Row
	bytes     int
	firstTs   time.Time
	firstSeen time.Time
	pending   *pending
	commit    bool // cần xác nhận lại offset pos với metadata prev.Key
	broken    string
	lastFlush time.Time
	lastLag   time.Duration
}

// Archiver là dịch vụ lưu trữ.
type Archiver struct {
	cfg    Config
	store  Store
	signer Signer
	log    *slog.Logger
	m      *metrics.Registry
	now    func() time.Time

	cl  *kgo.Client
	adm *kadm.Client

	mu    sync.Mutex
	parts map[tp]*partState
}

// Metric names.
const (
	MRecords   = "audit_archiver_ban_ghi_luu_total"
	MFiles     = "audit_archiver_tep_luu_total"
	MErrors    = "audit_archiver_loi_total"
	MLag       = "audit_archiver_do_tre_giay"
	MBatchLag  = "audit_archiver_do_tre_lo_cuoi_giay"
	MLastFlush = "audit_archiver_lan_luu_cuoi_giay"
	MBroken    = "audit_archiver_phan_vung_dung"
	MAssigned  = "audit_archiver_phan_vung_dang_giu"
)

// New tạo dịch vụ và client Kafka (chưa tham gia nhóm cho tới khi Run poll).
func New(cfg Config, store Store, signer Signer, log *slog.Logger, m *metrics.Registry) (*Archiver, error) {
	cfg.defaults()
	if cfg.Cell == "" || cfg.SignKey == "" || cfg.Retention <= 0 {
		return nil, errors.New("archiver: thiếu cell, khoá ký hoặc hạn giữ")
	}
	if log == nil {
		log = slog.Default()
	}
	if m == nil {
		m = metrics.New()
	}
	a := &Archiver{cfg: cfg, store: store, signer: signer, log: log, m: m, now: time.Now, parts: map[tp]*partState{}}
	m.Describe(MRecords, "counter", "Số bản ghi kiểm toán đã lưu vào kho bất biến")
	m.Describe(MFiles, "counter", "Số tệp Parquet đã lưu")
	m.Describe(MErrors, "counter", "Số lỗi theo giai đoạn")
	m.Describe(MLag, "gauge", "Tuổi (giây) của bản ghi cũ nhất chưa vào kho bất biến, theo phân vùng")
	m.Describe(MBatchLag, "gauge", "Độ trễ (giây) từ bản ghi đầu lô tới lúc lô cuối khoá xong")
	m.Describe(MLastFlush, "gauge", "Thời điểm Unix lô cuối được lưu")
	m.Describe(MBroken, "gauge", "1 khi phân vùng bị dừng vì chuỗi hoặc kho không nhất quán")
	m.Describe(MAssigned, "gauge", "Số phân vùng đang giữ")
	m.OnCollect(a.collect)
	opts := []kgo.Opt{
		kgo.SeedBrokers(cfg.Brokers...),
		kgo.ConsumerGroup(cfg.Group),
		kgo.ConsumeTopics(cfg.Topics...),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.FetchIsolationLevel(kgo.ReadCommitted()),
		kgo.AdjustFetchOffsetsFn(a.adjust),
		kgo.OnPartitionsRevoked(a.drop),
		kgo.OnPartitionsLost(a.drop),
	}
	cl, err := kgo.NewClient(append(opts, cfg.KafkaOpts...)...)
	if err != nil {
		return nil, fmt.Errorf("archiver: tạo client Kafka: %w", err)
	}
	a.cl, a.adm = cl, kadm.NewClient(cl)
	return a, nil
}

// Close rời nhóm. Lô đang gom chưa ghi bị bỏ, phân vùng sẽ được đọc lại từ kê khai cuối trong kho.
func (a *Archiver) Close() { a.cl.Close() }

func (a *Archiver) params() archive.Params {
	return archive.Params{Cell: a.cfg.Cell, SignKey: a.cfg.SignKey, Retention: a.cfg.Retention}
}

func (a *Archiver) collect(m *metrics.Registry) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	m.Set(MAssigned, float64(len(a.parts)))
	for k, st := range a.parts {
		labels := []string{"topic", k.topic, "phan_vung", fmt.Sprint(k.partition)}
		lag := 0.0
		if len(st.rows) > 0 {
			lag = now.Sub(st.firstTs).Seconds()
		}
		m.Set(MLag, lag, labels...)
		b := 0.0
		if st.broken != "" {
			b = 1
		}
		m.Set(MBroken, b, labels...)
	}
}

func (a *Archiver) fail(stage, topic string, err error) {
	a.m.Add(MErrors, 1, "giai_doan", stage, "topic", topic)
	a.log.Error("audit-archiver lỗi", "giai_doan", stage, "topic", topic, "loi", err.Error())
}

// drop bỏ trạng thái phân vùng bị thu hồi.
func (a *Archiver) drop(_ context.Context, _ *kgo.Client, revoked map[string][]int32) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for t, ps := range revoked {
		for _, p := range ps {
			delete(a.parts, tp{t, p})
			a.m.Set(MLag, 0, "topic", t, "phan_vung", fmt.Sprint(p))
		}
	}
}

// adjust chạy khi nhận phân vùng: khôi phục trạng thái từ kho và đặt vị trí đọc theo kê khai cuối.
func (a *Archiver) adjust(ctx context.Context, offsets map[string]map[int32]kgo.Offset) (map[string]map[int32]kgo.Offset, error) {
	committed, err := a.adm.FetchOffsets(ctx, a.cfg.Group)
	if err != nil {
		return nil, fmt.Errorf("archiver: đọc offset đã xác nhận: %w", err)
	}
	var topics []string
	for t := range offsets {
		topics = append(topics, t)
	}
	starts, err := a.adm.ListStartOffsets(ctx, topics...)
	if err != nil {
		return nil, fmt.Errorf("archiver: đọc offset đầu log: %w", err)
	}
	out := map[string]map[int32]kgo.Offset{}
	for t, ps := range offsets {
		out[t] = map[int32]kgo.Offset{}
		for p := range ps {
			at, md := int64(-1), ""
			if o, ok := committed.Lookup(t, p); ok && o.Err == nil && o.At >= 0 {
				at, md = o.At, o.Metadata
			}
			start := int64(0)
			if so, ok := starts.Lookup(t, p); ok && so.Err == nil {
				start = so.Offset
			}
			st := a.recover(ctx, t, p, at, md, start)
			a.mu.Lock()
			a.parts[tp{t, p}] = st
			a.mu.Unlock()
			if st.broken != "" {
				a.cl.PauseFetchPartitions(map[string][]int32{t: {p}})
			}
			out[t][p] = kgo.NewOffset().At(st.pos).WithEpoch(-1)
			a.log.Info("nhận phân vùng", "topic", t, "phan_vung", p, "vi_tri", st.pos, "ke_khai_cuoi", st.prev.Key, "dung", st.broken)
		}
	}
	return out, nil
}

// recover dựng trạng thái phân vùng từ kho, bắt đầu từ kê khai ghi trong metadata của offset đã xác nhận.
func (a *Archiver) recover(ctx context.Context, topic string, p int32, committed int64, mdKey string, logStart int64) *partState {
	st := &partState{}
	brk := func(format string, args ...any) *partState {
		st.broken = fmt.Sprintf(format, args...)
		a.fail("khoi_phuc", topic, errors.New(st.broken))
		return st
	}
	prefix := archive.Prefix(a.cfg.Cell, topic)
	startAfter := ""
	switch {
	case mdKey != "":
		if !strings.HasPrefix(mdKey, prefix) {
			return brk("metadata offset %q không thuộc kho của cell và topic này", mdKey)
		}
		b, err := a.store.Get(ctx, mdKey)
		if err != nil {
			return brk("kê khai đã xác nhận %s không đọc được: %v", mdKey, err)
		}
		m, err := archive.ParseManifest(b)
		if err != nil {
			return brk("kê khai đã xác nhận %s: %v", mdKey, err)
		}
		if err := a.complete(ctx, mdKey, b, m); err != nil {
			return brk("kê khai %s: %v", mdKey, err)
		}
		st.prev, st.pos = archive.Link{Key: mdKey, Sha256: archive.SHA256Hex(b)}, m.DenOffset
		if m.DenOffset != committed {
			st.commit = true
		}
		startAfter = mdKey
	case committed >= 0:
		a.log.Warn("offset đã xác nhận không có metadata kê khai, liệt kê toàn bộ kho của phân vùng", "topic", topic, "phan_vung", p)
	}
	// Các kê khai ghi sau kê khai đã xác nhận (hoặc toàn bộ khi chưa có).
	type found struct {
		key  string
		from int64
	}
	var later []found
	err := a.store.List(ctx, prefix, startAfter, func(key string) error {
		ki, ok := archive.ParseKey(key)
		if ok && ki.Kind == "json" && ki.Partition == p && (st.prev.Key == "" || ki.From >= st.pos) {
			later = append(later, found{key, ki.From})
		}
		return nil
	})
	if err != nil {
		return brk("liệt kê kho: %v", err)
	}
	sort.Slice(later, func(i, j int) bool { return later[i].from < later[j].from })
	for _, f := range later {
		b, err := a.store.Get(ctx, f.key)
		if err != nil {
			return brk("đọc %s: %v", f.key, err)
		}
		m, err := archive.ParseManifest(b)
		if err != nil {
			return brk("%s: %v", f.key, err)
		}
		if m.KeKhaiTruoc != st.prev.Key || m.KeKhaiTruocSha256 != st.prev.Sha256 || (st.prev.Key != "" && m.TuOffset != st.pos) {
			return brk("chuỗi kê khai đứt tại %s", f.key)
		}
		if err := a.complete(ctx, f.key, b, m); err != nil {
			return brk("kê khai %s: %v", f.key, err)
		}
		st.prev, st.pos, st.commit = archive.Link{Key: f.key, Sha256: archive.SHA256Hex(b)}, m.DenOffset, true
	}
	if st.prev.Key == "" {
		switch {
		case committed >= 0:
			st.pos = committed
		default:
			st.pos = logStart
		}
	}
	if st.pos < logStart {
		return brk("Kafka đã xoá offset %d..%d trước khi lưu trữ (offset đầu log %d)", st.pos, logStart-1, logStart)
	}
	return st
}

// complete bảo đảm kê khai đã lưu có chữ ký và tệp dữ liệu đã khoá; thiếu thì ký lại hoặc dựng lại từ Kafka.
func (a *Archiver) complete(ctx context.Context, key string, b []byte, m archive.Manifest) error {
	sigKey := archive.SigKeyFor(key)
	ok, err := a.store.Exists(ctx, sigKey)
	if err != nil {
		return err
	}
	if !ok {
		sig, err := a.signer.Sign(ctx, b)
		if err != nil {
			return fmt.Errorf("ký lại: %w", err)
		}
		if _, err := a.store.PutLocked(ctx, sigKey, []byte(sig), "text/plain"); err != nil {
			return err
		}
		a.log.Warn("đã bổ sung chữ ký thiếu", "ke_khai", key)
	}
	ok, err = a.store.Exists(ctx, m.TepDuLieu)
	if err != nil {
		return err
	}
	if !ok {
		rows, err := a.readRange(ctx, m.Topic, m.PhanVung, m.TuOffset, m.DenOffset)
		if err != nil {
			return fmt.Errorf("đọc lại dải offset từ Kafka: %w", err)
		}
		data, err := archive.EncodeParquet(rows)
		if err != nil {
			return err
		}
		if archive.SHA256Hex(data) != m.Sha256 || int64(len(rows)) != m.SoBanGhi {
			return errors.New("tệp dựng lại từ Kafka không khớp SHA-256 trong kê khai")
		}
		if _, err := a.store.PutLocked(ctx, m.TepDuLieu, data, "application/vnd.apache.parquet"); err != nil {
			return err
		}
		a.log.Warn("đã dựng lại tệp dữ liệu thiếu từ Kafka", "tep", m.TepDuLieu)
	}
	return a.store.CheckLocked(ctx, m.TepDuLieu, 0)
}

// readRange đọc các bản ghi [from, to) của một phân vùng bằng client riêng (không thuộc nhóm).
func (a *Archiver) readRange(ctx context.Context, topic string, p int32, from, to int64) ([]archive.Row, error) {
	opts := []kgo.Opt{
		kgo.SeedBrokers(a.cfg.Brokers...),
		kgo.ConsumePartitions(map[string]map[int32]kgo.Offset{topic: {p: kgo.NewOffset().At(from)}}),
		kgo.FetchIsolationLevel(kgo.ReadCommitted()),
	}
	// Chỉ mang tuỳ chọn kết nối (TLS, SASL); tuỳ chọn nhóm của client chính không áp cho client này.
	cl, err := kgo.NewClient(append(opts, a.connOpts()...)...)
	if err != nil {
		return nil, err
	}
	defer cl.Close()
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	var rows []archive.Row
	for {
		f := cl.PollFetches(ctx)
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var ferr error
		f.EachError(func(_ string, _ int32, err error) { ferr = err })
		if ferr != nil {
			return nil, ferr
		}
		done := false
		f.EachRecord(func(r *kgo.Record) {
			if r.Offset >= to {
				done = true
				return
			}
			if r.Offset >= from {
				rows = append(rows, toRow(r))
			}
			if r.Offset == to-1 {
				done = true
			}
		})
		if done {
			return rows, nil
		}
	}
}

// connOpts là tuỳ chọn kết nối dùng chung cho client phụ; KafkaOpts của Config chỉ được chứa tuỳ chọn kết nối.
func (a *Archiver) connOpts() []kgo.Opt { return a.cfg.KafkaOpts }

func toRow(r *kgo.Record) archive.Row {
	hs := make([]archive.Header, len(r.Headers))
	for i, h := range r.Headers {
		hs[i] = archive.Header{Key: h.Key, Value: h.Value}
	}
	return archive.Row{
		Topic:     r.Topic,
		Partition: r.Partition,
		Offset:    r.Offset,
		Timestamp: r.Timestamp.UTC().Truncate(time.Millisecond),
		Key:       r.Key,
		Value:     r.Value,
		Headers:   hs,
	}
}

// Run là vòng tiêu thụ; trả về khi ctx huỷ.
func (a *Archiver) Run(ctx context.Context) error {
	for ctx.Err() == nil {
		pctx, cancel := context.WithTimeout(ctx, a.cfg.Tick)
		fetches := a.cl.PollRecords(pctx, 10_000)
		cancel()
		if fetches.IsClientClosed() {
			return nil
		}
		fetches.EachError(func(t string, p int32, err error) {
			if !errors.Is(err, context.DeadlineExceeded) && !errors.Is(err, context.Canceled) {
				a.fail("doc_kafka", t, fmt.Errorf("phân vùng %d: %w", p, err))
			}
		})
		a.mu.Lock()
		fetches.EachRecord(func(r *kgo.Record) {
			st := a.parts[tp{r.Topic, r.Partition}]
			if st == nil || st.broken != "" {
				return
			}
			end := st.pos
			if n := len(st.rows); n > 0 {
				end = st.rows[n-1].Offset + 1
			}
			if r.Offset < end {
				return // đã có trong lô hoặc trong kho
			}
			if len(st.rows) == 0 {
				st.firstTs, st.firstSeen = r.Timestamp, a.now()
			}
			st.rows = append(st.rows, toRow(r))
			st.bytes += len(r.Key) + len(r.Value)
			for _, h := range r.Headers {
				st.bytes += len(h.Key) + len(h.Value)
			}
		})
		a.mu.Unlock()
		a.flushDue(ctx)
		a.cl.AllowRebalance()
	}
	return nil
}

func (a *Archiver) due(st *partState, now time.Time) bool {
	if len(st.rows) == 0 || st.broken != "" {
		return false
	}
	return st.pending != nil || len(st.rows) >= a.cfg.MaxRecords || st.bytes >= a.cfg.MaxBytes ||
		now.Sub(st.firstTs) >= a.cfg.Window || now.Sub(st.firstSeen) >= a.cfg.Window
}

// flushDue ghi các lô đến hạn và xác nhận lại offset còn treo.
func (a *Archiver) flushDue(ctx context.Context) {
	a.mu.Lock()
	keys := make([]tp, 0, len(a.parts))
	for k := range a.parts {
		keys = append(keys, k)
	}
	a.mu.Unlock()
	for _, k := range keys {
		a.mu.Lock()
		st := a.parts[k]
		a.mu.Unlock()
		if st == nil {
			continue
		}
		if a.due(st, a.now()) {
			if err := a.flush(ctx, k, st); err != nil {
				var c *conflictError
				if errors.As(err, &c) {
					st.broken = err.Error()
					a.cl.PauseFetchPartitions(map[string][]int32{k.topic: {k.partition}})
				}
				a.fail("ghi_lo", k.topic, err)
				continue
			}
		}
		if st.commit {
			if err := a.commitPos(ctx, k, st.pos, st.prev.Key); err != nil {
				a.fail("xac_nhan_offset", k.topic, err)
			} else {
				st.commit = false
			}
		}
	}
}

type conflictError struct{ err error }

func (e *conflictError) Error() string { return e.err.Error() }
func (e *conflictError) Unwrap() error { return e.err }

// flush ghi một lô. Lô đã dựng và ký được giữ nguyên giữa các lần thử lại để kê khai đã ghi không bao giờ khác bản
// thử lại.
func (a *Archiver) flush(ctx context.Context, k tp, st *partState) error {
	if st.pending == nil {
		n := len(st.rows)
		if n > a.cfg.MaxRecords {
			n = a.cfg.MaxRecords
		}
		b, err := archive.Build(a.params(), st.rows[:n], st.pos, st.prev, a.now())
		if err != nil {
			return err
		}
		sig, err := a.signer.Sign(ctx, b.ManifestBytes)
		if err != nil {
			return fmt.Errorf("ký kê khai: %w", err)
		}
		st.pending = &pending{batch: b, sig: sig, n: n}
	}
	b := st.pending.batch
	put := func(key string, data []byte, ct string) error {
		_, err := a.store.PutLocked(ctx, key, data, ct)
		if errors.Is(err, objstore.ErrConflict) {
			return &conflictError{err}
		}
		return err
	}
	if err := put(b.ManifestKey, b.ManifestBytes, "application/json"); err != nil {
		return err
	}
	if err := put(b.SigKey(), []byte(st.pending.sig), "text/plain"); err != nil {
		return err
	}
	if err := put(b.DataKey, b.Data, "application/vnd.apache.parquet"); err != nil {
		return err
	}
	// Hạn giữ còn lại phải gần trọn hạn đã đặt (trừ sai lệch đồng hồ); hạn ngắn chỉ có ở kiểm thử.
	minLeft := a.cfg.Retention - 24*time.Hour
	if minLeft < a.cfg.Retention/2 {
		minLeft = a.cfg.Retention / 2
	}
	if err := a.store.CheckLocked(ctx, b.DataKey, minLeft); err != nil {
		return err
	}
	now := a.now()
	a.mu.Lock()
	st.rows = append([]archive.Row(nil), st.rows[st.pending.n:]...)
	st.bytes = 0
	for _, r := range st.rows {
		st.bytes += len(r.Key) + len(r.Value)
	}
	if len(st.rows) > 0 {
		st.firstTs, st.firstSeen = st.rows[0].Timestamp, now
	}
	st.prev = archive.Link{Key: b.ManifestKey, Sha256: archive.SHA256Hex(b.ManifestBytes)}
	st.pos = b.Manifest.DenOffset
	st.pending = nil
	st.commit = true
	st.lastFlush = now
	st.lastLag = now.Sub(b.Manifest.ThoiDiemDau)
	a.mu.Unlock()
	labels := []string{"topic", k.topic, "phan_vung", fmt.Sprint(k.partition)}
	a.m.Add(MRecords, float64(b.Manifest.SoBanGhi), "topic", k.topic)
	a.m.Add(MFiles, 1, "topic", k.topic)
	a.m.Set(MBatchLag, st.lastLag.Seconds(), labels...)
	a.m.Set(MLastFlush, float64(now.Unix()), labels...)
	a.log.Info("đã lưu lô kiểm toán", "tep", b.DataKey, "so_ban_ghi", b.Manifest.SoBanGhi, "tu", b.Manifest.TuOffset, "den", b.Manifest.DenOffset, "do_tre_giay", int(st.lastLag.Seconds()))
	return nil
}

// commitPos xác nhận offset kèm metadata là khoá kê khai cuối.
func (a *Archiver) commitPos(ctx context.Context, k tp, pos int64, key string) error {
	cctx := kgo.PreCommitFnContext(ctx, func(req *kmsg.OffsetCommitRequest) error {
		for i := range req.Topics {
			if req.Topics[i].Topic != k.topic {
				continue
			}
			for j := range req.Topics[i].Partitions {
				if req.Topics[i].Partitions[j].Partition == k.partition {
					md := key
					req.Topics[i].Partitions[j].Metadata = &md
				}
			}
		}
		return nil
	})
	var cerr error
	a.cl.CommitOffsetsSync(cctx, map[string]map[int32]kgo.EpochOffset{k.topic: {k.partition: {Epoch: -1, Offset: pos}}},
		func(_ *kgo.Client, _ *kmsg.OffsetCommitRequest, resp *kmsg.OffsetCommitResponse, err error) {
			if err != nil {
				cerr = err
				return
			}
			for _, t := range resp.Topics {
				for _, p := range t.Partitions {
					if e := kerrOf(p.ErrorCode); e != nil {
						cerr = e
					}
				}
			}
		})
	return cerr
}

func kerrOf(code int16) error {
	if code == 0 {
		return nil
	}
	return fmt.Errorf("mã lỗi Kafka %d", code)
}

// Status là trạng thái cho /readyz và kiểm thử.
type Status struct {
	Assigned int
	Broken   []string
	Buffered int
}

// Status đọc trạng thái hiện tại.
func (a *Archiver) Status() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := Status{Assigned: len(a.parts)}
	for k, st := range a.parts {
		s.Buffered += len(st.rows)
		if st.broken != "" {
			s.Broken = append(s.Broken, fmt.Sprintf("%s/%d: %s", k.topic, k.partition, st.broken))
		}
	}
	return s
}
