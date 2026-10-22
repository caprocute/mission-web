package move

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ObjectSync là cổng đồng bộ đối tượng của tenant (khoá t/<tenant>/..., TL-03 "Kho đối tượng Ceph") giữa kho
// của cell nguồn và cell đích.
type ObjectSync interface {
	// Sync sao chép đối tượng còn thiếu hoặc khác ETag ở đích; trả số đối tượng đã chép.
	Sync(ctx context.Context, from, to string, tenantID uuid.UUID) (int, error)
	// Verify so khớp danh sách đối tượng (khoá, kích thước, ETag) hai bên.
	Verify(ctx context.Context, from, to string, tenantID uuid.UUID) (ObjectReport, error)
	// Purge xoá đối tượng của tenant ở kho nguồn sau thời hạn giữ (bucket không khoá Object Lock).
	Purge(ctx context.Context, cell string, tenantID uuid.UUID) (int, error)
}

// ObjectReport là kết quả so khớp theo bucket.
type ObjectReport struct {
	Buckets []BucketReport `json:"buckets"`
	OK      bool           `json:"ok"`
}

// BucketReport là kết quả một bucket.
type BucketReport struct {
	Bucket  string `json:"bucket"`
	Source  int    `json:"source"`
	Target  int    `json:"target"`
	Missing int    `json:"missing"`
}

// S3Endpoint là kho S3 (Ceph RGW) của một cell.
type S3Endpoint struct {
	URL       string `json:"url"` // ví dụ https://rgw.cell-g1.svc:8443 (kiểu đường dẫn)
	Region    string `json:"region"`
	AccessKey string `json:"accessKey"`
	SecretKey string `json:"secretKey"`
}

// BucketMode: "copy" (tenant-registry chép đối tượng) hoặc "verify" (đối tượng mã hoá SSE-C như evidence do
// đồng bộ multisite của Ceph RGW chép, tenant-registry chỉ so khớp; INF-12).
type BucketMode struct {
	Name string `json:"name"`
	Mode string `json:"mode"`
	// Locked: bucket bật Object Lock compliance (TL-03), không xoá đối tượng khi dọn; vòng đời Ceph xử lý.
	Locked bool `json:"locked"`
}

// S3Sync hiện thực ObjectSync bằng API S3 (ListObjectsV2, GetObject, PutObject, DeleteObject) ký AWS SigV4.
type S3Sync struct {
	Cells   map[string]S3Endpoint
	Buckets []BucketMode
	HTTP    *http.Client
	Now     func() time.Time
}

// DefaultBuckets theo TL-03 "Tổ chức bucket" (chỉ bucket mang dữ liệu lâu dài của tenant).
func DefaultBuckets() []BucketMode {
	return []BucketMode{{"evidence", "verify", true}, {"dossier", "copy", true}, {"templates", "copy", false}, {"ledger-archive", "copy", true}}
}

type s3Object struct {
	Key  string `xml:"Key"`
	ETag string `xml:"ETag"`
	Size int64  `xml:"Size"`
}

func (s *S3Sync) client() *http.Client {
	if s.HTTP != nil {
		return s.HTTP
	}
	return &http.Client{Timeout: 5 * time.Minute}
}

func (s *S3Sync) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *S3Sync) endpoint(cell string) (S3Endpoint, error) {
	e, ok := s.Cells[cell]
	if !ok || e.URL == "" {
		return e, fmt.Errorf("move: chưa cấu hình kho đối tượng của cell %s", cell)
	}
	if e.Region == "" {
		e.Region = "default"
	}
	return e, nil
}

func prefix(tenantID uuid.UUID) string { return "t/" + tenantID.String() + "/" }

// list liệt kê đối tượng theo tiền tố (phân trang continuation-token).
func (s *S3Sync) list(ctx context.Context, e S3Endpoint, bucket, pfx string) (map[string]s3Object, error) {
	out := map[string]s3Object{}
	token := ""
	for {
		q := url.Values{"list-type": {"2"}, "prefix": {pfx}, "max-keys": {"1000"}}
		if token != "" {
			q.Set("continuation-token", token)
		}
		resp, err := s.do(ctx, e, http.MethodGet, "/"+bucket+"/", q, nil, -1, "")
		if err != nil {
			return nil, err
		}
		var r struct {
			Contents              []s3Object `xml:"Contents"`
			IsTruncated           bool       `xml:"IsTruncated"`
			NextContinuationToken string     `xml:"NextContinuationToken"`
		}
		err = xml.NewDecoder(io.LimitReader(resp.Body, 64<<20)).Decode(&r)
		resp.Body.Close()
		if err != nil {
			return nil, err
		}
		for _, o := range r.Contents {
			out[o.Key] = o
		}
		if !r.IsTruncated || r.NextContinuationToken == "" {
			return out, nil
		}
		token = r.NextContinuationToken
	}
}

