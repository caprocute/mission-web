// Package dnssync ghi bản ghi A điểm vào của cell (cell-<x>.edge.<vùng>) theo cờ TTDL đang hoạt động của cell trong
// SVC-03 (INF-20; TL-04 "GeoDNS và định tuyến tenant tới cell", DR-10, DR-16).
//
// Nguyên tắc an toàn: bản ghi của cell chỉ đổi khi cờ đổi, không bao giờ theo kiểm tra sức khoẻ của cell (chuyển vùng
// cell là quyết định có người duyệt, DR-10). Khi không đọc được SVC-03, khi cell thiếu VIP cho TTDL được chọn, hoặc khi
// dữ liệu không rõ ràng, tác vụ giữ nguyên bản ghi hiện có và ghi cảnh báo. Không bao giờ xoá bản ghi.
package dnssync

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sort"
	"strings"
)

// Cell là phần của RegistryCell (GET /api/v1/registry/cells) mà dns-sync cần.
type Cell struct {
	Code      string `json:"code"`
	PrimaryDc string `json:"primaryDc"`
	// ActiveDc là cờ active_site của TL-04 (RegistryCell.activeDc, cột registry.cell.active_dc). Khi vắng (SVC-03
	// bản cũ trong lúc nâng cấp) thì dùng primaryDc, tức trạng thái bình thường khi chưa chuyển vùng.
	ActiveDc string `json:"activeDc,omitempty"`
}

// Site trả TTDL mà điểm vào của cell phải trỏ tới.
func (c Cell) Site() string {
	if c.ActiveDc != "" {
		return c.ActiveDc
	}
	return c.PrimaryDc
}

// Registry đọc danh sách cell từ SVC-03.
type Registry interface {
	Cells(ctx context.Context) ([]Cell, error)
}

// DNS đọc và ghi bản ghi A của vùng do nền tảng quản lý.
type DNS interface {
	// A trả các địa chỉ hiện có của tên (rỗng khi chưa có bản ghi).
	A(ctx context.Context, name string) ([]string, error)
	ReplaceA(ctx context.Context, name string, ttl int, ips []string) error
}

// CellConfig khai VIP của cell ở từng TTDL (HOALAC, PHIANAM, TAICHO) và tên điểm vào.
type CellConfig struct {
	// Entry là nhãn đầu của tên điểm vào, mặc định cell-<mã cell viết thường bỏ tiền tố CELL->.
	Entry string            `json:"entry,omitempty"`
	VIP   map[string]string `json:"vip"`
}

// Config là cấu hình của tác vụ, nạp từ ConfigMap (JSON).
type Config struct {
	Zone  string                `json:"zone"`
	TTL   int                   `json:"ttl"`
	Cells map[string]CellConfig `json:"cells"`
}

// Validate kiểm cấu hình trước khi chạy để lỗi gõ VIP không thành bản ghi sai.
func (c *Config) Validate() error {
	if c.Zone == "" {
		return fmt.Errorf("dns-sync: thiếu zone")
	}
	if c.TTL == 0 {
		c.TTL = 60
	}
	if c.TTL < 30 || c.TTL > 300 {
		return fmt.Errorf("dns-sync: ttl %d ngoài khoảng 30–300", c.TTL)
	}
	if len(c.Cells) == 0 {
		return fmt.Errorf("dns-sync: chưa khai cell nào")
	}
	for code, cc := range c.Cells {
		if len(cc.VIP) == 0 {
			return fmt.Errorf("dns-sync: cell %s chưa có VIP", code)
		}
		for site, ip := range cc.VIP {
			if p := net.ParseIP(ip); p == nil || p.To4() == nil {
				return fmt.Errorf("dns-sync: VIP %q của %s/%s không phải IPv4", ip, code, site)
			}
		}
	}
	return nil
}

// EntryName trả tên đầy đủ của điểm vào cell, ví dụ cell-sh.edge.danhgia.vn.
func (c *Config) EntryName(code string) string {
	e := c.Cells[code].Entry
	if e == "" {
		e = "cell-" + strings.TrimPrefix(strings.ToLower(code), "cell-")
	}
	return e + ".edge." + strings.TrimSuffix(c.Zone, ".") + "."
}

// Result tóm tắt một lượt đồng bộ.
type Result struct {
	Changed, Unchanged, Skipped int
}

// Syncer chạy một lượt đồng bộ.
type Syncer struct {
	Cfg      *Config
	Registry Registry
	DNS      DNS
	Log      *slog.Logger
}

// Run đọc cờ của mọi cell rồi đặt bản ghi A khi khác; lỗi đọc SVC-03 làm cả lượt dừng mà không đổi gì.
func (s *Syncer) Run(ctx context.Context) (Result, error) {
	var r Result
	cells, err := s.Registry.Cells(ctx)
	if err != nil {
		return r, fmt.Errorf("dns-sync: đọc SVC-03: %w", err)
	}
	theoMa := map[string]Cell{}
	for _, c := range cells {
		if _, trung := theoMa[c.Code]; trung {
			return r, fmt.Errorf("dns-sync: SVC-03 trả trùng mã cell %s", c.Code)
		}
		theoMa[c.Code] = c
	}
	ma := make([]string, 0, len(s.Cfg.Cells))
	for code := range s.Cfg.Cells {
		ma = append(ma, code)
	}
	sort.Strings(ma)
	for _, code := range ma {
		c, ok := theoMa[code]
		if !ok {
			s.Log.Warn("dns_sync_cell_missing", "cell", code, "reason", "SVC-03 không có cell này, giữ nguyên bản ghi")
			r.Skipped++
			continue
		}
		site := c.Site()
		ip, ok := s.Cfg.Cells[code].VIP[site]
		if !ok {
			s.Log.Warn("dns_sync_no_vip", "cell", code, "site", site, "reason", "chưa khai VIP cho TTDL, giữ nguyên bản ghi")
			r.Skipped++
			continue
		}
		name := s.Cfg.EntryName(code)
		cur, err := s.DNS.A(ctx, name)
		if err != nil {
			s.Log.Error("dns_sync_read_failed", "cell", code, "name", name, "error", err.Error())
			r.Skipped++
			continue
		}
		if len(cur) == 1 && cur[0] == ip {
			r.Unchanged++
			continue
		}
		if err := s.DNS.ReplaceA(ctx, name, s.Cfg.TTL, []string{ip}); err != nil {
			s.Log.Error("dns_sync_write_failed", "cell", code, "name", name, "error", err.Error())
			r.Skipped++
			continue
		}
		s.Log.Info("dns_sync_changed", "cell", code, "name", name, "site", site, "from", strings.Join(cur, ","), "to", ip, "ttl", s.Cfg.TTL)
		r.Changed++
	}
	for code := range theoMa {
		if _, ok := s.Cfg.Cells[code]; !ok {
			s.Log.Warn("dns_sync_cell_unconfigured", "cell", code, "reason", "cell có trong SVC-03 nhưng chưa khai VIP")
		}
	}
	return r, nil
}
