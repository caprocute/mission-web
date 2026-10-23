package cli

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"vn.viettel.okrkpi/services-go/vtctl/internal/auth"
	"vn.viettel.okrkpi/services-go/vtctl/internal/dlq"
)

func dlqCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "dlq", Short: "Hàng thư chết <topic>.dlq.<consumer> (OPS-24)"}
	cmd.AddCommand(dlqListCmd(a), dlqInspectCmd(a), dlqReplayCmd(a), dlqSkipCmd(a))
	return cmd
}

type dlqTarget struct {
	topic, consumer string
	partition       int32
	from, to        int64
}

func (d *dlqTarget) flags(cmd *cobra.Command, needRange bool) {
	cmd.Flags().StringVar(&d.topic, "topic", "", "topic gốc, ví dụ task.task.assigned.v1")
	cmd.Flags().StringVar(&d.consumer, "consumer", "", "tên consumer (hậu tố của <topic>.dlq.<consumer>)")
	cmd.Flags().Int32Var(&d.partition, "partition", -1, "phân vùng DLQ; bỏ trống là mọi phân vùng")
	cmd.Flags().Int64Var(&d.from, "from", 0, "offset đầu (gồm)")
	cmd.Flags().Int64Var(&d.to, "to", -1, "offset cuối (gồm)")
	_ = cmd.MarkFlagRequired("topic")
	_ = cmd.MarkFlagRequired("consumer")
	if needRange {
		_ = cmd.MarkFlagRequired("from")
		_ = cmd.MarkFlagRequired("to")
	}
}

func (d *dlqTarget) rng() dlq.Range { return dlq.Range{Partition: d.partition, From: d.from, To: d.to} }

func dlqListCmd(a *App) *cobra.Command {
	var filter string
	cmd := &cobra.Command{
		Use:   "list",
		Short: "Liệt kê DLQ và số bản ghi chưa xử lý",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			ops, err := a.kafka(t)
			if err != nil {
				return err
			}
			defer ops.Close()
			qs, err := ops.List(cmd.Context(), filter)
			if err != nil {
				return err
			}
			if a.JSON {
				a.print(qs)
				return nil
			}
			if len(qs) == 0 {
				fmt.Fprintln(a.Out, "Không có hàng thư chết nào.")
				return nil
			}
			for _, q := range qs {
				fmt.Fprintf(a.Out, "%s\tchưa xử lý %d\n", q.Topic, q.Pending)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&filter, "filter", "", "chỉ topic chứa chuỗi này")
	return cmd
}

func dlqInspectCmd(a *App) *cobra.Command {
	var d dlqTarget
	var limit int
	cmd := &cobra.Command{
		Use:   "inspect",
		Short: "Xem khoá, header lỗi, kích thước và băm thân của bản ghi DLQ (không in thân)",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			if d.to < 0 {
				d.to = d.from + int64(limit) - 1
			}
			ops, err := a.kafka(t)
			if err != nil {
				return err
			}
			defer ops.Close()
			ms, err := ops.Read(cmd.Context(), d.topic, d.consumer, d.rng(), limit)
			if err != nil {
				return err
			}
			if a.JSON {
				a.print(ms)
				return nil
			}
			for _, m := range ms {
				fmt.Fprintf(a.Out, "%d/%d\tkhoá=%s\t%d byte\tsha256=%s\n", m.Partition, m.Offset, m.Key, m.ValueBytes, m.ValueSHA256[:16])
				for _, k := range []string{"x-dlq-error-code", "x-dlq-failed-at", "x-inbox-dlq-reason", "x-inbox-dlq-failed-at", "ce_type", "ce_id", "id"} {
					if v, ok := m.Headers[k]; ok {
						fmt.Fprintf(a.Out, "\t%s=%s\n", k, v)
					}
				}
			}
			fmt.Fprintf(a.Out, "%d bản ghi\n", len(ms))
			return nil
		},
	}
	d.flags(cmd, false)
	cmd.Flags().IntVar(&limit, "limit", 20, "số bản ghi tối đa")
	return cmd
}