// Sync hiện thực ObjectSync.Sync cho bucket chế độ copy.
func (s *S3Sync) Sync(ctx context.Context, from, to string, tenantID uuid.UUID) (int, error) {
	src, err := s.endpoint(from)
	if err != nil {
		return 0, err
	}
	dst, err := s.endpoint(to)
	if err != nil {
		return 0, err
	}
	n := 0
	for _, b := range s.Buckets {
		if b.Mode != "copy" {
			continue
		}
		so, err := s.list(ctx, src, b.Name, prefix(tenantID))
		if err != nil {
			return n, err
		}
		do, err := s.list(ctx, dst, b.Name, prefix(tenantID))
		if err != nil {
			return n, err
		}
		keys := make([]string, 0, len(so))
		for k := range so {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			o := so[k]
			if d, ok := do[k]; ok && d.ETag == o.ETag && d.Size == o.Size {
				continue
			}
			if err := s.copyObject(ctx, src, dst, b.Name, o); err != nil {
				return n, err
			}
			n++
		}
	}
	return n, nil
}

func objectPath(bucket, key string) string {
	segs := strings.Split(key, "/")
	for i, sg := range segs {
		segs[i] = url.PathEscape(sg)
	}
	return "/" + bucket + "/" + strings.Join(segs, "/")
}

func (s *S3Sync) copyObject(ctx context.Context, src, dst S3Endpoint, bucket string, o s3Object) error {
	resp, err := s.do(ctx, src, http.MethodGet, objectPath(bucket, o.Key), nil, nil, -1, "")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	ct := resp.Header.Get("Content-Type")
	pr, err := s.do(ctx, dst, http.MethodPut, objectPath(bucket, o.Key), nil, resp.Body, resp.ContentLength, ct)
	if err != nil {
		return err
	}
	pr.Body.Close()
	return nil
}

// Verify hiện thực ObjectSync.Verify cho mọi bucket.
func (s *S3Sync) Verify(ctx context.Context, from, to string, tenantID uuid.UUID) (ObjectReport, error) {
	rep := ObjectReport{OK: true}
	src, err := s.endpoint(from)
	if err != nil {
		return rep, err
	}
	dst, err := s.endpoint(to)
	if err != nil {
		return rep, err
	}
	for _, b := range s.Buckets {
		so, err := s.list(ctx, src, b.Name, prefix(tenantID))
		if err != nil {
			return rep, err
		}
		do, err := s.list(ctx, dst, b.Name, prefix(tenantID))
		if err != nil {
			return rep, err
		}
		br := BucketReport{Bucket: b.Name, Source: len(so), Target: len(do)}
		for k, o := range so {
			if d, ok := do[k]; !ok || d.Size != o.Size || d.ETag != o.ETag {
				br.Missing++
			}
		}
		if br.Missing > 0 {
			rep.OK = false
		}
		rep.Buckets = append(rep.Buckets, br)
	}
	return rep, nil
}

// Purge xoá đối tượng của tenant ở bucket chép được và không khoá Object Lock (bucket khoá do vòng đời Ceph xử lý).
func (s *S3Sync) Purge(ctx context.Context, cell string, tenantID uuid.UUID) (int, error) {
	e, err := s.endpoint(cell)
	if err != nil {
		return 0, err
	}
	n := 0
	for _, b := range s.Buckets {
		if b.Mode != "copy" || b.Locked {
			continue
		}
		objs, err := s.list(ctx, e, b.Name, prefix(tenantID))
		if err != nil {
			return n, err
		}
		for k := range objs {
			resp, err := s.do(ctx, e, http.MethodDelete, objectPath(b.Name, k), nil, nil, -1, "")
			if err != nil {
				return n, err
			}
			resp.Body.Close()
			n++
		}
	}
	return n, nil
}

