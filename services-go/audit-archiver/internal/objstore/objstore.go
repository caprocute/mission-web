// Package objstore ghi và đọc đối tượng trên bucket Object Lock của kho sao lưu (Ceph RGW, INF-12, INF-17; dev
// dùng RGW hoặc MinIO có Object Lock). Mỗi lần ghi đặt hạn giữ riêng cho đối tượng ở chế độ cấu hình (COMPLIANCE ở
// production), kèm Content-MD5 vì S3 bắt buộc khi ghi có khoá. Ghi không bao giờ đè: đối tượng đã có thì so byte,
// trùng thì coi như đã ghi (chạy lại sau sự cố), khác thì trả ErrConflict để dịch vụ dừng phân vùng và cảnh báo.
//
// Tài khoản S3 của audit-archiver chỉ có PutObject, PutObjectRetention, GetObject, GetObjectRetention, ListBucket;
// không có DeleteObject, BypassGovernanceRetention hay quyền đổi cấu hình Object Lock (chart, runbook).
package objstore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// ErrNotFound báo đối tượng không có.
var ErrNotFound = errors.New("objstore: không có đối tượng")

// ErrConflict báo khoá đã có đối tượng với nội dung khác.
var ErrConflict = errors.New("objstore: khoá đã có đối tượng khác nội dung")

// Config là cấu hình kết nối kho.
type Config struct {
	Endpoint  string // host:port
	Secure    bool
	Region    string
	Bucket    string
	AccessKey string
	SecretKey string
	// Mode là COMPLIANCE hoặc GOVERNANCE; Retention là hạn giữ mỗi đối tượng (mặc định 10 năm theo TL-04).
	Mode      string
	Retention time.Duration
	Transport http.RoundTripper
}

// Store là bucket kho sao lưu.
type Store struct {
	c         *minio.Client
	bucket    string
	mode      minio.RetentionMode
	retention time.Duration
	now       func() time.Time
}

// New tạo Store; không gọi mạng.
func New(cfg Config) (*Store, error) {
	mode := minio.RetentionMode(cfg.Mode)
	if mode == "" {
		mode = minio.Compliance
	}
	if !mode.IsValid() {
		return nil, fmt.Errorf("objstore: chế độ khoá %q không hợp lệ", cfg.Mode)
	}
	if cfg.Retention <= 0 {
		return nil, errors.New("objstore: hạn giữ phải dương")
	}
	c, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:     credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure:    cfg.Secure,
		Region:    cfg.Region,
		Transport: cfg.Transport,
	})
	if err != nil {
		return nil, fmt.Errorf("objstore: tạo client: %w", err)
	}
	return &Store{c: c, bucket: cfg.Bucket, mode: mode, retention: cfg.Retention, now: time.Now}, nil
}

// Client trả client S3 bên dưới (kiểm thử).
func (s *Store) Client() *minio.Client { return s.c }

// Bucket là tên bucket.
func (s *Store) Bucket() string { return s.bucket }

// CheckBucket kiểm bucket tồn tại và bật Object Lock; gọi khi khởi động và ở /readyz.
func (s *Store) CheckBucket(ctx context.Context) error {
	enabled, _, _, _, err := s.c.GetObjectLockConfig(ctx, s.bucket)
	if err != nil {
		return fmt.Errorf("objstore: đọc cấu hình Object Lock của bucket: %w", err)
	}
	if enabled != "Enabled" {
		return errors.New("objstore: bucket chưa bật Object Lock")
	}
	return nil
}

func isNotFound(err error) bool {
	r := minio.ToErrorResponse(err)
	return r.StatusCode == http.StatusNotFound || r.Code == "NoSuchKey" || r.Code == "NotFound"
}

// Get đọc toàn bộ đối tượng (bản mới nhất).
func (s *Store) Get(ctx context.Context, key string) ([]byte, error) {
	o, err := s.c.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		if isNotFound(err) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	defer o.Close()
	b, err := io.ReadAll(o)
	if err != nil {
		if isNotFound(err) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return b, nil
}

// Exists kiểm đối tượng có hay không.
func (s *Store) Exists(ctx context.Context, key string) (bool, error) {
	_, err := s.c.StatObject(ctx, s.bucket, key, minio.StatObjectOptions{})
	if err == nil {
		return true, nil
	}
	if isNotFound(err) {
		return false, nil
	}
	return false, err
}

// PutLocked ghi đối tượng mới với hạn giữ; created=false khi đối tượng đã có với đúng nội dung.
func (s *Store) PutLocked(ctx context.Context, key string, data []byte, contentType string) (created bool, err error) {
	if ok, err := s.sameAsExisting(ctx, key, data); err != nil || ok {
		return false, err
	}
	opts := minio.PutObjectOptions{
		ContentType:      contentType,
		Mode:             s.mode,
		RetainUntilDate:  s.now().UTC().Add(s.retention).Truncate(time.Second),
		SendContentMd5:   true,
		DisableMultipart: true,
	}
	// If-None-Match: * chặn hai tiến trình cùng ghi một khoá (tiến trình cũ còn sót sau phân vùng lại).
	opts.SetMatchETagExcept("*")
	_, err = s.c.PutObject(ctx, s.bucket, key, bytes.NewReader(data), int64(len(data)), opts)
	if err != nil {
		r := minio.ToErrorResponse(err)
		if r.StatusCode == http.StatusPreconditionFailed || r.Code == "PreconditionFailed" {
			if ok, err2 := s.sameAsExisting(ctx, key, data); err2 != nil || ok {
				return false, err2
			}
		}
		return false, fmt.Errorf("objstore: ghi %s: %w", key, err)
	}
	return true, nil
}

// sameAsExisting trả true khi khoá đã có đúng các byte này, ErrConflict khi có nội dung khác.
func (s *Store) sameAsExisting(ctx context.Context, key string, data []byte) (bool, error) {
	cur, err := s.Get(ctx, key)
	if errors.Is(err, ErrNotFound) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if bytes.Equal(cur, data) {
		return true, nil
	}
	return false, fmt.Errorf("%w: %s", ErrConflict, key)
}

// CheckLocked kiểm đối tượng đang bị khoá đúng chế độ, hạn giữ còn ít nhất minLeft.
func (s *Store) CheckLocked(ctx context.Context, key string, minLeft time.Duration) error {
	mode, until, err := s.c.GetObjectRetention(ctx, s.bucket, key, "")
	if err != nil {
		return fmt.Errorf("objstore: đọc hạn giữ %s: %w", key, err)
	}
	if mode == nil || *mode != s.mode {
		return fmt.Errorf("objstore: %s không ở chế độ khoá %s", key, s.mode)
	}
	if until == nil || until.Before(s.now().Add(minLeft)) {
		return fmt.Errorf("objstore: %s có hạn giữ ngắn hơn quy định", key)
	}
	return nil
}

// List liệt kê khoá dưới prefix theo thứ tự từ điển, bắt đầu sau startAfter (rỗng là từ đầu). fn trả lỗi thì dừng.
func (s *Store) List(ctx context.Context, prefix, startAfter string, fn func(key string) error) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	for o := range s.c.ListObjects(ctx, s.bucket, minio.ListObjectsOptions{Prefix: prefix, StartAfter: startAfter, Recursive: true}) {
		if o.Err != nil {
			return fmt.Errorf("objstore: liệt kê %s: %w", prefix, o.Err)
		}
		if err := fn(o.Key); err != nil {
			return err
		}
	}
	return nil
}
