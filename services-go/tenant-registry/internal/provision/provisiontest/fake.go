// Package provisiontest dựng hệ giả cho kiểm thử saga cấp phát (YC-PT-13): một máy chủ HTTP đóng vai PowerDNS,
// API Kubernetes của cert-manager, API nội bộ mission-core (MOD-05, MOD-08), org-service, Prometheus và điểm
// cấp token; cùng bản Keycloak trong bộ nhớ cho kiểm thử API. Kiểm thử bước Organization với Keycloak thật nằm
// ở gói provision. Chỉ dùng trong kiểm thử.
package provisiontest

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/provision"
)

// Cell là trạng thái các hệ giả của một cell.
type Cell struct {
	Server *httptest.Server

	mu        sync.Mutex
	DNS       map[string]string // fqdn → đích fqdn
	Certs     map[string]int    // tên Certificate → số lần GET còn lại trước khi Ready
	Secrets   map[string]bool   // Secret TLS đã có
	Packs     map[uuid.UUID][]provision.PackState
	UiPacks   map[uuid.UUID]bool
	Calendars map[uuid.UUID]int
	Trees     map[uuid.UUID]bool
	HighDisc  map[uuid.UUID]int
	LagSec    float64
	Calls     []string

	// Điều khiển lỗi.
	PackNotReady   string // mã gói MOD-05 từ chối (BR-01-03)
	CalendarFails  int    // số lần đầu tạo lịch trả 503
	CertReadyAfter int    // số lần GET Certificate trước khi Ready
	PackGolden     float64
	TreeDefault    bool // coi mọi tenant đã có cây tổ chức (bộ giả lập dev, cmd/provision-gia-lap)

	handler http.Handler
}

// NewCell mở máy chủ giả.
func NewCell() *Cell {
	c := NewCellHandler()
	c.Server = httptest.NewServer(c.handler)
	return c
}

// NewCellHandler dựng hệ giả mà không mở máy chủ; người gọi tự phục vụ Handler() (bộ giả lập dev).
func NewCellHandler() *Cell {
	c := &Cell{DNS: map[string]string{}, Certs: map[string]int{}, Secrets: map[string]bool{}, Packs: map[uuid.UUID][]provision.PackState{},
		UiPacks: map[uuid.UUID]bool{}, Calendars: map[uuid.UUID]int{}, Trees: map[uuid.UUID]bool{}, HighDisc: map[uuid.UUID]int{}, PackGolden: 100}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.Form.Get("grant_type") != "client_credentials" || r.Form.Get("client_secret") == "" {
			w.WriteHeader(401)
			return
		}
		writeJSON(w, 200, map[string]any{"access_token": "tok-" + r.Form.Get("client_id"), "expires_in": 300})
	})
	mux.HandleFunc("PATCH /api/v1/servers/localhost/zones/{zone}", c.dnsPatch)
	mux.HandleFunc("GET /api/v1/servers/localhost/zones/{zone}", c.dnsGet)
	mux.HandleFunc("POST /apis/cert-manager.io/v1/namespaces/{ns}/certificates", c.certCreate)
	mux.HandleFunc("GET /apis/cert-manager.io/v1/namespaces/{ns}/certificates/{name}", c.certGet)
	mux.HandleFunc("DELETE /apis/cert-manager.io/v1/namespaces/{ns}/certificates/{name}", c.certDelete)
	mux.HandleFunc("DELETE /api/v1/namespaces/{ns}/secrets/{name}", c.secretDelete)
	mux.HandleFunc("/internal/v1/tenants/{id}/{res...}", c.core)
	mux.HandleFunc("GET /api/v1/org/units/tree", c.orgTree)
	mux.HandleFunc("GET /api/v1/org/discrepancies", c.orgDisc)
	mux.HandleFunc("GET /api/v1/query", func(w http.ResponseWriter, _ *http.Request) {
		c.mu.Lock()
		lag := c.LagSec
		c.mu.Unlock()
		writeJSON(w, 200, map[string]any{"status": "success", "data": map[string]any{"resultType": "vector",
			"result": []any{map[string]any{"metric": map[string]string{}, "value": []any{1.7e9, fmt.Sprint(lag)}}}}})
	})
	c.handler = c.record(mux)
	return c
}

