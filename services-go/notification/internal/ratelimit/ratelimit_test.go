package ratelimit

import (
	"context"
	"testing"
	"time"
)

func TestMemorySlidingWindow(t *testing.T) {
	now := time.Unix(1000, 0)
	m := &Memory{Limits: Limits{Window: time.Minute, Per: map[string]int{"SMS": 10}}, Now: func() time.Time { return now }}
	ctx := context.Background()
	k, release, _ := m.Reserve(ctx, "SMS", 8)
	if k != 8 {
		t.Fatal(k)
	}
	release(3)
	m.Note(ctx, "SMS")
	if k, _, _ = m.Reserve(ctx, "SMS", 10); k != 4 {
		t.Fatalf("còn 4 lượt, được %d", k)
	}
	now = now.Add(59 * time.Second)
	if k, _, _ = m.Reserve(ctx, "SMS", 1); k != 0 {
		t.Fatal("chưa hết cửa sổ 60 giây")
	}
	now = now.Add(time.Second)
	if k, _, _ = m.Reserve(ctx, "SMS", 20); k != 10 {
		t.Fatalf("hết cửa sổ thì đủ 10 lượt, được %d", k)
	}
	if k, _, _ = m.Reserve(ctx, "EMAIL", 7); k != 7 {
		t.Fatal("kênh không giới hạn")
	}
	if r := (Limits{Window: time.Minute, Per: map[string]int{"SMS": 6000}}).Rate("SMS"); r != 100 {
		t.Fatal(r)
	}
}
