// Package site đọc vai trò trung tâm dữ liệu (TTDL) của pod (INF-28, QA-15).
//
// ApplicationSet suy vai trò từ cells/<cell>/cell.yaml (primarySite) và truyền qua global.siteRole thành biến
// VT_SITE_ROLE. Ở TTDL chờ, CSDL là bản sao chỉ đọc và Kafka là đích của MirrorMaker 2, nên service không được
// chạy consumer Kafka, job định kỳ hay migration; API đọc và health vẫn chạy.
package site

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
)

// Role là vai trò TTDL.
type Role string

const (
	Primary Role = "primary"
	Standby Role = "standby"
)

// Paused liệt kê phần bị tạm dừng ở TTDL chờ (khớp chi tiết health của vt-platform-starter).
var Paused = []string{"kafka-consumer", "scheduler", "flyway"}

// Parse đọc "primary"/"standby"; rỗng là primary, giá trị lạ là lỗi để service dừng khởi động.
func Parse(s string) (Role, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "", "primary":
		return Primary, nil
	case "standby":
		return Standby, nil
	}
	return "", fmt.Errorf("VT_SITE_ROLE phải là primary hoặc standby, nhận %q", s)
}

// Info là vai trò và mã TTDL của tiến trình.
type Info struct {
	Role Role   `json:"role"`
	Site string `json:"site"`
}

// FromEnv đọc VT_SITE_ROLE và VT_SITE.
func FromEnv() (Info, error) {
	r, err := Parse(os.Getenv("VT_SITE_ROLE"))
	return Info{Role: r, Site: os.Getenv("VT_SITE")}, err
}

// Standby cho biết tiến trình đang ở TTDL chờ.
func (i Info) Standby() bool { return i.Role == Standby }

// Active trả true khi được chạy phần chỉ dành cho TTDL primary; ở TTDL chờ thì ghi nhật ký phần bị dừng.
func (i Info) Active(log *slog.Logger, part string) bool {
	if !i.Standby() {
		return true
	}
	if log != nil {
		log.Info("TTDL chờ: tạm dừng", "phan", part, "site", i.Site)
	}
	return false
}

// Handler trả health vai trò TTDL (luôn 200: tạm dừng ở TTDL chờ là chủ ý, không phải sự cố).
func (i Info) Handler() http.Handler {
	paused := []string{}
	if i.Standby() {
		paused = Paused
	}
	body, _ := json.Marshal(struct {
		Status string `json:"status"`
		Info
		Paused []string `json:"paused"`
	}{"UP", i, paused})
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	})
}

// Path là đường dẫn health vai trò TTDL, đặt cạnh /healthz của service.
const Path = "/healthz/site"

// Wrap gắn Handler vào Path, các đường dẫn khác chuyển cho next.
func (i Info) Wrap(next http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.Handle(Path, i.Handler())
	mux.Handle("/", next)
	return mux
}