// Handler trả bộ xử lý HTTP của hệ giả.
func (c *Cell) Handler() http.Handler { return c.handler }

// Close dừng máy chủ.
func (c *Cell) Close() { c.Server.Close() }

// URL là địa chỉ máy chủ giả.
func (c *Cell) URL() string { return c.Server.URL }

func (c *Cell) record(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		c.Calls = append(c.Calls, r.Method+" "+r.URL.Path)
		c.mu.Unlock()
		if r.URL.Path != "/token" && !strings.HasPrefix(r.URL.Path, "/api/v1/query") &&
			!strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") && r.Header.Get("X-API-Key") == "" {
			w.WriteHeader(401)
			return
		}
		h.ServeHTTP(w, r)
	})
}

// Lock, Unlock cho kiểm thử đọc trạng thái.
func (c *Cell) Lock()   { c.mu.Lock() }
func (c *Cell) Unlock() { c.mu.Unlock() }

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (c *Cell) dnsPatch(w http.ResponseWriter, r *http.Request) {
	var body struct {
		RRsets []struct {
			Name       string `json:"name"`
			Type       string `json:"type"`
			ChangeType string `json:"changetype"`
			Records    []struct {
				Content string `json:"content"`
			} `json:"records"`
		} `json:"rrsets"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		w.WriteHeader(400)
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, rr := range body.RRsets {
		if !strings.HasSuffix(rr.Name, ".") || rr.Type != "CNAME" {
			w.WriteHeader(422)
			return
		}
		switch rr.ChangeType {
		case "REPLACE":
			c.DNS[rr.Name] = rr.Records[0].Content
		case "DELETE":
			delete(c.DNS, rr.Name)
		}
	}
	w.WriteHeader(204)
}

func (c *Cell) dnsGet(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("rrset_name")
	c.mu.Lock()
	target, ok := c.DNS[name]
	c.mu.Unlock()
	rr := []any{}
	if ok {
		rr = append(rr, map[string]any{"name": name, "type": "CNAME", "ttl": 300, "records": []any{map[string]any{"content": target, "disabled": false}}})
	}
	writeJSON(w, 200, map[string]any{"name": r.PathValue("zone"), "rrsets": rr})
}

func (c *Cell) certCreate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Metadata struct {
			Name string `json:"name"`
		} `json:"metadata"`
		Spec struct {
			DNSNames []string `json:"dnsNames"`
		} `json:"spec"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.Certs[body.Metadata.Name]; ok {
		writeJSON(w, 409, map[string]any{"kind": "Status", "reason": "AlreadyExists"})
		return
	}
	if len(body.Spec.DNSNames) == 0 {
		writeJSON(w, 422, map[string]any{"kind": "Status"})
		return
	}
	c.Certs[body.Metadata.Name] = c.CertReadyAfter
	writeJSON(w, 201, body)
}

func (c *Cell) certGet(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	c.mu.Lock()
	n, ok := c.Certs[name]
	ready := ok && n <= 0
	if ok && n > 0 {
		c.Certs[name] = n - 1
	}
	if ready {
		c.Secrets[name] = true
	}
	c.mu.Unlock()
	if !ok {
		writeJSON(w, 404, map[string]any{"kind": "Status"})
		return
	}
	st := "False"
	if ready {
		st = "True"
	}
	writeJSON(w, 200, map[string]any{"status": map[string]any{"conditions": []any{map[string]string{"type": "Ready", "status": st}}}})
}

func (c *Cell) certDelete(w http.ResponseWriter, r *http.Request) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.Certs[r.PathValue("name")]; !ok {
		w.WriteHeader(404)
		return
	}
	delete(c.Certs, r.PathValue("name"))
	w.WriteHeader(200)
}

func (c *Cell) secretDelete(w http.ResponseWriter, r *http.Request) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.Secrets[r.PathValue("name")] {
		w.WriteHeader(404)
		return
	}
	delete(c.Secrets, r.PathValue("name"))
	w.WriteHeader(200)
}

