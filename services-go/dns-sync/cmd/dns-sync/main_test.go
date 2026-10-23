package main

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestBuild(t *testing.T) {
	d := t.TempDir()
	cfg := filepath.Join(d, "cfg.json")
	key := filepath.Join(d, "key")
	_ = os.WriteFile(cfg, []byte(`{"zone":"danhgia.vn","cells":{"CELL-SH":{"vip":{"HOALAC":"203.0.113.21"}}}}`), 0o600)
	_ = os.WriteFile(key, []byte("khoa\n"), 0o600)
	env := map[string]string{"CONFIG_FILE": cfg, "REGISTRY_URL": "http://r", "PDNS_URL": "http://p", "PDNS_API_KEY_FILE": key, "INTERVAL": "0"}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	s, iv, err := build(func(k string) string { return env[k] }, log)
	if err != nil || iv != 0 || s.Cfg.TTL != 60 {
		t.Fatalf("build: %v %v", err, iv)
	}
	delete(env, "INTERVAL")
	if _, iv, _ := build(func(k string) string { return env[k] }, log); iv != 15*time.Second {
		t.Fatalf("INTERVAL mặc định: %v", iv)
	}
	delete(env, "PDNS_URL")
	if _, _, err := build(func(k string) string { return env[k] }, log); err == nil {
		t.Fatal("thiếu PDNS_URL phải lỗi")
	}
}
