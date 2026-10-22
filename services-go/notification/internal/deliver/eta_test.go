package deliver

import "testing"

func TestETA(t *testing.T) {
	// 1.000.000 tin SMS ở giới hạn 6.000 tin mỗi phút (100 tin mỗi giây) mất 10.000 giây, khoảng 2 giờ 47 phút.
	if got := ETA(1_000_000, 100); got != 10_000 {
		t.Fatal(got)
	}
	if ETA(5, 0) != 0 || ETA(0, 100) != 0 || ETA(1, 3) != 1 {
		t.Fatal("ETA biên")
	}
}
