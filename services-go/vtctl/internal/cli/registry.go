package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"github.com/google/uuid"
	"github.com/spf13/cobra"

	"vn.viettel.okrkpi/services-go/vtctl/internal/api"
)

// keyFlag là Idempotency-Key: mặc định sinh mới; người chạy truyền lại khoá cũ để gửi lại an toàn sau lỗi mạng.
func keyFlag(cmd *cobra.Command, key *string) {
	cmd.Flags().StringVar(key, "idempotency-key", "", "Idempotency-Key; bỏ trống là sinh mới, truyền lại khoá cũ khi gửi lại")
}

func ensureKey(k string) (string, error) {
	if k == "" {
		return api.NewKey(), nil
	}
	if _, err := uuid.Parse(k); err != nil {
		return "", usagef("--idempotency-key phải là UUID")
	}
	return k, nil
}

func uuidArg(s, what string) error {
	if _, err := uuid.Parse(s); err != nil {
		return usagef("%s %q không phải UUID", what, s)
	}
	return nil
}

func decodeMap(res *api.Response) map[string]any {
	out := map[string]any{"http": res.Status}
	var body map[string]any
	if len(res.Body) > 0 && json.Unmarshal(res.Body, &body) == nil {
		for _, k := range []string{"id", "status", "currentStep", "sagaStatus", "rowVersion", "pauseRequested", "jobId", "message"} {
			if v, ok := body[k]; ok {
				out[k] = v
			}
		}
	}
	if res.Location != "" {
		out["location"] = res.Location
	}
	if res.ETag != "" {
		out["etag"] = res.ETag
	}
	return out
}

func tenantCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "tenant", Short: "Trạng thái, kích hoạt lại và tạm ngừng tenant (máy trạng thái DV-03-03, SVC-03)"}
	status := &cobra.Command{
		Use:   "status <tenant-id>",
		Short: "Xem bản ghi danh bạ, trạng thái và cell hiện hành của tenant",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := uuidArg(args[0], "mã tenant"); err != nil {
				return err
			}
			t, err := a.target(false)
			if err != nil {
				return err
			}
			reg, err := a.registry(t)
			if err != nil {
				return err
			}
			var tn map[string]any
			res, err := reg.Get(cmd.Context(), "/registry/tenants/"+args[0], true, &tn)
			if err != nil {
				return err
			}
			if a.JSON {
				a.print(tn)
				return nil
			}
			out := map[string]any{"etag": res.ETag}
			for _, k := range []string{"code", "name", "status", "tenancyModel", "accountCount", "maxAccounts", "currentCell"} {
				out[k] = tn[k]
			}
			a.print(out)
			return nil
		},
	}
	// transition dựng lệnh đổi trạng thái tenant qua một API config (If-Match từ ETag, Idempotency-Key, aal2).
	transition := func(name, short, op, summary string) *cobra.Command {
		var reason, key string
		c := &cobra.Command{
			Use:   name + " <tenant-id>",
			Short: short,
			Args:  cobra.ExactArgs(1),
			RunE: func(cmd *cobra.Command, args []string) error {
				if err := uuidArg(args[0], "mã tenant"); err != nil {
					return err
				}
				if err := requireReason(reason); err != nil {
					return err
				}
				k, err := ensureKey(key)
				if err != nil {
					return err
				}
				t, err := a.target(false)
				if err != nil {
					return err
				}
				reg, err := a.registry(t)
				if err != nil {
					return err
				}
				path := "/config/tenants/" + args[0]
				var cur map[string]any
				read := func(ctx context.Context) (*api.Response, error) { return reg.Get(ctx, path, false, &cur) }
				return a.write(cmd.Context(), t, Write{
					Command: "tenant " + name, Args: flagArgs(cmd, args...), Key: k,
					Summary: []string{"Chuyển tenant " + args[0] + " " + summary + "; lý do: " + reason},
					Preview: func(ctx context.Context) (map[string]any, error) {
						res, err := read(ctx)
						if err != nil {
							return nil, err
						}
						return map[string]any{"trangThaiHienTai": cur["status"], "etag": res.ETag}, nil
					},
					Do: func(ctx context.Context) (map[string]any, error) {
						got, err := read(ctx)
						if err != nil {
							return nil, err
						}
						res, err := reg.Do(ctx, api.Request{Method: http.MethodPost, Path: path + "/" + op, IfMatch: got.ETag,
							IdempotencyKey: k, AAL2: true, Body: map[string]any{"reason": reason}})
						if err != nil {
							return nil, err
						}
						return decodeMap(res), nil
					},
				})
			},
		}
		c.Flags().StringVar(&reason, "reason", "", "lý do, số văn bản (ghi registry.tenant_status_log)")
		keyFlag(c, &key)
		return c
	}
	activate := transition("activate", "Kích hoạt hoặc kích hoạt lại tenant (SAN_SANG hoặc TAM_NGUNG sang DANG_VAN_HANH, aal2)",
		"activate", "sang DANG_VAN_HANH (activateConfigTenant)")
	suspend := transition("suspend", "Tạm ngừng tenant khi hết hạn hợp đồng hoặc sự cố an ninh (DANG_VAN_HANH sang TAM_NGUNG, aal2)",
		"suspend", "sang TAM_NGUNG (suspendConfigTenant), dữ liệu giữ nguyên, người dùng của tenant không đăng nhập được")
	cmd.AddCommand(status, activate, suspend)
	return cmd
}

func cellCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "cell", Short: "Danh sách cell và chuyển tenant giữa cell (bốn mắt ở máy chủ, ADR-29)"}
	list := &cobra.Command{
		Use:   "list",
		Short: "Liệt kê cell trong registry",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(false)
			if err != nil {
				return err
			}
			reg, err := a.registry(t)
			if err != nil {
				return err
			}
			var page struct {
				Items []map[string]any `json:"items"`
			}
			if _, err := reg.Get(cmd.Context(), "/registry/cells?limit=200", false, &page); err != nil {
				return err
			}
			if a.JSON {
				a.print(page.Items)
				return nil
			}
			for _, c := range page.Items {
				fmt.Fprintf(a.Out, "%v\t%v\t%v\t%v\n", c["code"], c["status"], c["tenancyModel"], c["id"])
			}
			return nil
		},
	}
	move := &cobra.Command{Use: "move", Short: "Đề nghị, duyệt, theo dõi, huỷ lệnh chuyển cell"}

	var req struct{ target, model, region, planned, reason, key string }
	request := &cobra.Command{
		Use:   "request <tenant-id>",
		Short: "Đề nghị chuyển tenant sang cell khác (requestTenantMove, người thứ nhất)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := uuidArg(args[0], "mã tenant"); err != nil {
				return err
			}
			if err := uuidArg(req.target, "--target-cell"); err != nil {
				return err
			}
			if err := requireReason(req.reason); err != nil {
				return err
			}
			body := map[string]any{"targetCellId": req.target, "reason": req.reason}
			if req.model != "" {
				body["targetTenancyModel"] = req.model
			}
			if req.region != "" {
				body["dataRegion"] = req.region
			}
			if req.planned != "" {
				p, err := time.Parse(time.RFC3339, req.planned)
				if err != nil {
					return usagef("--planned-at phải theo RFC 3339: %v", err)
				}
				body["plannedAt"] = p.Format(time.RFC3339)
			}
			k, err := ensureKey(req.key)
			if err != nil {
				return err
			}
			t, err := a.target(false)
			if err != nil {
				return err
			}
			reg, err := a.registry(t)
			if err != nil {
				return err
			}
			tp := "/registry/tenants/" + args[0]
			return a.write(cmd.Context(), t, Write{
				Command: "cell move request", Args: flagArgs(cmd, args...), Key: k,
				Summary: []string{fmt.Sprintf("Đề nghị chuyển tenant %s sang cell %s (trạng thái CHO_DUYET, chờ người duyệt thứ hai)", args[0], req.target),
					"Lý do: " + req.reason},
				Preview: func(ctx context.Context) (map[string]any, error) {
					var tn map[string]any
					res, err := reg.Get(ctx, tp, true, &tn)
					if err != nil {
						return nil, err
					}
					return map[string]any{"trangThaiTenant": tn["status"], "cellHienTai": tn["currentCell"], "etag": res.ETag}, nil
				},
				Do: func(ctx context.Context) (map[string]any, error) {
					cur, err := reg.Get(ctx, tp, true, nil)
					if err != nil {
						return nil, err
					}
					res, err := reg.Do(ctx, api.Request{Method: http.MethodPost, Path: tp + "/move", IfMatch: cur.ETag,
						IdempotencyKey: k, AAL2: true, Body: body})
					if err != nil {
						return nil, err
					}
					return decodeMap(res), nil
				},
			})
		},
	}
	request.Flags().StringVar(&req.target, "target-cell", "", "mã (UUID) cell đích, xem `vtctl cell list`")
	request.Flags().StringVar(&req.model, "model", "", "mô hình thuê đích (POOL, BRIDGE, SILO)")
	request.Flags().StringVar(&req.region, "data-region", "", "vùng dữ liệu đích")
	request.Flags().StringVar(&req.planned, "planned-at", "", "thời điểm cắt chuyển dự kiến, RFC 3339")
	request.Flags().StringVar(&req.reason, "reason", "", "lý do, số văn bản")
	keyFlag(request, &req.key)
	_ = request.MarkFlagRequired("target-cell")

	moveAction := func(use, short, op, verb string) *cobra.Command {
		var comment, key string
		c := &cobra.Command{
			Use:   use + " <tenant-id> <move-id>",
			Short: short,
			Args:  cobra.ExactArgs(2),
			RunE: func(cmd *cobra.Command, args []string) error {
				if err := uuidArg(args[0], "mã tenant"); err != nil {
					return err
				}
				if err := uuidArg(args[1], "mã lệnh chuyển"); err != nil {
					return err
				}
				k, err := ensureKey(key)
				if err != nil {
					return err
				}
				t, err := a.target(false)
				if err != nil {
					return err
				}
				reg, err := a.registry(t)
				if err != nil {
					return err
				}
				mp := "/registry/tenants/" + args[0] + "/move/" + args[1]
				var mv map[string]any
				return a.write(cmd.Context(), t, Write{
					Command: "cell move " + use, Args: flagArgs(cmd, args...), Key: k,
					Summary: []string{fmt.Sprintf("%s lệnh chuyển cell %s của tenant %s (%s)", verb, args[1], args[0], op)},
					Preview: func(ctx context.Context) (map[string]any, error) {
						res, err := reg.Get(ctx, mp, true, &mv)
						if err != nil {
							return nil, err
						}
						return map[string]any{"trangThaiLenh": mv["status"], "nguoiDeNghi": mv["requestedBy"], "etag": res.ETag}, nil
					},
					Do: func(ctx context.Context) (map[string]any, error) {
						cur, err := reg.Get(ctx, mp, true, nil)
						if err != nil {
							return nil, err
						}
						var body any
						if comment != "" {
							body = map[string]any{"comment": comment}
						}
						res, err := reg.Do(ctx, api.Request{Method: http.MethodPost, Path: mp + "/" + use, IfMatch: cur.ETag,
							IdempotencyKey: k, AAL2: true, Body: body})
						if err != nil {
							return nil, err
						}
						return decodeMap(res), nil
					},
				})
			},
		}
		c.Flags().StringVar(&comment, "comment", "", "ý kiến kèm theo")
		keyFlag(c, &key)
		return c
	}
	status := &cobra.Command{
		Use:   "status <tenant-id> <move-id>",
		Short: "Tiến độ saga chuyển cell (getTenantMove)",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := uuidArg(args[0], "mã tenant"); err != nil {
				return err
			}
			if err := uuidArg(args[1], "mã lệnh chuyển"); err != nil {
				return err
			}
			t, err := a.target(false)
			if err != nil {
				return err
			}
			reg, err := a.registry(t)
			if err != nil {
				return err
			}
			var mv map[string]any
			if _, err := reg.Get(cmd.Context(), "/registry/tenants/"+url.PathEscape(args[0])+"/move/"+url.PathEscape(args[1]), true, &mv); err != nil {
				return err
			}
			a.print(mv)
			return nil
		},
	}
	move.AddCommand(request,
		moveAction("approve", "Phê duyệt lệnh chuyển cell (approveTenantMove, người thứ hai khác người đề nghị)", "approveTenantMove", "Phê duyệt"),
		status,
		moveAction("cancel", "Huỷ lệnh chuyển cell trước cắt chuyển (cancelTenantMove)", "cancelTenantMove", "Huỷ"))
	cmd.AddCommand(list, move)
	return cmd
}

