package provision

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// ---------------------------------------------------------------- PowerDNS (OPS-07, TL-04 "GeoDNS")

// PowerDNS hiện thực cổng DNS bằng HTTP API của PowerDNS Authoritative: bản ghi tenant là CNAME tới điểm vào
// cell (TTL 300 như ví dụ của TL-04). Tên miền ngoài vùng do nền tảng quản lý (tên miền riêng của khách hàng)
// không tạo được bản ghi; khi đó chỉ kiểm khách hàng đã trỏ CNAME đúng bằng phân giải DNS công khai.
type PowerDNS struct {
	BaseURL  string // ví dụ http://pdns-api.dns.svc:8081
	ServerID string // mặc định localhost
	Zone     string // vùng do nền tảng quản lý, ví dụ okr.viettel.vn
	APIKey   string // đọc từ OpenBao qua External Secrets
	TTL      int
	HTTP     *http.Client
	// Resolver dùng cho tên miền ngoài vùng; nil = net.DefaultResolver.
	Resolver interface {
		LookupCNAME(ctx context.Context, host string) (string, error)
	}
}

const sysDNS = "powerdns"

func fqdn(s string) string { return strings.TrimSuffix(strings.ToLower(s), ".") + "." }

// managed: tên thuộc vùng do nền tảng quản lý.
func (p *PowerDNS) managed(name string) bool {
	z := strings.TrimSuffix(strings.ToLower(p.Zone), ".")
	n := strings.TrimSuffix(strings.ToLower(name), ".")
	return z != "" && (n == z || strings.HasSuffix(n, "."+z))
}

func (p *PowerDNS) zoneURL() string {
	sid := p.ServerID
	if sid == "" {
		sid = "localhost"
	}
	return strings.TrimRight(p.BaseURL, "/") + "/api/v1/servers/" + url.PathEscape(sid) + "/zones/" + url.PathEscape(fqdn(p.Zone))
}

func (p *PowerDNS) patch(ctx context.Context, rrset map[string]any) error {
	_, err := do(ctx, p.HTTP, call{system: sysDNS, method: http.MethodPatch, url: p.zoneURL(),
		body: map[string]any{"rrsets": []any{rrset}}, headers: map[string]string{"X-API-Key": p.APIKey}})
	return err
}

// EnsureCNAME đặt (REPLACE) bản ghi CNAME; idempotent.
func (p *PowerDNS) EnsureCNAME(ctx context.Context, name, target string) error {
	if !p.managed(name) {
		got, err := p.LookupCNAME(ctx, name)
		if err != nil {
			return Transient("Tên miền "+name+" chưa trỏ về điểm vào của cell", err)
		}
		if fqdn(got) != fqdn(target) {
			return Transient("Tên miền "+name+" chưa trỏ về điểm vào của cell", errors.New("CNAME hiện tại: "+got))
		}
		return nil
	}
	ttl := p.TTL
	if ttl == 0 {
		ttl = 300
	}
	return p.patch(ctx, map[string]any{"name": fqdn(name), "type": "CNAME", "ttl": ttl, "changetype": "REPLACE",
		"records": []map[string]any{{"content": fqdn(target), "disabled": false}}})
}

// RemoveCNAME xoá bản ghi (bù trừ "thu hồi tên miền"); tên ngoài vùng không có gì để xoá.
func (p *PowerDNS) RemoveCNAME(ctx context.Context, name string) error {
	if !p.managed(name) {
		return nil
	}
	return p.patch(ctx, map[string]any{"name": fqdn(name), "type": "CNAME", "changetype": "DELETE"})
}

// LookupCNAME đọc đích CNAME hiện tại.
func (p *PowerDNS) LookupCNAME(ctx context.Context, name string) (string, error) {
	if !p.managed(name) {
		var r interface {
			LookupCNAME(ctx context.Context, host string) (string, error)
		} = net.DefaultResolver
		if p.Resolver != nil {
			r = p.Resolver
		}
		c, err := r.LookupCNAME(ctx, name)
		if err != nil {
			return "", err
		}
		return strings.TrimSuffix(c, "."), nil
	}
	q := url.Values{"rrsets": {"true"}, "rrset_name": {fqdn(name)}, "rrset_type": {"CNAME"}}
	res, err := do(ctx, p.HTTP, call{system: sysDNS, method: http.MethodGet, url: p.zoneURL() + "?" + q.Encode(),
		headers: map[string]string{"X-API-Key": p.APIKey}})
	if err != nil {
		return "", err
	}
	var z struct {
		RRsets []struct {
			Name    string `json:"name"`
			Type    string `json:"type"`
			Records []struct {
				Content  string `json:"content"`
				Disabled bool   `json:"disabled"`
			} `json:"records"`
		} `json:"rrsets"`
	}
	if err := decode(res, &z); err != nil {
		return "", err
	}
	for _, rr := range z.RRsets {
		if rr.Type == "CNAME" && fqdn(rr.Name) == fqdn(name) {
			for _, rec := range rr.Records {
				if !rec.Disabled {
					return strings.TrimSuffix(rec.Content, "."), nil
				}
			}
		}
	}
	return "", nil
}

// ---------------------------------------------------------------- cert-manager