func dlqReplayCmd(a *App) *cobra.Command {
	var d dlqTarget
	var reason string
	cmd := &cobra.Command{
		Use:   "replay",
		Short: "Phát lại dải offset của DLQ qua <topic>.replay.<consumer>",
		Long: "Đọc <topic>.dlq.<consumer> trong dải --from..--to (gồm hai đầu) và ghi nguyên khoá, thân, header CloudEvents " +
			"sang <topic>.replay.<consumer>, bỏ header lỗi, thêm ce_replayed=true. Chỉ consumer đó đọc lại; inbox của consumer " +
			"khử trùng nên phát lại hai lần không xử lý trùng. Topic trung gian không được MirrorMaker 2 nhân bản.",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			if err := dlq.Validate(d.topic, d.consumer); err != nil {
				return usagef("%v", err)
			}
			if err := requireReason(reason); err != nil {
				return err
			}
			ops, err := a.kafka(t)
			if err != nil {
				return err
			}
			defer ops.Close()
			part := "mọi phân vùng"
			if d.partition >= 0 {
				part = fmt.Sprintf("phân vùng %d", d.partition)
			}
			run := func(dry bool) func(ctx context.Context) (map[string]any, error) {
				return func(ctx context.Context) (map[string]any, error) {
					r, err := ops.Replay(ctx, d.topic, d.consumer, d.rng(), dry)
					if err != nil {
						return nil, err
					}
					return map[string]any{"soBanGhi": r.Count, "dlq": r.DLQTopic, "topicPhatLai": r.ReplayTopic,
						"dauDai": r.First, "cuoiDai": r.Last}, nil
				}
			}
			return a.write(cmd.Context(), t, Write{
				Command: "dlq replay",
				Args:    flagArgs(cmd),
				Summary: []string{
					fmt.Sprintf("Đọc %s, offset %d..%d, %s", dlq.DLQTopic(d.topic, d.consumer), d.from, d.to, part),
					fmt.Sprintf("Ghi sang %s (chỉ consumer %s đọc), thêm ce_replayed=true", dlq.ReplayTopic(d.topic, d.consumer), d.consumer),
					fmt.Sprintf("Đánh dấu đã xử lý trong nhóm %s; lý do: %s", dlq.Group, reason),
				},
				Preview: run(true),
				Do:      run(false),
			})
		},
	}
	d.flags(cmd, true)
	cmd.Flags().StringVar(&reason, "reason", "", "số phiếu sự cố, lý do phát lại (ghi kiểm toán)")
	return cmd
}

func dlqSkipCmd(a *App) *cobra.Command {
	var d dlqTarget
	var reason, approver string
	cmd := &cobra.Command{
		Use:   "skip",
		Short: "Bỏ qua có duyệt bản ghi không hợp lệ vĩnh viễn (cần người duyệt thứ hai)",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			if err := dlq.Validate(d.topic, d.consumer); err != nil {
				return usagef("%v", err)
			}
			if d.partition < 0 || d.to < 0 {
				return usagef("cần --partition và --to (bỏ qua tới hết offset này)")
			}
			if err := requireReason(reason); err != nil {
				return err
			}
			approver = strings.TrimSpace(approver)
			if approver == "" {
				return usagef("cần --approver: tài khoản người duyệt thứ hai")
			}
			if a.confirmed() {
				s, err := a.session(t)
				if err != nil {
					return err
				}
				// Bỏ qua là chấp nhận mất sự kiện: người chạy phải vừa xác thực bậc 2 và khác người duyệt.
				if _, err := s.AccessToken(cmd.Context(), true); err != nil {
					return err
				}
				c, err := s.Claims()
				if err != nil {
					return err
				}
				if strings.EqualFold(c.Actor(), approver) || strings.EqualFold(c.Sub, approver) {
					return fmt.Errorf("người duyệt phải khác người chạy (%s)", c.Actor())
				}
				if !c.AAL2Fresh(a.Now(), auth.StepUpMaxAge) {
					return fmt.Errorf("cần xác thực bước lên trong %s", auth.StepUpMaxAge)
				}
			}
			ops, err := a.kafka(t)
			if err != nil {
				return err
			}
			defer ops.Close()
			run := func(dry bool) func(ctx context.Context) (map[string]any, error) {
				return func(ctx context.Context) (map[string]any, error) {
					next, err := ops.Skip(ctx, d.topic, d.consumer, d.partition, d.to, dry)
					if err != nil {
						return nil, err
					}
					return map[string]any{"viTriMoi": next, "nguoiDuyet": approver}, nil
				}
			}
			return a.write(cmd.Context(), t, Write{
				Command: "dlq skip",
				Args:    flagArgs(cmd),
				Summary: []string{
					fmt.Sprintf("Bỏ qua %s phân vùng %d tới hết offset %d (không phát lại, không xoá)", dlq.DLQTopic(d.topic, d.consumer), d.partition, d.to),
					fmt.Sprintf("Người duyệt: %s; lý do: %s", approver, reason),
				},
				Preview: run(true),
				Do:      run(false),
			})
		},
	}
	d.flags(cmd, false)
	cmd.Flags().StringVar(&reason, "reason", "", "lý do, số phiếu (ghi kiểm toán)")
	cmd.Flags().StringVar(&approver, "approver", "", "tài khoản người duyệt thứ hai")
	return cmd
}

func consumerCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "consumer", Short: "Độ trễ và khởi động lại consumer Kafka"}
	var groups []string
	lag := &cobra.Command{
		Use:   "lag",
		Short: "Độ trễ của nhóm consumer",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			ops, err := a.kafka(t)
			if err != nil {
				return err
			}
			defer ops.Close()
			ls, err := ops.Lags(cmd.Context(), groups...)
			if err != nil {
				return err
			}
			if a.JSON {
				a.print(ls)
				return nil
			}
			for _, l := range ls {
				fmt.Fprintf(a.Out, "%s\t%s\ttrễ %d\n", l.Group, l.State, l.Total)
			}
			return nil
		},
	}
	lag.Flags().StringSliceVar(&groups, "group", nil, "nhóm consumer; bỏ trống là mọi nhóm")

	var deployment, ns, reason string
	var wait time.Duration
	restart := &cobra.Command{
		Use:   "restart",
		Short: "Khởi động lại Deployment của consumer (kubectl rollout restart, quyền operate-app)",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(true)
			if err != nil {
				return err
			}
			if !k8sName.MatchString(deployment) || !k8sName.MatchString(ns) {
				return usagef("--deployment và --namespace phải là tên Kubernetes hợp lệ")
			}
			if err := requireReason(reason); err != nil {
				return err
			}
			k := a.kubectl(t)
			if err := a.requireRBAC(cmd.Context(), t, "operate-app", ns, "patch", "deployments"); err != nil {
				return err
			}
			return a.write(cmd.Context(), t, Write{
				Command: "consumer restart",
				Args:    flagArgs(cmd),
				Summary: []string{fmt.Sprintf("kubectl rollout restart deployment/%s -n %s (context %s); lý do: %s", deployment, ns, t.Cell.KubeContext, reason)},
				Preview: func(ctx context.Context) (map[string]any, error) {
					out, err := k.Run(ctx, nil, ns, "get", "deployment/"+deployment, "-o", "jsonpath={.status.readyReplicas}/{.spec.replicas}")
					if err != nil {
						return nil, err
					}
					return map[string]any{"sanSang": strings.TrimSpace(string(out))}, nil
				},
				Do: func(ctx context.Context) (map[string]any, error) {
					if _, err := k.Run(ctx, nil, ns, "rollout", "restart", "deployment/"+deployment); err != nil {
						return nil, err
					}
					res := map[string]any{"deployment": deployment, "namespace": ns}
					if wait > 0 {
						if _, err := k.Run(ctx, nil, ns, "rollout", "status", "deployment/"+deployment, "--timeout="+wait.String()); err != nil {
							return res, fmt.Errorf("đã yêu cầu khởi động lại nhưng chưa sẵn sàng trong %s: %w", wait, err)
						}
						res["sanSang"] = true
					}
					return res, nil
				},
			})
		},
	}
	restart.Flags().StringVar(&deployment, "deployment", "", "tên Deployment của consumer")
	restart.Flags().StringVarP(&ns, "namespace", "n", "", "namespace ứng dụng")
	restart.Flags().StringVar(&reason, "reason", "", "lý do, số phiếu (ghi kiểm toán)")
	restart.Flags().DurationVar(&wait, "wait", 5*time.Minute, "chờ rollout xong; 0 là không chờ")
	_ = restart.MarkFlagRequired("deployment")
	_ = restart.MarkFlagRequired("namespace")
	cmd.AddCommand(lag, restart)
	return cmd
}