func periodLockCmd(a *App) *cobra.Command {
	cmd := &cobra.Command{Use: "period-lock", Short: "Saga khoá kỳ của tenant ở cell (MOD-08, SVC-06)"}
	status := &cobra.Command{
		Use:   "status <period-id>",
		Short: "Tiến trình khoá kỳ (getPeriodLockProgress)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := uuidArg(args[0], "mã kỳ"); err != nil {
				return err
			}
			t, err := a.target(true)
			if err != nil {
				return err
			}
			c, err := a.cellAPI(cmd.Context(), t)
			if err != nil {
				return err
			}
			var p map[string]any
			if _, err := c.Get(cmd.Context(), "/periods/"+args[0]+"/lock", false, &p); err != nil {
				return err
			}
			if a.JSON {
				a.print(p)
				return nil
			}
			a.print(map[string]any{"sagaStatus": p["sagaStatus"], "totals": p["totals"], "pauseRequested": p["pauseRequested"],
				"estimatedFinishAt": p["estimatedFinishAt"], "budgetFinishAt": p["budgetFinishAt"]})
			return nil
		},
	}
	control := func(use, short, op string, ifMatch bool) *cobra.Command {
		var comment, key string
		var profiles []string
		c := &cobra.Command{
			Use:   use + " <period-id>",
			Short: short,
			Args:  cobra.ExactArgs(1),
			RunE: func(cmd *cobra.Command, args []string) error {
				if err := uuidArg(args[0], "mã kỳ"); err != nil {
					return err
				}
				for _, p := range profiles {
					if err := uuidArg(p, "mã hồ sơ"); err != nil {
						return err
					}
				}
				if len(profiles) > 1000 {
					return usagef("tối đa 1.000 hồ sơ mỗi lần")
				}
				k, err := ensureKey(key)
				if err != nil {
					return err
				}
				t, err := a.target(true)
				if err != nil {
					return err
				}
				cl, err := a.cellAPI(cmd.Context(), t)
				if err != nil {
					return err
				}
				base := "/periods/" + args[0] + "/lock"
				body := map[string]any{}
				if comment != "" {
					body["comment"] = comment
				}
				if use == "retry-failed" {
					body = map[string]any{}
					if len(profiles) > 0 {
						body["profileIds"] = profiles
					}
				}
				what := map[string]string{"pause": "Tạm dừng saga khoá kỳ sau bước hiện tại", "resume": "Tiếp tục saga khoá kỳ",
					"retry-failed": "Chạy lại hồ sơ tính điểm lỗi"}[use]
				scope := "toàn bộ hàng đợi lỗi"
				if len(profiles) > 0 {
					scope = fmt.Sprintf("%d hồ sơ", len(profiles))
				}
				sum := []string{fmt.Sprintf("%s của kỳ %s (%s)", what, args[0], op)}
				if use == "retry-failed" {
					sum = append(sum, "Phạm vi: "+scope)
				}
				return a.write(cmd.Context(), t, Write{
					Command: "period-lock " + use, Args: flagArgs(cmd, args...), Key: k, Summary: sum,
					Preview: func(ctx context.Context) (map[string]any, error) {
						var p map[string]any
						res, err := cl.Get(ctx, base, false, &p)
						if err != nil {
							return nil, err
						}
						return map[string]any{"sagaStatus": p["sagaStatus"], "totals": p["totals"], "etag": res.ETag}, nil
					},
					Do: func(ctx context.Context) (map[string]any, error) {
						r := api.Request{Method: http.MethodPost, Path: base + "/" + use, IdempotencyKey: k, AAL2: true, Body: body}
						if ifMatch {
							cur, err := cl.Get(ctx, base, false, nil)
							if err != nil {
								return nil, err
							}
							r.IfMatch = cur.ETag
						}
						res, err := cl.Do(ctx, r)
						if err != nil {
							return nil, err
						}
						return decodeMap(res), nil
					},
				})
			},
		}
		keyFlag(c, &key)
		if use == "retry-failed" {
			c.Flags().StringSliceVar(&profiles, "profile", nil, "chỉ chạy lại các hồ sơ này; bỏ trống là toàn bộ hàng đợi lỗi")
		} else {
			c.Flags().StringVar(&comment, "comment", "", "ghi chú (ghi sổ cái)")
		}
		return c
	}
	cmd.AddCommand(status,
		control("pause", "Tạm dừng saga khoá kỳ sau bước hiện tại (pausePeriodLock)", "pausePeriodLock", true),
		control("resume", "Tiếp tục saga khoá kỳ (resumePeriodLock)", "resumePeriodLock", true),
		control("retry-failed", "Chạy lại hồ sơ tính điểm lỗi (retryFailedPeriodLock)", "retryFailedPeriodLock", false))
	return cmd
}