func (c *Cell) core(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil || r.Header.Get(provision.HeaderTenant) != id.String() {
		w.WriteHeader(400)
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	switch r.Method + " " + r.PathValue("res") {
	case "PUT policy-bindings":
		var body struct {
			Bindings []provision.PackBinding `json:"bindings"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		var st []provision.PackState
		for _, b := range body.Bindings {
			code := "goi-" + strings.ToLower(b.Flow)
			if c.PackNotReady == code {
				writeJSON(w, 422, map[string]any{"type": "https://okrkpi.vn/problems/br-01-03", "title": "Gói chưa sẵn sàng", "status": 422,
					"code": "POLICY-422-PACK-NOT-READY", "packCode": code})
				return
			}
			st = append(st, provision.PackState{Flow: b.Flow, PackVersionID: b.PackVersionID, PackCode: code, Version: "1.0.0",
				Status: "DA_PHAT_HANH", GoldenPassPct: c.PackGolden})
		}
		c.Packs[id] = st
		writeJSON(w, 200, map[string]any{"items": st})
	case "GET policy-bindings":
		items := c.Packs[id]
		if items == nil {
			items = []provision.PackState{}
		}
		writeJSON(w, 200, map[string]any{"items": items})
	case "DELETE policy-bindings":
		if _, ok := c.Packs[id]; !ok {
			w.WriteHeader(404)
			return
		}
		delete(c.Packs, id)
		w.WriteHeader(204)
	case "PUT ui-pack":
		c.UiPacks[id] = true
		writeJSON(w, 200, map[string]any{"name": "Mặc định", "version": "3.0.0"})
	case "DELETE ui-pack":
		if !c.UiPacks[id] {
			w.WriteHeader(404)
			return
		}
		delete(c.UiPacks, id)
		w.WriteHeader(204)
	case "PUT holiday-calendars/default":
		if c.CalendarFails > 0 {
			c.CalendarFails--
			w.WriteHeader(503)
			return
		}
		var body struct {
			Year int `json:"year"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		c.Calendars[id] = body.Year
		w.WriteHeader(200)
	case "DELETE holiday-calendars/default":
		if _, ok := c.Calendars[id]; !ok {
			w.WriteHeader(404)
			return
		}
		delete(c.Calendars, id)
		w.WriteHeader(204)
	default:
		w.WriteHeader(404)
	}
}

func (c *Cell) tenantOf(r *http.Request) uuid.UUID {
	id, _ := uuid.Parse(r.Header.Get(provision.HeaderTenant))
	return id
}

func (c *Cell) orgTree(w http.ResponseWriter, r *http.Request) {
	c.mu.Lock()
	ok := c.TreeDefault || c.Trees[c.tenantOf(r)]
	c.mu.Unlock()
	roots := []any{}
	if ok {
		roots = append(roots, map[string]any{"id": uuid.NewString(), "name": "Tỉnh uỷ"})
	}
	writeJSON(w, 200, map[string]any{"asOf": "2026-10-21", "recordedAsOf": "2026-10-21T00:00:00Z", "readOnly": false, "roots": roots})
}

func (c *Cell) orgDisc(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("severity") != "CAO" {
		w.WriteHeader(400)
		return
	}
	c.mu.Lock()
	n := c.HighDisc[c.tenantOf(r)]
	c.mu.Unlock()
	writeJSON(w, 200, map[string]any{"items": []any{}, "nextCursor": nil, "counts": map[string]int{"CAO": n, "VUA": 0, "THAP": 0}})
}

// ---------------------------------------------------------------- Keycloak trong bộ nhớ

// Keycloak là bản giả của cổng provision.Keycloak (cho kiểm thử API; bản thật kiểm ở gói provision).
type Keycloak struct {
	mu      sync.Mutex
	Orgs    map[string]provision.OrgSpec
	Idps    map[string]provision.IdpSpec
	Users   map[string]string // id → email
	Members map[string][]string
	Links   map[string][]string
	FailOrg bool
}

// NewKeycloak tạo bản giả rỗng.
func NewKeycloak() *Keycloak {
	return &Keycloak{Orgs: map[string]provision.OrgSpec{}, Idps: map[string]provision.IdpSpec{}, Users: map[string]string{},
		Members: map[string][]string{}, Links: map[string][]string{}}
}

func (k *Keycloak) EnsureOrganization(_ context.Context, _ provision.CellEndpoints, s provision.OrgSpec) (string, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.FailOrg {
		return "", &provision.HTTPError{System: "keycloak-admin", Status: 503}
	}
	for id, o := range k.Orgs {
		if o.Alias == s.Alias {
			return id, nil
		}
	}
	id := uuid.NewString()
	k.Orgs[id] = s
	return id, nil
}

func (k *Keycloak) OrganizationExists(_ context.Context, _ provision.CellEndpoints, id string) (bool, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	_, ok := k.Orgs[id]
	return ok, nil
}

func (k *Keycloak) DeleteOrganization(_ context.Context, _ provision.CellEndpoints, id string) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	delete(k.Orgs, id)
	delete(k.Links, id)
	delete(k.Members, id)
	return nil
}

func (k *Keycloak) EnsureIdentityProvider(_ context.Context, _ provision.CellEndpoints, i provision.IdpSpec) (bool, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	if _, ok := k.Idps[i.Alias]; ok {
		return false, nil
	}
	k.Idps[i.Alias] = i
	return true, nil
}

func (k *Keycloak) DeleteIdentityProvider(_ context.Context, _ provision.CellEndpoints, alias string) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	delete(k.Idps, alias)
	return nil
}

