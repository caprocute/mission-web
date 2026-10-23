package main

import "testing"

func TestParseAlertLevels(t *testing.T) {
	m, err := parseAlertLevels("MissionCoreSaturated=1, Http5xxHigh=2,CNPGPrimaryCPUSaturated=3")
	if err != nil || m["Http5xxHigh"] != 2 || len(m) != 3 {
		t.Fatalf("%v %v", m, err)
	}
	for _, bad := range []string{"A", "A=0", "A=4", "=2", "A=x"} {
		if _, err := parseAlertLevels(bad); err == nil {
			t.Fatalf("%q phải lỗi", bad)
		}
	}
}
