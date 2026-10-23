package engine

import (
	"errors"
	"testing"
	"time"
)

var t0 = time.Date(2026, 11, 7, 8, 0, 0, 0, time.UTC)

func lv(n int) *int { return &n }

func fire(key, name string, level int) Alert {
	return Alert{Key: key, Name: name, Level: level, Firing: true}
}
func resolve(key string) Alert { return Alert{Key: key} }

// Bảng chuyển mức TL-04: mức là max của các cảnh báo đang cháy, tăng ngay.
func TestBangChuyenMuc(t *testing.T) {
	e := New(DefaultConfig(), 0, t0)
	steps := []struct {
		alerts []Alert
		want   int
	}{
		{[]Alert{fire("a", "MissionCoreSaturated", 1)}, 1},
		{[]Alert{fire("b", "Http5xxHigh", 2)}, 2},
		{[]Alert{fire("c", "PrimaryCPUHigh", 3)}, 3},
		{[]Alert{fire("a", "MissionCoreSaturated", 1)}, 3}, // lặp lại cảnh báo thấp không hạ mức
	}
	for i, s := range steps {
		d := e.Alerts(t0.Add(time.Duration(i)*time.Second), s.alerts, "")
		if d.Level != s.want {
			t.Fatalf("bước %d: mức %d, muốn %d", i, d.Level, s.want)
		}
	}
	st := e.Snapshot(t0)
	if len(st.Firing) != 3 || st.FiringAlerts[0] != "Http5xxHigh" {
		t.Fatalf("trạng thái sai: %+v", st)
	}
}

// Chống dao động: hạ mức chỉ khi điều kiện đã hết liên tục 10 phút; cảnh báo cháy lại thì đếm lại.
func TestChongDaoDong(t *testing.T) {
	e := New(DefaultConfig(), 0, t0)
	e.Alerts(t0, []Alert{fire("c", "PrimaryCPUHigh", 3)}, "")
	d := e.Alerts(t0.Add(time.Minute), []Alert{resolve("c")}, "")
	if d.Level != 3 || d.Changed {
		t.Fatalf("hết cảnh báo không được hạ ngay: %+v", d)
	}
	if d := e.Tick(t0.Add(9 * time.Minute)); d.Level != 3 {
		t.Fatalf("chưa đủ 10 phút đã hạ: %+v", d)
	}
	// Cháy lại ở phút 9 rồi hết ở phút 10: bộ đếm bắt đầu lại.
	e.Alerts(t0.Add(9*time.Minute), []Alert{fire("c", "PrimaryCPUHigh", 3)}, "")
	e.Alerts(t0.Add(10*time.Minute), []Alert{resolve("c")}, "")
	if d := e.Tick(t0.Add(19 * time.Minute)); d.Level != 3 {
		t.Fatalf("bộ đếm chống dao động không đặt lại: %+v", d)
	}
	d = e.Tick(t0.Add(20 * time.Minute))
	if d.Level != 0 || !d.Changed || d.Source != "hold_down" {
		t.Fatalf("đủ 10 phút phải về 0: %+v", d)
	}
	// Dao động nhanh quanh ngưỡng không tạo chuyển mức xuống.
	e.Alerts(t0.Add(21*time.Minute), []Alert{fire("b", "Http5xxHigh", 2)}, "")
	changes := 0
	for i := 0; i < 20; i++ {
		at := t0.Add(22*time.Minute + time.Duration(i)*30*time.Second)
		var d Decision
		if i%2 == 0 {
			d = e.Alerts(at, []Alert{resolve("b")}, "")
		} else {
			d = e.Alerts(at, []Alert{fire("b", "Http5xxHigh", 2)}, "")
		}
		if d.Changed {
			changes++
		}
	}
	if changes != 0 || e.Level() != 2 {
		t.Fatalf("dao động tạo %d lần chuyển mức, mức %d", changes, e.Level())
	}
}

// Hạ nhiều bậc một lần về mức mong muốn, không đi qua từng bậc.
func TestHaVeMucMongMuon(t *testing.T) {
	e := New(DefaultConfig(), 0, t0)
	e.Alerts(t0, []Alert{fire("a", "A", 1), fire("c", "C", 3)}, "")
	e.Alerts(t0.Add(time.Minute), []Alert{resolve("c")}, "")
	if d := e.Tick(t0.Add(11 * time.Minute)); d.Level != 1 || d.Previous != 3 {
		t.Fatalf("muốn 3 → 1: %+v", d)
	}
}

