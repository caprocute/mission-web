package cli

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"vn.viettel.okrkpi/services-go/vtctl/internal/config"
)

var schemaRe = regexp.MustCompile(`^[a-z][a-z0-9_]{0,62}$`)

// jobSpec là một Job dựng từ chart bằng `helm template -s` rồi `kubectl create -f -`.
type jobSpec struct {
	release, chart, template, ns string
	// set là giá trị kiểu (bool, số, danh sách) qua --set; str là chuỗi tự do qua --set-string, dấu phẩy được thoát.
	set, str []string
}

func (a *App) renderJob(ctx context.Context, t config.Target, j jobSpec) ([]byte, error) {
	c, err := a.config()
	if err != nil {
		return nil, err
	}
	args := []string{"template", j.release, filepath.Join(c.ChartsDir, j.chart), "-n", j.ns, "-s", j.template}
	for _, f := range t.Cell.Values[j.chart] {
		args = append(args, "-f", f)
	}
	for _, s := range j.set {
		args = append(args, "--set", s)
	}
	for _, s := range j.str {
		k, v, _ := strings.Cut(s, "=")
		args = append(args, "--set-string", k+"="+strings.NewReplacer(`\`, `\\`, ",", `\,`).Replace(v))
	}
	return a.Runner.Run(ctx, nil, "helm", args...)
}

// createJob tạo Job; dry=true kiểm phía máy chủ không lưu (`--dry-run=server`). Trả tên Job.
func (a *App) createJob(ctx context.Context, t config.Target, ns string, manifest []byte, dry bool) (string, error) {
	args := []string{"create", "-f", "-", "-o", "name"}
	if dry {
		args = append(args, "--dry-run=server")
	}
	out, err := a.kubectl(t).Run(ctx, manifest, ns, args...)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func (a *App) waitJob(ctx context.Context, t config.Target, ns, job string, d time.Duration) error {
	_, err := a.kubectl(t).Run(ctx, nil, ns, "wait", "--for=condition=complete", job, "--timeout="+d.String())
	return err
}

func (a *App) actor(ctx context.Context, t config.Target) (string, error) {
	s, err := a.session(t)
	if err != nil {
		return "", err
	}
	if _, err := s.AccessToken(ctx, false); err != nil {
		return "", err
	}
	c, err := s.Claims()
	if err != nil {
		return "", err
	}
	return c.Actor(), nil
}

func outboxCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "outbox", Short: "Phát lại sự kiện từ bảng outbox (NEN-022, chạy Job trong cell)"}
	var o struct {
		schema, tenant, from, to, eventID, aggregateID, eventType, topic, consumer, ns, reason string
		rate, batch                                                                            int
		wait                                                                                   time.Duration
	}
	replay := &cobra.Command{
		Use:   "replay",
		Short: "Dựng và chạy Job outbox-replay; không --confirm thì chỉ kiểm Job phía máy chủ",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			if !schemaRe.MatchString(o.schema) {
				return usagef("--schema không hợp lệ")
			}
			if err := uuidArg(o.tenant, "--tenant"); err != nil {
				return err
			}
			for _, ts := range []string{o.from, o.to} {
				if ts == "" {
					continue
				}
				if _, err := time.Parse(time.RFC3339, ts); err != nil {
					return usagef("--from/--to phải theo RFC 3339: %v", err)
				}
			}
			if o.from == "" && o.eventID == "" && o.aggregateID == "" {
				return usagef("cần --from (khuyến nghị lùi 5 phút trước sự cố) hoặc --event-id/--aggregate-id")
			}
			if err := requireReason(o.reason); err != nil {
				return err
			}
			ns := o.ns
			if ns == "" {
				ns = t.Cell.Namespace("jobs", "okr-ops")
			}
			if err := a.requireRBAC(cmd.Context(), t, "operate-data", ns, "create", "jobs"); err != nil {
				return err
			}
			op, err := a.actor(cmd.Context(), t)
			if err != nil {
				return err
			}
			strs := []string{"outboxReplay.schema=" + o.schema, "outboxReplay.tenant=" + o.tenant, "operator=" + op, "reason=" + o.reason}
			for _, kv := range [][2]string{{"from", o.from}, {"to", o.to}, {"eventId", o.eventID}, {"aggregateId", o.aggregateID},
				{"eventType", o.eventType}, {"topic", o.topic}, {"consumer", o.consumer}} {
				if kv[1] != "" {
					strs = append(strs, "outboxReplay."+kv[0]+"="+kv[1])
				}
			}
			set := func(confirm bool) []string {
				return []string{"outboxReplay.enabled=true", fmt.Sprintf("outboxReplay.rateLimit=%d", o.rate),
					fmt.Sprintf("outboxReplay.batchSize=%d", o.batch), fmt.Sprintf("outboxReplay.confirm=%t", confirm)}
			}
			dest := "topic gốc (mọi consumer)"
			if o.consumer != "" {
				dest = "<topic>.replay." + o.consumer
			}
			job := func(confirm bool) func(ctx context.Context) (map[string]any, error) {
				return func(ctx context.Context) (map[string]any, error) {
					m, err := a.renderJob(ctx, t, jobSpec{release: "vtctl", chart: "vtctl-jobs", template: "templates/outbox-replay-job.yaml", ns: ns, set: set(confirm), str: strs})
					if err != nil {
						return nil, err
					}
					name, err := a.createJob(ctx, t, ns, m, !confirm)
					if err != nil {
						return nil, err
					}
					res := map[string]any{"job": name, "namespace": ns}
					if !confirm {
						res["kiemMayChu"] = "đạt (--dry-run=server, chưa tạo Job)"
						return res, nil
					}
					res["theoDoi"] = fmt.Sprintf("kubectl -n %s logs -f %s", ns, name)
					if o.wait > 0 {
						if err := a.waitJob(ctx, t, ns, name, o.wait); err != nil {
							return res, fmt.Errorf("Job %s chưa hoàn tất trong %s (xem log, tiếp tục bằng --after-created-at/--after-id): %w", name, o.wait, err)
						}
						res["hoanTat"] = true
					}
					return res, nil
				}
			}
			return a.write(cmd.Context(), t, Write{
				Command: "outbox replay", Args: flagArgs(cmd),
				Summary: []string{fmt.Sprintf("Job outbox-replay ở namespace %s: schema %s, tenant %s, từ %s đến %s", ns, o.schema, o.tenant, or(o.from, "-"), or(o.to, "-")),
					"Đích: " + dest + "; người chạy " + op + "; lý do: " + o.reason},
				Preview: job(false),
				Do:      job(true),
			})
		},
	}
	f := replay.Flags()
	f.StringVar(&o.schema, "schema", "", "schema chứa <schema>.outbox_event")
	f.StringVar(&o.tenant, "tenant", "", "mã tenant")
	f.StringVar(&o.from, "from", "", "mốc đầu RFC 3339 (created_at >= from)")
	f.StringVar(&o.to, "to", "", "mốc cuối RFC 3339 (created_at <= to)")
	f.StringVar(&o.eventID, "event-id", "", "chỉ một sự kiện")
	f.StringVar(&o.aggregateID, "aggregate-id", "", "chỉ một đối tượng")
	f.StringVar(&o.eventType, "event-type", "", "chỉ một loại sự kiện")
	f.StringVar(&o.topic, "topic", "", "chỉ một topic")
	f.StringVar(&o.consumer, "consumer", "", "chỉ một consumer, qua <topic>.replay.<consumer>")
	f.StringVarP(&o.ns, "namespace", "n", "", "namespace chạy Job (mặc định namespaces.jobs của cell)")
	f.StringVar(&o.reason, "reason", "", "số phiếu sự cố (ghi sys.audit.v1 và kiểm toán vtctl)")
	f.IntVar(&o.rate, "rate-limit", 500, "thông điệp mỗi giây")
	f.IntVar(&o.batch, "batch-size", 500, "cỡ trang đọc")
	f.DurationVar(&o.wait, "wait", 0, "chờ Job xong")
	_ = replay.MarkFlagRequired("schema")
	_ = replay.MarkFlagRequired("tenant")
	cmd.AddCommand(replay)
	return cmd
}

func orgCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "org", Short: "Phát lại trạng thái tổ chức (DV-04-08)"}
	var o struct {
		tenant, asOf, ns, reason string
		entities, buckets        []string
		wait                     time.Duration
	}
	republish := &cobra.Command{
		Use:   "republish",
		Short: "Dựng và chạy Job org-republish của org-service",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			if err := uuidArg(o.tenant, "--tenant"); err != nil {
				return err
			}
			for _, e := range o.entities {
				if e != "unit" && e != "assignment" && e != "person" {
					return usagef("--entity chỉ nhận unit, assignment, person")
				}
			}
			for _, b := range o.buckets {
				var n int
				if _, err := fmt.Sscanf(b, "%d", &n); err != nil || n < 0 || n > 255 || fmt.Sprint(n) != b {
					return usagef("--bucket phải là số 0..255")
				}
			}
			if o.asOf != "" {
				if _, err := time.Parse(time.RFC3339, o.asOf); err != nil {
					return usagef("--as-of phải theo RFC 3339")
				}
			}
			if err := requireReason(o.reason); err != nil {
				return err
			}
			ns := o.ns
			if ns == "" {
				ns = t.Cell.Namespace("org", "okr-org")
			}
			if err := a.requireRBAC(cmd.Context(), t, "operate-data", ns, "create", "jobs"); err != nil {
				return err
			}
			op, err := a.actor(cmd.Context(), t)
			if err != nil {
				return err
			}
			strs := []string{"republish.tenant=" + o.tenant, "republish.operator=" + op}
			if o.asOf != "" {
				strs = append(strs, "republish.asOf="+o.asOf)
			}
			set := func(confirm bool) []string {
				s := []string{"republish.enabled=true", fmt.Sprintf("republish.dryRun=%t", !confirm)}
				if len(o.entities) > 0 {
					s = append(s, "republish.entities={"+strings.Join(o.entities, ",")+"}")
				}
				if len(o.buckets) > 0 {
					s = append(s, "republish.buckets={"+strings.Join(o.buckets, ",")+"}")
				}
				return s
			}
			job := func(confirm bool) func(ctx context.Context) (map[string]any, error) {
				return func(ctx context.Context) (map[string]any, error) {
					m, err := a.renderJob(ctx, t, jobSpec{release: "org-service", chart: "org-service", template: "templates/republish-job.yaml", ns: ns, set: set(confirm), str: strs})
					if err != nil {
						return nil, err
					}
					name, err := a.createJob(ctx, t, ns, m, !confirm)
					if err != nil {
						return nil, err
					}
					res := map[string]any{"job": name, "namespace": ns}
					if !confirm {
						res["kiemMayChu"] = "đạt (--dry-run=server, chưa tạo Job)"
						return res, nil
					}
					res["theoDoi"] = fmt.Sprintf("kubectl -n %s logs -f %s", ns, name)
					if o.wait > 0 {
						if err := a.waitJob(ctx, t, ns, name, o.wait); err != nil {
							return res, fmt.Errorf("Job %s chưa hoàn tất trong %s: %w", name, o.wait, err)
						}
						res["hoanTat"] = true
					}
					return res, nil
				}
			}
			return a.write(cmd.Context(), t, Write{
				Command: "org republish", Args: flagArgs(cmd),
				Summary: []string{fmt.Sprintf("Job org-republish ở namespace %s cho tenant %s (thực thể %s, bucket %s)", ns, o.tenant,
					or(strings.Join(o.entities, ","), "cả ba"), or(strings.Join(o.buckets, ","), "mọi bucket")),
					"Người chạy " + op + "; lý do: " + o.reason},
				Preview: job(false),
				Do:      job(true),
			})
		},
	}
	f := republish.Flags()
	f.StringVar(&o.tenant, "tenant", "", "mã tenant")
	f.StringSliceVar(&o.entities, "entity", nil, "unit, assignment, person; bỏ trống là cả ba")
	f.StringSliceVar(&o.buckets, "bucket", nil, "bucket 0..255; bỏ trống là mọi bucket")
	f.StringVar(&o.asOf, "as-of", "", "thời điểm hiệu lực RFC 3339")
	f.StringVarP(&o.ns, "namespace", "n", "", "namespace của org-service (mặc định namespaces.org của cell)")
	f.StringVar(&o.reason, "reason", "", "lý do, số phiếu (ghi kiểm toán vtctl)")
	f.DurationVar(&o.wait, "wait", 0, "chờ Job xong")
	_ = republish.MarkFlagRequired("tenant")
	cmd.AddCommand(republish)
	return cmd
}

func degradeCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "degrade", Short: "Mức suy giảm của cổng API cell (bộ điều khiển apisix-degrade-controller, TL-04 mục 6.9)"}
	var ns, secret string
	client := func(ctx context.Context, t config.Target, needToken bool) (string, string, error) {
		if t.Cell.DegradeURL == "" {
			return "", "", fmt.Errorf("cell %s chưa khai báo degradeURL", t.CellName)
		}
		if !needToken {
			return t.Cell.DegradeURL, "", nil
		}
		if err := a.requireRBAC(ctx, t, "operate-app", ns, "get", "secrets/"+secret); err != nil {
			return "", "", err
		}
		out, err := a.kubectl(t).Run(ctx, nil, ns, "get", "secret", secret, "-o", "jsonpath={.data.token}")
		if err != nil {
			return "", "", err
		}
		tok, err := decodeB64(strings.TrimSpace(string(out)))
		if err != nil || tok == "" {
			return "", "", fmt.Errorf("Secret %s không có khoá token", secret)
		}
		return t.Cell.DegradeURL, tok, nil
	}
	status := &cobra.Command{
		Use:   "status",
		Short: "Mức hiện hành, mức đặt tay và cảnh báo đang cháy",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			base, _, err := client(cmd.Context(), t, false)
			if err != nil {
				return err
			}
			body, err := a.degradeCall(cmd.Context(), http.MethodGet, base, "", nil)
			if err != nil {
				return err
			}
			a.print(body)
			return nil
		},
	}
	var reason string
	setLevel := func(name, use, short string, level func(args []string) (*int, error)) *cobra.Command {
		c := &cobra.Command{
			Use:   use,
			Short: short,
			RunE: func(cmd *cobra.Command, args []string) error {
				lv, err := level(args)
				if err != nil {
					return err
				}
				if err := requireReason(reason); err != nil {
					return err
				}
				t, err := a.target(true)
				if err != nil {
					return err
				}
				what := "Bỏ mức đặt tay, trở về mức theo cảnh báo và mức nền"
				if lv != nil {
					what = fmt.Sprintf("Đặt tay mức suy giảm %d", *lv)
				}
				return a.write(cmd.Context(), t, Write{
					Command: "degrade " + name, Args: flagArgs(cmd, args...),
					Summary: []string{what + " cho cổng API cell " + t.CellName + "; lý do: " + reason},
					Preview: func(ctx context.Context) (map[string]any, error) {
						base, _, err := client(ctx, t, false)
						if err != nil {
							return nil, err
						}
						cur, err := a.degradeCall(ctx, http.MethodGet, base, "", nil)
						if err != nil {
							return nil, err
						}
						return map[string]any{"hienTai": cur}, nil
					},
					Do: func(ctx context.Context) (map[string]any, error) {
						base, tok, err := client(ctx, t, true)
						if err != nil {
							return nil, err
						}
						op, err := a.actor(ctx, t)
						if err != nil {
							return nil, err
						}
						body := map[string]any{"level": lv, "reason": "OPS-14 " + op + ": " + reason}
						return a.degradeCall(ctx, http.MethodPost, base, tok, body)
					},
				})
			},
		}
		c.Flags().StringVar(&reason, "reason", "", "lý do (ghi nhật ký bộ điều khiển và kiểm toán vtctl)")
		return c
	}
	set := setLevel("set", "set <0..3>", "Đặt tay mức suy giảm 0..3", func(args []string) (*int, error) {
		if len(args) != 1 {
			return nil, usagef("cần đúng một mức 0..3")
		}
		var n int
		if _, err := fmt.Sscanf(args[0], "%d", &n); err != nil || n < 0 || n > 3 || fmt.Sprint(n) != args[0] {
			return nil, usagef("mức phải là 0, 1, 2 hoặc 3")
		}
		return &n, nil
	})
	clear := setLevel("clear", "clear", "Bỏ mức đặt tay", func(args []string) (*int, error) {
		if len(args) != 0 {
			return nil, usagef("clear không nhận tham số")
		}
		return nil, nil
	})
	cmd.PersistentFlags().StringVar(&ns, "namespace", "edge", "namespace của bộ điều khiển")
	cmd.PersistentFlags().StringVar(&secret, "secret", "apisix-degrade-webhook", "Secret chứa token webhook")
	cmd.AddCommand(status, set, clear)
	return cmd
}

func (a *App) degradeCall(ctx context.Context, method, base, token string, body any) (map[string]any, error) {
	var rd *strings.Reader
	if body != nil {
		raw, _ := json.Marshal(body)
		rd = strings.NewReader(string(raw))
	} else {
		rd = strings.NewReader("")
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(base, "/")+"/level", rd)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	hc := a.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 15 * time.Second}
	}
	res, err := hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("gọi bộ điều khiển suy giảm: %w", err)
	}
	defer res.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	switch res.StatusCode {
	case http.StatusOK:
		return out, nil
	case http.StatusUnauthorized:
		return nil, fmt.Errorf("bộ điều khiển từ chối token (401)")
	case http.StatusUnprocessableEntity:
		return nil, fmt.Errorf("mức ngoài 0..3 (422)")
	case http.StatusBadGateway:
		return nil, fmt.Errorf("bộ điều khiển không ghi được ApisixPluginConfig (502); xem runbook degradation mục 5")
	default:
		return nil, fmt.Errorf("bộ điều khiển trả HTTP %d", res.StatusCode)
	}
}

func or(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

func decodeB64(s string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	return string(b), err
}