// CertManager hiện thực cổng Certificates bằng tài nguyên Certificate (cert-manager.io/v1) qua API Kubernetes,
// với ServiceAccount có Role hẹp chỉ trên certificates và secrets của namespace cổng vào (chart
// deploy/charts/tenant-registry). tls_cert_ref có dạng k8s:<namespace>/<secret> (suy luận định dạng).
type CertManager struct {
	APIServer  string // ví dụ https://kubernetes.default.svc
	Namespace  string // namespace của Secret TLS mà APISIX đọc
	IssuerName string
	IssuerKind string // ClusterIssuer (mặc định) hoặc Issuer
	// Token trả bearer token của ServiceAccount (đọc lại mỗi lần vì token chiếu có hạn).
	Token     func() (string, error)
	HTTP      *http.Client
	PollEvery time.Duration
	Wait      time.Duration // thời gian chờ chứng chỉ sẵn sàng trong một lần thử
}

const sysCert = "cert-manager"

// TokenFile đọc token ServiceAccount từ tệp chiếu.
func TokenFile(path string) func() (string, error) {
	return func() (string, error) {
		b, err := os.ReadFile(path)
		return strings.TrimSpace(string(b)), err
	}
}

func (c *CertManager) req(ctx context.Context, method, path string, body any, ok ...int) (*result, error) {
	tok := ""
	if c.Token != nil {
		t, err := c.Token()
		if err != nil {
			return nil, err
		}
		tok = t
	}
	return do(ctx, c.HTTP, call{system: sysCert, method: method, url: strings.TrimRight(c.APIServer, "/") + path, body: body, token: tok, ok: ok})
}

func (c *CertManager) certPath(name string) string {
	return "/apis/cert-manager.io/v1/namespaces/" + url.PathEscape(c.Namespace) + "/certificates/" + url.PathEscape(name)
}

// CertRef dựng tham chiếu chứng chỉ.
func CertRef(namespace, secret string) string { return "k8s:" + namespace + "/" + secret }

func parseRef(ref string) (ns, name string, ok bool) {
	r, found := strings.CutPrefix(ref, "k8s:")
	if !found {
		return "", "", false
	}
	ns, name, ok = strings.Cut(r, "/")
	return ns, name, ok && ns != "" && name != ""
}

// Ref hiện thực Certificates.Ref.
func (c *CertManager) Ref(name string) string { return CertRef(c.Namespace, name) }

// Issue tạo Certificate (đã có thì dùng lại) rồi chờ điều kiện Ready.
func (c *CertManager) Issue(ctx context.Context, name string, dnsNames []string) (string, error) {
	kind := c.IssuerKind
	if kind == "" {
		kind = "ClusterIssuer"
	}
	cert := map[string]any{
		"apiVersion": "cert-manager.io/v1", "kind": "Certificate",
		"metadata": map[string]any{"name": name, "namespace": c.Namespace,
			"labels": map[string]string{"app.kubernetes.io/managed-by": "tenant-registry"}},
		"spec": map[string]any{"secretName": name, "dnsNames": dnsNames,
			"issuerRef":  map[string]string{"name": c.IssuerName, "kind": kind, "group": "cert-manager.io"},
			"privateKey": map[string]any{"algorithm": "ECDSA", "size": 256, "rotationPolicy": "Always"}},
	}
	if _, err := c.req(ctx, http.MethodPost, "/apis/cert-manager.io/v1/namespaces/"+url.PathEscape(c.Namespace)+"/certificates",
		cert, http.StatusConflict); err != nil {
		return "", err
	}
	ref := CertRef(c.Namespace, name)
	wait, every := c.Wait, c.PollEvery
	if wait <= 0 {
		wait = 3 * time.Minute
	}
	if every <= 0 {
		every = 5 * time.Second
	}
	deadline := time.Now().Add(wait)
	for {
		ok, err := c.Ready(ctx, ref)
		if err != nil {
			return "", err
		}
		if ok {
			return ref, nil
		}
		if time.Now().After(deadline) {
			return "", Transient("Chứng chỉ TLS chưa được cấp", errors.New("hết thời gian chờ chứng chỉ "+name))
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(every):
		}
	}
}

// Ready kiểm điều kiện Ready=True của Certificate.
func (c *CertManager) Ready(ctx context.Context, ref string) (bool, error) {
	ns, name, ok := parseRef(ref)
	if !ok || ns != c.Namespace {
		return false, errors.New("provision: tham chiếu chứng chỉ không hợp lệ")
	}
	r, err := c.req(ctx, http.MethodGet, c.certPath(name), nil, http.StatusNotFound)
	if err != nil || r.status == http.StatusNotFound {
		return false, err
	}
	var cr struct {
		Status struct {
			Conditions []struct {
				Type   string `json:"type"`
				Status string `json:"status"`
			} `json:"conditions"`
		} `json:"status"`
	}
	if err := decode(r, &cr); err != nil {
		return false, err
	}
	for _, cd := range cr.Status.Conditions {
		if cd.Type == "Ready" && cd.Status == "True" {
			return true, nil
		}
	}
	return false, nil
}

// Revoke xoá Certificate và Secret chứa khoá riêng (bù trừ).
func (c *CertManager) Revoke(ctx context.Context, ref string) error {
	ns, name, ok := parseRef(ref)
	if !ok || ns != c.Namespace {
		return errors.New("provision: tham chiếu chứng chỉ không hợp lệ")
	}
	if _, err := c.req(ctx, http.MethodDelete, c.certPath(name), nil, http.StatusNotFound); err != nil {
		return err
	}
	_, err := c.req(ctx, http.MethodDelete, "/api/v1/namespaces/"+url.PathEscape(ns)+"/secrets/"+url.PathEscape(name), nil, http.StatusNotFound)
	return err
}
