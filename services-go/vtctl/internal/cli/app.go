// Package cli dựng cây lệnh vtctl (OPS-14): mỗi lệnh ghi mặc định chạy thử, in tóm tắt tác động, chỉ thực hiện
// khi có --confirm và khi đó ghi đúng một bản ghi kiểm toán.
package cli

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"
	"github.com/twmb/franz-go/pkg/kgo"

	"vn.viettel.okrkpi/services-go/vtctl/internal/api"
	"vn.viettel.okrkpi/services-go/vtctl/internal/audit"
	"vn.viettel.okrkpi/services-go/vtctl/internal/auth"
	"vn.viettel.okrkpi/services-go/vtctl/internal/config"
	"vn.viettel.okrkpi/services-go/vtctl/internal/dlq"
	"vn.viettel.okrkpi/services-go/vtctl/internal/runner"
)

// Version được đặt lúc dựng (-ldflags "-X …cli.Version=…").
var Version = "dev"

// App là trạng thái dùng chung của một lần chạy.
type App struct {
	Out, Err io.Writer

	ConfigPath string
	Env, Cell  string
	Confirm    bool
	DryRun     bool
	JSON       bool

	// Có thể thay trong kiểm thử.
	Runner      runner.Runner
	HTTP        *http.Client
	Interactive bool
	Now         func() time.Time
	KafkaOpts   func(config.Kafka) ([]kgo.Opt, error)

	cfg       *config.Config
	sessions  map[string]*auth.Session
	dryRunSet bool
}

