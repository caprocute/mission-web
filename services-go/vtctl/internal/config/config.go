// Package config đọc cấu hình vtctl: môi trường (Keycloak vận hành, registry), cell và cách tới Kafka,
// Kubernetes, bộ điều khiển suy giảm của từng cell. Danh mục cell đối chiếu với deploy/cells/<cell>/cell.yaml.
package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"
)

// Config là tệp ~/.config/vtctl/config.yaml (mẫu ở vtctl.example.yaml). Không chứa bí mật: token nằm ở kho
// token riêng, khoá mTLS Kafka chỉ ghi đường dẫn tệp.
type Config struct {
	CellsDir string `yaml:"cellsDir"`
	// ChartsDir mặc định là thư mục charts cạnh cellsDir (deploy/charts).
	ChartsDir    string                 `yaml:"chartsDir"`
	AuditLog     string                 `yaml:"auditLog"`
	TokenDir     string                 `yaml:"tokenDir"`
	Environments map[string]Environment `yaml:"environments"`
}

// Environment là một môi trường (staging, production, kind…).
type Environment struct {
	Issuer      string          `yaml:"issuer"`
	ClientID    string          `yaml:"clientId"`
	RegistryURL string          `yaml:"registryURL"`
	Cells       map[string]Cell `yaml:"cells"`
}

// Cell là cách tới các thành phần của một cell trong môi trường.
type Cell struct {
	APIURL      string            `yaml:"apiURL"`
	Kafka       Kafka             `yaml:"kafka"`
	KubeContext string            `yaml:"kubeContext"`
	Namespaces  map[string]string `yaml:"namespaces"`
	DegradeURL  string            `yaml:"degradeURL"`
	// Values là tệp values theo chart (ví dụ org-service, vtctl-jobs) dùng khi dựng Job.
	Values map[string][]string `yaml:"values"`
}

// Kafka là cách nối Kafka của cell; TLS bắt buộc ngoài môi trường kind.
type Kafka struct {
	Bootstrap []string `yaml:"bootstrap"`
	CAFile    string   `yaml:"caFile"`
	CertFile  string   `yaml:"certFile"`
	KeyFile   string   `yaml:"keyFile"`
	Plaintext bool     `yaml:"plaintext"`
}

// CellInfo là phần cần dùng của deploy/cells/<cell>/cell.yaml.
type CellInfo struct {
	Name        string `yaml:"name"`
	DisplayName string `yaml:"displayName"`
	Sizing      string `yaml:"sizing"`
	Tenancy     string `yaml:"tenancy"`
	ReleaseWave string `yaml:"releaseWave"`
}

// Target là môi trường và cell đã chọn cho một lệnh.
type Target struct {
	EnvName  string
	Env      Environment
	CellName string
	Cell     Cell
	Info     CellInfo
}

var nameRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,30}$`)

// DefaultPath là vị trí tệp cấu hình mặc định.
func DefaultPath() string {
	if p := os.Getenv("VTCTL_CONFIG"); p != "" {
		return p
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		return "vtctl.yaml"
	}
	return filepath.Join(dir, "vtctl", "config.yaml")
}

// Load đọc và kiểm cấu hình; đường dẫn tương đối trong tệp tính từ thư mục chứa tệp.
func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("đọc cấu hình %s: %w", path, err)
	}
	var c Config
	dec := yaml.NewDecoder(strings.NewReader(string(raw)))
	dec.KnownFields(true)
	if err := dec.Decode(&c); err != nil {
		return nil, fmt.Errorf("cấu hình %s: %w", path, err)
	}
	base := filepath.Dir(path)
	abs := func(p string) string {
		if p == "" || filepath.IsAbs(p) {
			return p
		}
		if strings.HasPrefix(p, "~/") {
			if h, err := os.UserHomeDir(); err == nil {
				return filepath.Join(h, p[2:])
			}
		}
		return filepath.Join(base, p)
	}
	c.CellsDir, c.ChartsDir, c.AuditLog, c.TokenDir = abs(c.CellsDir), abs(c.ChartsDir), abs(c.AuditLog), abs(c.TokenDir)
	if c.CellsDir == "" {
		return nil, errors.New("cấu hình thiếu cellsDir (thư mục deploy/cells)")
	}
	if c.ChartsDir == "" {
		c.ChartsDir = filepath.Join(filepath.Dir(c.CellsDir), "charts")
	}
	if c.AuditLog == "" {
		if d, err := os.UserHomeDir(); err == nil {
			c.AuditLog = filepath.Join(d, ".local", "state", "vtctl", "audit.jsonl")
		}
	}
	if c.TokenDir == "" {
		c.TokenDir = filepath.Join(filepath.Dir(DefaultPath()), "tokens")
	}
	for name, env := range c.Environments {
		if !nameRe.MatchString(name) {
			return nil, fmt.Errorf("tên môi trường %q không hợp lệ", name)
		}
		if env.Issuer == "" || env.ClientID == "" {
			return nil, fmt.Errorf("môi trường %s thiếu issuer hoặc clientId", name)
		}
		for cell, cc := range env.Cells {
			if !nameRe.MatchString(cell) {
				return nil, fmt.Errorf("tên cell %q không hợp lệ", cell)
			}
			cc.Kafka.CAFile, cc.Kafka.CertFile, cc.Kafka.KeyFile = abs(cc.Kafka.CAFile), abs(cc.Kafka.CertFile), abs(cc.Kafka.KeyFile)
			for chart, files := range cc.Values {
				for i := range files {
					files[i] = abs(files[i])
				}
				cc.Values[chart] = files
			}
			env.Cells[cell] = cc
		}
		c.Environments[name] = env
	}
	return &c, nil
}

// CellCatalog đọc mọi deploy/cells/*/cell.yaml.
func CellCatalog(dir string) (map[string]CellInfo, error) {
	files, err := filepath.Glob(filepath.Join(dir, "*", "cell.yaml"))
	if err != nil {
		return nil, err
	}
	if len(files) == 0 {
		return nil, fmt.Errorf("không thấy cell.yaml nào trong %s", dir)
	}
	out := map[string]CellInfo{}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			return nil, err
		}
		var doc struct {
			Cell CellInfo `yaml:"cell"`
		}
		if err := yaml.Unmarshal(raw, &doc); err != nil {
			return nil, fmt.Errorf("%s: %w", f, err)
		}
		if doc.Cell.Name != filepath.Base(filepath.Dir(f)) {
			return nil, fmt.Errorf("%s: cell.name %q khác tên thư mục", f, doc.Cell.Name)
		}
		out[doc.Cell.Name] = doc.Cell
	}
	return out, nil
}

// Resolve chọn môi trường và (tuỳ chọn) cell. cell rỗng chỉ dùng được cho lệnh cấp môi trường (registry).
func (c *Config) Resolve(envName, cellName string) (Target, error) {
	env, ok := c.Environments[envName]
	if !ok {
		return Target{}, fmt.Errorf("không có môi trường %q trong cấu hình (có: %s)", envName, strings.Join(keys(c.Environments), ", "))
	}
	t := Target{EnvName: envName, Env: env}
	if cellName == "" {
		return t, nil
	}
	catalog, err := CellCatalog(c.CellsDir)
	if err != nil {
		return Target{}, err
	}
	info, ok := catalog[cellName]
	if !ok {
		return Target{}, fmt.Errorf("cell %q không có trong %s (có: %s)", cellName, c.CellsDir, strings.Join(keys(catalog), ", "))
	}
	cc, ok := env.Cells[cellName]
	if !ok {
		return Target{}, fmt.Errorf("môi trường %s chưa khai báo cell %s trong cấu hình", envName, cellName)
	}
	t.CellName, t.Cell, t.Info = cellName, cc, info
	return t, nil
}

// Namespace trả namespace theo vai trò (org, jobs, edge…) của cell, hoặc giá trị mặc định.
func (c Cell) Namespace(role, def string) string {
	if ns := c.Namespaces[role]; ns != "" {
		return ns
	}
	return def
}

func keys[V any](m map[string]V) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