func (k *Keycloak) LinkIdentityProvider(_ context.Context, _ provision.CellEndpoints, org, alias string) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	k.Links[org] = append(k.Links[org], alias)
	return nil
}

func (k *Keycloak) EnsureUser(_ context.Context, _ provision.CellEndpoints, u provision.UserSpec) (string, bool, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	for id, e := range k.Users {
		if strings.EqualFold(e, u.Email) {
			return id, false, nil
		}
	}
	id := uuid.NewString()
	k.Users[id] = u.Email
	return id, true, nil
}

func (k *Keycloak) DeleteUser(_ context.Context, _ provision.CellEndpoints, id string) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	delete(k.Users, id)
	return nil
}

func (k *Keycloak) AddMember(_ context.Context, _ provision.CellEndpoints, org, user string) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	k.Members[org] = append(k.Members[org], user)
	return nil
}

// Counts trả số Organization, IdP, tài khoản (kiểm bù trừ).
func (k *Keycloak) Counts() (orgs, idps, users int) {
	k.mu.Lock()
	defer k.mu.Unlock()
	return len(k.Orgs), len(k.Idps), len(k.Users)
}

// Tokens là nguồn token giả của máy chủ giả.
func Tokens() *provision.ClientCredentials {
	return &provision.ClientCredentials{ClientID: "tenant-registry-provisioner", ClientSecret: "kiem-thu"}
}

// SagaDeps dựng các cổng của saga trỏ vào máy chủ giả; tokenURL rỗng dùng điểm cấp token giả.
func (c *Cell) SagaDeps(tokens provision.TokenSource, keycloakURL, tokenURL string) (provision.EndpointResolver, provision.DNS, provision.Certificates,
	provision.Core, provision.Org, provision.ReplicaLag) {
	if tokens == nil {
		tokens = Tokens()
	}
	if tokenURL == "" {
		tokenURL = c.URL() + "/token"
	}
	res := provision.TemplateResolver{KeycloakURL: keycloakURL, Realm: "mission", TokenURL: tokenURL, CoreURL: c.URL(), OrgURL: c.URL()}
	return res,
		&provision.PowerDNS{BaseURL: c.URL(), Zone: "okr.viettel.vn", APIKey: "kiem-thu"},
		&provision.CertManager{APIServer: c.URL(), Namespace: "apisix", IssuerName: "letsencrypt", Token: func() (string, error) { return "sa", nil },
			PollEvery: 1, Wait: 2e9},
		&provision.CoreHTTP{Tokens: tokens},
		&provision.OrgHTTP{Tokens: tokens},
		&provision.PrometheusLag{BaseURL: c.URL(), Query: `max(lag{cell="{cell}"})`}
}