// NewRoot dựng lệnh gốc.
func NewRoot(a *App) *cobra.Command {
	if a.Runner == nil {
		a.Runner = runner.Exec{Stderr: a.Err}
	}
	if a.Now == nil {
		a.Now = time.Now
	}
	if a.KafkaOpts == nil {
		a.KafkaOpts = KafkaOptions
	}
	root := &cobra.Command{
		Use:           "vtctl",
		Short:         "Công cụ vận hành Mission cho người trực (OPS-14)",
		Version:       Version,
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.PersistentPreRun = func(cmd *cobra.Command, _ []string) { a.dryRunSet = cmd.Flags().Changed("dry-run") }
	root.SetOut(a.Out)
	root.SetErr(a.Err)
	pf := root.PersistentFlags()
	pf.StringVar(&a.ConfigPath, "config", config.DefaultPath(), "tệp cấu hình vtctl")
	pf.StringVarP(&a.Env, "env", "e", os.Getenv("VTCTL_ENV"), "môi trường (staging, production, kind…)")
	pf.StringVarP(&a.Cell, "cell", "c", os.Getenv("VTCTL_CELL"), "cell (tên thư mục trong deploy/cells)")
	pf.BoolVar(&a.Confirm, "confirm", false, "thực hiện thật lệnh ghi (mặc định chỉ chạy thử)")
	pf.BoolVar(&a.DryRun, "dry-run", true, "chạy thử; --confirm mới thực hiện, --dry-run=true luôn thắng --confirm")
	pf.BoolVarP(&a.JSON, "json", "o", false, "in kết quả dạng JSON")
	root.AddCommand(loginCmd(a), logoutCmd(a), whoamiCmd(a), dlqCmd(a), consumerCmd(a), tenantCmd(a), cellCmd(a),
		periodLockCmd(a), outboxCmd(a), orgCmd(a), degradeCmd(a))
	return root
}

// Execute chạy vtctl và trả mã thoát: 0 thành công, 1 lỗi thao tác, 2 sai tham số.
func Execute(ctx context.Context, a *App, args []string) int {
	root := NewRoot(a)
	root.SetArgs(args)
	if err := root.ExecuteContext(ctx); err != nil {
		fmt.Fprintln(a.Err, "Lỗi:", err)
		var u usageError
		if errors.As(err, &u) || strings.Contains(err.Error(), "unknown flag") || strings.Contains(err.Error(), "required flag") ||
			strings.Contains(err.Error(), "accepts ") || strings.Contains(err.Error(), "unknown command") {
			return 2
		}
		return 1
	}
	return 0
}

type usageError struct{ msg string }

func (u usageError) Error() string { return u.msg }

func usagef(format string, v ...any) error { return usageError{fmt.Sprintf(format, v...)} }

func (a *App) config() (*config.Config, error) {
	if a.cfg != nil {
		return a.cfg, nil
	}
	c, err := config.Load(a.ConfigPath)
	if err != nil {
		return nil, err
	}
	a.cfg = c
	return c, nil
}

// target chọn môi trường, và cell khi needCell.
func (a *App) target(needCell bool) (config.Target, error) {
	c, err := a.config()
	if err != nil {
		return config.Target{}, err
	}
	if a.Env == "" {
		return config.Target{}, usagef("thiếu --env")
	}
	if needCell && a.Cell == "" {
		return config.Target{}, usagef("thiếu --cell")
	}
	cell := a.Cell
	if !needCell {
		cell = ""
	}
	return c.Resolve(a.Env, cell)
}

func (a *App) session(t config.Target) (*auth.Session, error) {
	c, err := a.config()
	if err != nil {
		return nil, err
	}
	if a.sessions == nil {
		a.sessions = map[string]*auth.Session{}
	}
	if s, ok := a.sessions[t.EnvName]; ok {
		return s, nil
	}
	s := &auth.Session{Issuer: t.Env.Issuer, ClientID: t.Env.ClientID, Path: filepath.Join(c.TokenDir, t.EnvName+".json"),
		HTTP: a.HTTP, Out: a.Err, Now: a.Now, Interactive: a.Interactive}
	a.sessions[t.EnvName] = s
	return s, nil
}

type tokenSource struct{ s *auth.Session }

func (t tokenSource) Token(ctx context.Context, aal2, force bool) (string, error) {
	if force {
		if err := t.s.Login(ctx, true); err != nil {
			return "", err
		}
	}
	return t.s.AccessToken(ctx, aal2)
}

func (a *App) apiClient(t config.Target, base string) (*api.Client, error) {
	s, err := a.session(t)
	if err != nil {
		return nil, err
	}
	return &api.Client{Base: base, HTTP: a.HTTP, Tokens: tokenSource{s}, UserAgent: "vtctl/" + Version}, nil
}

// registry trả client của tenant-registry (SVC-03) của môi trường.
func (a *App) registry(t config.Target) (*api.Client, error) {
	if t.Env.RegistryURL == "" {
		return nil, fmt.Errorf("môi trường %s chưa khai báo registryURL", t.EnvName)
	}
	return a.apiClient(t, t.Env.RegistryURL)
}

// cellAPI trả client cổng API của cell: apiURL trong cấu hình, nếu trống thì `apiBaseUrl` của cell trong registry
// (khớp `code` với displayName của cell.yaml).
func (a *App) cellAPI(ctx context.Context, t config.Target) (*api.Client, error) {
	if t.Cell.APIURL != "" {
		return a.apiClient(t, t.Cell.APIURL)
	}
	reg, err := a.registry(t)
	if err != nil {
		return nil, err
	}
	var page struct {
		Items []struct {
			Code       string `json:"code"`
			APIBaseURL string `json:"apiBaseUrl"`
		} `json:"items"`
	}
	if _, err := reg.Get(ctx, "/registry/cells?limit=200", false, &page); err != nil {
		return nil, fmt.Errorf("tra địa chỉ API của cell %s: %w", t.CellName, err)
	}
	for _, it := range page.Items {
		if strings.EqualFold(it.Code, t.Info.DisplayName) && it.APIBaseURL != "" {
			return a.apiClient(t, it.APIBaseURL)
		}
	}
	return nil, fmt.Errorf("registry không có cell %s (%s); khai báo apiURL trong cấu hình", t.CellName, t.Info.DisplayName)
}

// KafkaOptions dựng tuỳ chọn kgo theo cấu hình cell; mTLS bắt buộc trừ khi khai báo plaintext (chỉ kind, dev).
func KafkaOptions(k config.Kafka) ([]kgo.Opt, error) {
	if len(k.Bootstrap) == 0 {
		return nil, errors.New("cell chưa khai báo kafka.bootstrap")
	}
	opts := []kgo.Opt{kgo.SeedBrokers(k.Bootstrap...), kgo.ClientID("vtctl"), kgo.RequiredAcks(kgo.AllISRAcks())}
	if k.Plaintext {
		return opts, nil
	}
	if k.CAFile == "" || k.CertFile == "" || k.KeyFile == "" {
		return nil, errors.New("kết nối Kafka cần mTLS: khai báo kafka.caFile, certFile, keyFile")
	}
	ca, err := os.ReadFile(k.CAFile)
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(ca) {
		return nil, fmt.Errorf("không đọc được CA %s", k.CAFile)
	}
	cert, err := tls.LoadX509KeyPair(k.CertFile, k.KeyFile)
	if err != nil {
		return nil, err
	}
	return append(opts, kgo.DialTLSConfig(&tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool, Certificates: []tls.Certificate{cert}})), nil
}

func (a *App) kafka(t config.Target) (*dlq.Ops, error) {
	opts, err := a.KafkaOpts(t.Cell.Kafka)
	if err != nil {
		return nil, err
	}
	return dlq.New(opts...)
}

func (a *App) kubectl(t config.Target) runner.Kubectl {
	return runner.Kubectl{R: a.Runner, Context: t.Cell.KubeContext}
}

// requireRBAC dừng khi người chạy chưa có quyền Kubernetes cần thiết. Quyền cao chỉ có khi PAM đã cấp (INF-27);
// vtctl không tự nâng quyền.
func (a *App) requireRBAC(ctx context.Context, t config.Target, role, ns, verb, resource string) error {
	ok, err := a.kubectl(t).CanI(ctx, ns, verb, resource)
	if err != nil {
		return fmt.Errorf("kiểm quyền Kubernetes: %w", err)
	}
	if !ok {
		return fmt.Errorf("chưa có quyền %s %s trong namespace %s (vai trò %s). Xin quyền có thời hạn qua PAM theo "+
			"quy trình INF-27 (người duyệt chấp thuận, hiệu lực 4 giờ), rồi chạy lại lệnh", verb, resource, ns, role)
	}
	return nil
}

