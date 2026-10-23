// Package actuate áp mức suy giảm ra cụm (INF-25): cổng APISIX (nhãn vt.io/degrade-level cùng bộ plugin
// từng mức do DV-01-02 dựng) và tác động ngoài cổng (tạm dừng ScaledObject KEDA của tác vụ nền không gấp).
package actuate

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"vn.viettel.okrkpi/services-go/degrade-controller/internal/kube"
)

// Nhãn và chú thích.
const (
	ManagedSelector = "vt.io/degrade-managed=true"
	LevelLabel      = "vt.io/degrade-level"
	ClassLabel      = "vt.io/priority-class"
	PauseFromLabel  = "vt.io/degrade-pause-from"
	KedaPaused      = "autoscaling.keda.sh/paused"
	PausedBy        = "vt.io/degrade-paused-by"
	controllerName  = "degrade-controller"
)

// Actuator áp một mức.
type Actuator interface {
	Name() string
	Apply(ctx context.Context, level int) error
}

// ApplyAll áp mức qua mọi actuator: tăng mức theo thứ tự khai báo, hạ mức theo thứ tự ngược
// (gỡ tác động ngược với lúc bật). Lỗi của một actuator không chặn các actuator sau.
func ApplyAll(ctx context.Context, acts []Actuator, prev, level int) (applied []string, err error) {
	order := make([]Actuator, len(acts))
	copy(order, acts)
	if level < prev {
		for i, j := 0, len(order)-1; i < j; i, j = i+1, j-1 {
			order[i], order[j] = order[j], order[i]
		}
	}
	var errs []error
	for _, a := range order {
		if e := a.Apply(ctx, level); e != nil {
			errs = append(errs, fmt.Errorf("%s: %w", a.Name(), e))
			continue
		}
		applied = append(applied, a.Name())
	}
	return applied, errors.Join(errs...)
}

// Kube là phần client Kubernetes actuator cần.
type Kube interface {
	List(ctx context.Context, r kube.Resource, ns, selector string) ([]kube.Object, error)
	MergePatch(ctx context.Context, r kube.Resource, ns, name string, patch any) error
}

// Gateway ghi nhãn mức và spec.plugins vào mọi ApisixPluginConfig có nhãn vt.io/degrade-managed=true
// trong các namespace được phép; bộ plugin lấy từ LevelsDir/p<lớp>-<mức>.json (ConfigMap
// apisix-degrade-levels của chart api-gateway).
type Gateway struct {
	Kube       Kube
	Namespaces []string
	LevelsDir  string
}

// Name thực hiện Actuator.
func (g *Gateway) Name() string { return "gateway" }

// Apply thực hiện Actuator.
func (g *Gateway) Apply(ctx context.Context, level int) error {
	for _, ns := range g.Namespaces {
		items, err := g.Kube.List(ctx, kube.ApisixPluginConfig, ns, ManagedSelector)
		if err != nil {
			return err
		}
		for _, it := range items {
			cls := it.Metadata.Labels[ClassLabel]
			if cls == "" {
				cls = "2"
			}
			plugins, err := g.variant(cls, level)
			if err != nil {
				return err
			}
			patch := map[string]any{
				"metadata": map[string]any{"labels": map[string]string{LevelLabel: strconv.Itoa(level)}},
				"spec":     map[string]any{"plugins": plugins},
			}
			if err := g.Kube.MergePatch(ctx, kube.ApisixPluginConfig, ns, it.Metadata.Name, patch); err != nil {
				return err
			}
		}
	}
	return nil
}

func (g *Gateway) variant(cls string, level int) (json.RawMessage, error) {
	if _, err := strconv.Atoi(cls); err != nil {
		return nil, fmt.Errorf("lớp ưu tiên %q không hợp lệ", cls)
	}
	raw, err := os.ReadFile(filepath.Join(g.LevelsDir, fmt.Sprintf("p%s-%d.json", cls, level)))
	if err != nil {
		return nil, fmt.Errorf("thiếu bộ plugin lớp %s mức %d: %w", cls, level, err)
	}
	if !json.Valid(raw) {
		return nil, fmt.Errorf("bộ plugin lớp %s mức %d không phải JSON", cls, level)
	}
	return raw, nil
}

// CurrentLevel đọc mức đang ghi trên cụm (cao nhất trong các ApisixPluginConfig được quản lý), để khởi
// động lại pod không làm tụt mức.
func (g *Gateway) CurrentLevel(ctx context.Context) (int, bool) {
	best, found := 0, false
	for _, ns := range g.Namespaces {
		items, err := g.Kube.List(ctx, kube.ApisixPluginConfig, ns, ManagedSelector)
		if err != nil {
			continue
		}
		for _, it := range items {
			if n, err := strconv.Atoi(it.Metadata.Labels[LevelLabel]); err == nil && n >= 0 && n <= 3 {
				found = true
				if n > best {
					best = n
				}
			}
		}
	}
	return best, found
}

// BackgroundJobs tạm dừng ScaledObject của tác vụ nền không gấp: đối tượng gắn nhãn
// vt.io/degrade-pause-from=<n> bị đặt autoscaling.keda.sh/paused=true khi mức ≥ n (TL-04 mức 2: đồng bộ
// tích hợp, tính lại hàng loạt không thuộc kỳ đang đóng). Chỉ gỡ chú thích tạm dừng do chính bộ điều khiển
// đặt (đánh dấu bằng vt.io/degrade-paused-by), không đụng tạm dừng tay của người khác.
type BackgroundJobs struct {
	Kube       Kube
	Namespaces []string
}

// Name thực hiện Actuator.
func (b *BackgroundJobs) Name() string { return "background-jobs" }

// Apply thực hiện Actuator.
func (b *BackgroundJobs) Apply(ctx context.Context, level int) error {
	for _, ns := range b.Namespaces {
		items, err := b.Kube.List(ctx, kube.ScaledObject, ns, PauseFromLabel)
		if err != nil {
			return err
		}
		for _, it := range items {
			from, err := strconv.Atoi(it.Metadata.Labels[PauseFromLabel])
			if err != nil || from < 1 || from > 3 {
				continue
			}
			ann := it.Metadata.Annotations
			ours := ann[PausedBy] == controllerName
			var patch map[string]any
			switch {
			case level >= from && ann[KedaPaused] != "true":
				patch = map[string]any{KedaPaused: "true", PausedBy: controllerName}
			case level < from && ours:
				patch = map[string]any{KedaPaused: nil, PausedBy: nil}
			default:
				continue
			}
			if err := b.Kube.MergePatch(ctx, kube.ScaledObject, ns, it.Metadata.Name,
				map[string]any{"metadata": map[string]any{"annotations": patch}}); err != nil {
				return err
			}
		}
	}
	return nil
}
