// Package kube là client REST tối thiểu tới Kubernetes API (liệt kê theo nhãn và merge-patch) bằng token
// ServiceAccount trong pod; đủ cho bộ điều khiển suy giảm, tránh kéo client-go.
package kube

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// Resource là một loại tài nguyên có namespace.
type Resource struct {
	Group, Version, Plural string
}

// Hai loại tài nguyên bộ điều khiển đụng tới.
var (
	ApisixPluginConfig = Resource{"apisix.apache.org", "v2", "apisixpluginconfigs"}
	ScaledObject       = Resource{"keda.sh", "v1alpha1", "scaledobjects"}
)

// Object là phần metadata cần dùng cộng nguyên đối tượng.
type Object struct {
	Metadata struct {
		Name        string            `json:"name"`
		Namespace   string            `json:"namespace"`
		Labels      map[string]string `json:"labels"`
		Annotations map[string]string `json:"annotations"`
	} `json:"metadata"`
}

// Client gọi API server.
type Client struct {
	Base      string
	TokenFile string
	HTTP      *http.Client
}

// InCluster dựng client từ K8S_API (mặc định https://kubernetes.default.svc) và tệp của ServiceAccount.
func InCluster(api, tokenFile, caFile string) (*Client, error) {
	if api == "" {
		api = "https://kubernetes.default.svc"
	}
	tr := http.DefaultTransport.(*http.Transport).Clone()
	if caFile != "" {
		if pem, err := os.ReadFile(caFile); err == nil {
			pool := x509.NewCertPool()
			pool.AppendCertsFromPEM(pem)
			tr.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
		}
	}
	return &Client{Base: strings.TrimRight(api, "/"), TokenFile: tokenFile, HTTP: &http.Client{Transport: tr, Timeout: 10 * time.Second}}, nil
}

func (c *Client) path(r Resource, ns, name string) string {
	p := fmt.Sprintf("/apis/%s/%s/namespaces/%s/%s", r.Group, r.Version, url.PathEscape(ns), r.Plural)
	if name != "" {
		p += "/" + url.PathEscape(name)
	}
	return p
}

func (c *Client) do(ctx context.Context, method, p, ctype string, body []byte, out any) error {
	req, err := http.NewRequestWithContext(ctx, method, c.Base+p, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	if c.TokenFile != "" {
		if tok, err := os.ReadFile(c.TokenFile); err == nil {
			req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(tok)))
		}
	}
	hc := c.HTTP
	if hc == nil {
		hc = http.DefaultClient
	}
	res, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	if res.StatusCode/100 != 2 {
		return &StatusError{Code: res.StatusCode, Method: method, Path: p}
	}
	if out != nil {
		return json.Unmarshal(raw, out)
	}
	return nil
}

// StatusError là phản hồi không thành công của API server (không chép thân để không lộ dữ liệu).
type StatusError struct {
	Code         int
	Method, Path string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("kube: %s %s: HTTP %d", e.Method, e.Path, e.Code)
}

// List liệt kê đối tượng có nhãn khớp selector trong một namespace.
func (c *Client) List(ctx context.Context, r Resource, ns, selector string) ([]Object, error) {
	var out struct {
		Items []Object `json:"items"`
	}
	p := c.path(r, ns, "") + "?labelSelector=" + url.QueryEscape(selector)
	if err := c.do(ctx, http.MethodGet, p, "", nil, &out); err != nil {
		return nil, err
	}
	return out.Items, nil
}

// MergePatch vá đối tượng bằng application/merge-patch+json.
func (c *Client) MergePatch(ctx context.Context, r Resource, ns, name string, patch any) error {
	body, err := json.Marshal(patch)
	if err != nil {
		return err
	}
	return c.do(ctx, http.MethodPatch, c.path(r, ns, name), "application/merge-patch+json", body, nil)
}