// confirmed: chỉ thực hiện khi có --confirm và người chạy không tự gõ --dry-run=true.
func (a *App) confirmed() bool {
	return a.Confirm && !(a.DryRun && a.dryRunSet)
}

// Write là một thao tác ghi.
type Write struct {
	Command string
	Args    map[string]string
	// Summary là các dòng mô tả tác động, in trước khi chạy.
	Summary []string
	Key     string
	// Preview chạy ở chế độ thử (đọc, dựng manifest, kiểm phía máy chủ không lưu); có thể nil.
	Preview func(ctx context.Context) (map[string]any, error)
	Do      func(ctx context.Context) (map[string]any, error)
}

// write chạy Write theo quy tắc chạy thử mặc định, xác nhận, kiểm toán.
func (a *App) write(ctx context.Context, t config.Target, w Write) error {
	fmt.Fprintf(a.Out, "Lệnh: %s (môi trường %s%s)\n", w.Command, t.EnvName, cellSuffix(t))
	for _, s := range w.Summary {
		fmt.Fprintln(a.Out, "  -", s)
	}
	if !a.confirmed() {
		if w.Preview != nil {
			res, err := w.Preview(ctx)
			if err != nil {
				return err
			}
			a.print(res)
		}
		fmt.Fprintln(a.Out, "Chạy thử: chưa thay đổi gì. Thêm --confirm để thực hiện.")
		return nil
	}
	s, err := a.session(t)
	if err != nil {
		return err
	}
	if _, err := s.AccessToken(ctx, false); err != nil {
		return err
	}
	claims, err := s.Claims()
	if err != nil {
		return err
	}
	res, runErr := w.Do(ctx)
	rec := audit.Record{Actor: claims.Actor(), Subject: claims.Sub, Env: t.EnvName, Cell: t.CellName, Command: w.Command,
		Args: w.Args, IdempotencyKey: w.Key, Outcome: audit.OutcomeOK, Result: res}
	if runErr != nil {
		rec.Outcome, rec.Error = audit.OutcomeFailed, runErr.Error()
	}
	c, _ := a.config()
	lg := audit.Logger{Path: c.AuditLog, Now: a.Now}
	if err := lg.Write(rec); err != nil {
		if runErr != nil {
			return fmt.Errorf("%w; ngoài ra không ghi được kiểm toán: %v", runErr, err)
		}
		return fmt.Errorf("thao tác đã chạy nhưng không ghi được kiểm toán: %w", err)
	}
	if runErr != nil {
		return runErr
	}
	a.print(res)
	fmt.Fprintln(a.Out, "Đã thực hiện.")
	return nil
}

func cellSuffix(t config.Target) string {
	if t.CellName == "" {
		return ""
	}
	return ", cell " + t.CellName
}

// print in kết quả dạng khoá: giá trị hoặc JSON.
func (a *App) print(v any) {
	if v == nil {
		return
	}
	if m, ok := v.(map[string]any); ok && len(m) == 0 {
		return
	}
	if a.JSON {
		enc := json.NewEncoder(a.Out)
		enc.SetIndent("", "  ")
		_ = enc.Encode(v)
		return
	}
	if m, ok := v.(map[string]any); ok {
		ks := make([]string, 0, len(m))
		for k := range m {
			ks = append(ks, k)
		}
		sort.Strings(ks)
		for _, k := range ks {
			switch x := m[k].(type) {
			case string, int, int32, int64, float64, bool, nil:
				fmt.Fprintf(a.Out, "%s: %v\n", k, x)
			default:
				raw, _ := json.Marshal(x)
				fmt.Fprintf(a.Out, "%s: %s\n", k, raw)
			}
		}
		return
	}
	raw, _ := json.MarshalIndent(v, "", "  ")
	fmt.Fprintln(a.Out, string(raw))
}

// flagArgs gom các cờ người chạy đã đặt để ghi kiểm toán.
func flagArgs(cmd *cobra.Command, pos ...string) map[string]string {
	out := map[string]string{}
	cmd.Flags().Visit(func(f *pflag.Flag) {
		if f.Name == "confirm" || f.Name == "dry-run" || f.Name == "config" || f.Name == "json" {
			return
		}
		out[f.Name] = f.Value.String()
	})
	for i, p := range pos {
		out[fmt.Sprintf("arg%d", i+1)] = p
	}
	return out
}

func requireReason(reason string) error {
	if len(strings.TrimSpace(reason)) < 5 {
		return usagef("cần --reason (số phiếu sự cố hoặc văn bản, tối thiểu 5 ký tự)")
	}
	return nil
}

var k8sName = regexp.MustCompile(`^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$`)
