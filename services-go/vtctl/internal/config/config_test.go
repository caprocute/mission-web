package config_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"vn.viettel.okrkpi/services-go/vtctl/internal/config"
)

func cellsDir(t *testing.T) string {
	d, _ := os.Getwd()
	return filepath.Join(d, "..", "..", "..", "..", "deploy", "cells")
}

func TestDanhMucCellKhopDeployCells(t *testing.T) {
	cat, err := config.CellCatalog(cellsDir(t))
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []string{"hn", "nb", "hcm", "g1", "dtw"} {
		if cat[c].Name != c || !strings.HasPrefix(cat[c].DisplayName, "CELL-") {
			t.Fatalf("cell %s: %+v", c, cat[c])
		}
	}
}

func TestMauCauHinhHopLe(t *testing.T) {
	c, err := config.Load(filepath.Join("..", "..", "vtctl.example.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Resolve("staging", "hn"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Resolve("staging", "khong-co"); err == nil {
		t.Fatal("cell lạ phải lỗi")
	}
	if _, err := c.Resolve("khong-co", ""); err == nil {
		t.Fatal("môi trường lạ phải lỗi")
	}
	if !strings.HasSuffix(c.ChartsDir, filepath.Join("deploy", "charts")) {
		t.Fatalf("chartsDir mặc định: %s", c.ChartsDir)
	}
}

func TestTruongLaBiTuChoi(t *testing.T) {
	p := filepath.Join(t.TempDir(), "c.yaml")
	_ = os.WriteFile(p, []byte("cellsDir: x\npassword: abc\n"), 0o600)
	if _, err := config.Load(p); err == nil {
		t.Fatal("trường không biết phải bị từ chối")
	}
}