// Ghim tay ưu tiên hơn tự động theo cả hai chiều, có thời hạn; hết hạn thì trở lại tự động.
func TestGhimTay(t *testing.T) {
	e := New(DefaultConfig(), 0, t0)
	e.Alerts(t0, []Alert{fire("b", "Http5xxHigh", 2)}, "")
	d, err := e.SetPin(t0.Add(time.Minute), lv(0), 30*time.Minute, "OPS-14 thử")
	if err != nil || d.Level != 0 || !d.Changed {
		t.Fatalf("ghim 0 phải hạ ngay, bỏ qua chống dao động: %+v %v", d, err)
	}
	if d := e.Alerts(t0.Add(2*time.Minute), []Alert{fire("c", "PrimaryCPUHigh", 3)}, ""); d.Level != 0 {
		t.Fatalf("đang ghim, cảnh báo không được đổi mức: %+v", d)
	}
	st := e.Snapshot(t0.Add(2 * time.Minute))
	if st.Manual == nil || *st.Manual != 0 || st.Pin.Until != t0.Add(31*time.Minute) {
		t.Fatalf("trạng thái ghim sai: %+v", st)
	}
	d = e.Tick(t0.Add(31 * time.Minute))
	if d.Level != 3 || d.Source != "pin_expired" {
		t.Fatalf("hết hạn ghim phải về mức theo cảnh báo (3): %+v", d)
	}

	// Ghim cao hơn rồi bỏ ghim: hạ theo chống dao động.
	e.Alerts(t0.Add(32*time.Minute), []Alert{resolve("b"), resolve("c")}, "")
	e.SetPin(t0.Add(33*time.Minute), lv(3), 0, "")
	d, _ = e.SetPin(t0.Add(34*time.Minute), nil, 0, "bỏ ghim")
	if d.Level != 3 {
		t.Fatalf("bỏ ghim không được tụt mức ngay: %+v", d)
	}
	if d := e.Tick(t0.Add(44 * time.Minute)); d.Level != 0 {
		t.Fatalf("bỏ ghim 10 phút phải về 0: %+v", d)
	}

	if _, err := e.SetPin(t0, lv(4), 0, ""); !errors.Is(err, ErrInvalid) {
		t.Fatal("mức 4 phải bị từ chối")
	}
	if _, err := e.SetPin(t0, lv(1), 25*time.Hour, ""); !errors.Is(err, ErrInvalid) {
		t.Fatal("ghim quá 24 giờ phải bị từ chối")
	}
	d, _ = e.SetPin(t0.Add(50*time.Minute), lv(1), 0, "")
	if st := e.Snapshot(t0.Add(50 * time.Minute)); d.Level != 1 || st.Pin.Until != t0.Add(110*time.Minute) {
		t.Fatalf("ghim không nêu hạn phải lấy mặc định 1 giờ: %+v", st.Pin)
	}
}

// Mức ≥ 2 quá 15 phút thì nâng SEV-2 đúng một lần mỗi đợt; 2 → 3 không đặt lại đồng hồ.
func TestNangSEV2(t *testing.T) {
	e := New(DefaultConfig(), 0, t0)
	e.Alerts(t0, []Alert{fire("b", "B", 2)}, "")
	e.Alerts(t0.Add(5*time.Minute), []Alert{fire("c", "C", 3)}, "")
	if d := e.Tick(t0.Add(14 * time.Minute)); d.Escalate {
		t.Fatal("chưa đủ 15 phút")
	}
	if d := e.Tick(t0.Add(15 * time.Minute)); !d.Escalate {
		t.Fatal("đủ 15 phút ở mức ≥ 2 phải nâng SEV-2")
	}
	if d := e.Tick(t0.Add(16 * time.Minute)); d.Escalate {
		t.Fatal("chỉ nâng một lần mỗi đợt")
	}
	e.SetPin(t0.Add(17*time.Minute), lv(1), 0, "")
	e.SetPin(t0.Add(18*time.Minute), lv(2), 0, "")
	if d := e.Tick(t0.Add(33 * time.Minute)); !d.Escalate {
		t.Fatal("đợt mới sau khi xuống dưới 2 phải nâng lại")
	}
}

// Khởi động lại ở mức đang ghi trên cụm: không tụt ngay; cảnh báo cũ không được gửi lại thì bị bỏ.
func TestKhoiDongLaiVaCanhBaoCu(t *testing.T) {
	e := New(DefaultConfig(), 2, t0)
	if d := e.Tick(t0.Add(time.Minute)); d.Level != 2 {
		t.Fatalf("khởi động lại không được tụt mức ngay: %+v", d)
	}
	if d := e.Tick(t0.Add(11 * time.Minute)); d.Level != 0 {
		t.Fatalf("không có cảnh báo, sau 10 phút phải về 0: %+v", d)
	}
	e.Alerts(t0.Add(12*time.Minute), []Alert{fire("a", "A", 1)}, "")
	e.Tick(t0.Add(73 * time.Minute)) // quá StaleAfter 1 giờ: bỏ cảnh báo, bắt đầu đếm chống dao động
	if d := e.Tick(t0.Add(83 * time.Minute)); d.Level != 0 {
		t.Fatalf("cảnh báo không được gửi lại quá 1 giờ phải coi như hết: %+v", d)
	}
}

func TestMucNen(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Base = 1
	e := New(cfg, 0, t0)
	if d := e.Tick(t0); d.Level != 1 || !d.Changed {
		t.Fatalf("mức nền 1 phải áp ngay: %+v", d)
	}
}
