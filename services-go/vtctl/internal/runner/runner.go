// Package runner chạy công cụ chuyên dụng đã có (kubectl, helm) thay vì truy cập thẳng hạ tầng; kiểm thử thay
// bằng bản giả.
package runner

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os/exec"
	"strings"
)

// Runner chạy một lệnh, stdin tuỳ chọn, trả stdout.
type Runner interface {
	Run(ctx context.Context, stdin []byte, name string, args ...string) ([]byte, error)
}

// Exec chạy tiến trình thật.
type Exec struct {
	Stderr io.Writer
}

func (e Exec) Run(ctx context.Context, stdin []byte, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	err := cmd.Run()
	if e.Stderr != nil && errb.Len() > 0 {
		_, _ = e.Stderr.Write(errb.Bytes())
	}
	if err != nil {
		msg := strings.TrimSpace(errb.String())
		if len(msg) > 500 {
			msg = msg[:500] + "…"
		}
		return out.Bytes(), fmt.Errorf("%s %s: %w %s", name, firstArgs(args), err, msg)
	}
	return out.Bytes(), nil
}

func firstArgs(a []string) string {
	if len(a) > 3 {
		return strings.Join(a[:3], " ") + " …"
	}
	return strings.Join(a, " ")
}

// Kubectl dựng tham số kubectl theo context của cell.
type Kubectl struct {
	R       Runner
	Context string
}

func (k Kubectl) args(ns string, a ...string) []string {
	out := []string{}
	if k.Context != "" {
		out = append(out, "--context", k.Context)
	}
	if ns != "" {
		out = append(out, "-n", ns)
	}
	return append(out, a...)
}

// Run chạy kubectl trong namespace.
func (k Kubectl) Run(ctx context.Context, stdin []byte, ns string, a ...string) ([]byte, error) {
	return k.R.Run(ctx, stdin, "kubectl", k.args(ns, a...)...)
}

// CanI kiểm quyền RBAC hiện có của người chạy (`kubectl auth can-i`). Quyền cao (debug-app, operate-data) chỉ có
// khi PAM đã cấp ClusterRoleBinding có thời hạn (TL-04 SEC-29).
func (k Kubectl) CanI(ctx context.Context, ns, verb, resource string) (bool, error) {
	out, err := k.R.Run(ctx, nil, "kubectl", k.args(ns, "auth", "can-i", verb, resource)...)
	ans := strings.TrimSpace(string(out))
	if ans == "yes" {
		return true, nil
	}
	if ans == "no" {
		return false, nil
	}
	return false, err
}