// do gửi một yêu cầu S3 ký SigV4 (thân không ký: UNSIGNED-PAYLOAD, phù hợp truyền luồng qua TLS).
func (s *S3Sync) do(ctx context.Context, e S3Endpoint, method, p string, q url.Values, body io.Reader, size int64, ctype string) (*http.Response, error) {
	base, err := url.Parse(strings.TrimRight(e.URL, "/"))
	if err != nil {
		return nil, err
	}
	u := *base
	u.Path = base.Path + p
	u.RawPath = base.EscapedPath() + p
	u.RawQuery = canonicalQuery(q)
	req, err := http.NewRequestWithContext(ctx, method, u.String(), body)
	if err != nil {
		return nil, err
	}
	if size >= 0 {
		req.ContentLength = size
	}
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	SignV4(req, e.AccessKey, e.SecretKey, e.Region, "s3", s.now())
	resp, err := s.client().Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		resp.Body.Close()
		return nil, fmt.Errorf("s3 %s %s: HTTP %d %s", method, p, resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return resp, nil
}

// ---------------------------------------------------------------- AWS Signature Version 4

func canonicalQuery(q url.Values) string {
	if len(q) == 0 {
		return ""
	}
	keys := make([]string, 0, len(q))
	for k := range q {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var parts []string
	for _, k := range keys {
		vs := append([]string(nil), q[k]...)
		sort.Strings(vs)
		for _, v := range vs {
			parts = append(parts, awsEscape(k)+"="+awsEscape(v))
		}
	}
	return strings.Join(parts, "&")
}

// awsEscape mã hoá theo RFC 3986 như SigV4 yêu cầu (không đổi A-Z a-z 0-9 - _ . ~).
func awsEscape(s string) string {
	var b strings.Builder
	for _, c := range []byte(s) {
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

func hmacSHA256(key []byte, data string) []byte {
	h := hmac.New(sha256.New, key)
	h.Write([]byte(data))
	return h.Sum(nil)
}

// SignV4 ký yêu cầu theo AWS Signature Version 4 với thân UNSIGNED-PAYLOAD.
func SignV4(req *http.Request, accessKey, secretKey, region, service string, now time.Time) {
	SignV4Payload(req, accessKey, secretKey, region, service, now, "UNSIGNED-PAYLOAD")
}

// SignV4Payload ký yêu cầu với giá trị x-amz-content-sha256 cho trước; ký host, content-type, range và mọi
// header x-amz-*.
func SignV4Payload(req *http.Request, accessKey, secretKey, region, service string, now time.Time, payloadHash string) {
	t := now.UTC()
	amzDate := t.Format("20060102T150405Z")
	day := t.Format("20060102")
	req.Header.Set("X-Amz-Date", amzDate)
	req.Header.Set("X-Amz-Content-Sha256", payloadHash)
	headers := map[string]string{"host": req.URL.Host}
	for k, vs := range req.Header {
		lk := strings.ToLower(k)
		if lk == "content-type" || lk == "range" || strings.HasPrefix(lk, "x-amz-") {
			headers[lk] = strings.Join(vs, ",")
		}
	}
	names := make([]string, 0, len(headers))
	for k := range headers {
		names = append(names, k)
	}
	sort.Strings(names)
	var canonHeaders strings.Builder
	for _, k := range names {
		canonHeaders.WriteString(k + ":" + strings.TrimSpace(headers[k]) + "\n")
	}
	signed := strings.Join(names, ";")
	cpath := req.URL.EscapedPath()
	if cpath == "" {
		cpath = "/"
	}
	canonical := strings.Join([]string{req.Method, cpath, req.URL.RawQuery, canonHeaders.String(), signed, payloadHash}, "\n")
	sum := sha256.Sum256([]byte(canonical))
	scope := day + "/" + region + "/" + service + "/aws4_request"
	toSign := "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex.EncodeToString(sum[:])
	k := hmacSHA256([]byte("AWS4"+secretKey), day)
	k = hmacSHA256(k, region)
	k = hmacSHA256(k, service)
	k = hmacSHA256(k, "aws4_request")
	sig := hex.EncodeToString(hmacSHA256(k, toSign))
	req.Header.Set("Authorization", "AWS4-HMAC-SHA256 Credential="+accessKey+"/"+scope+", SignedHeaders="+signed+", Signature="+sig)
}
