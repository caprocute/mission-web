package server

import (
	"testing"

	"github.com/google/uuid"
)

func TestJobIDPrefix(t *testing.T) {
	id := uuid.New()
	if got := jobID(id); got != "ledger-"+id.String() {
		t.Fatalf("jobID = %s", got)
	}
	for _, s := range []string{jobID(id), id.String()} {
		if got, err := parseJobID(s); err != nil || got != id {
			t.Fatalf("parseJobID(%s) = %v, %v", s, got, err)
		}
	}
	for _, s := range []string{"score-" + id.String(), "ledger-khong-hop-le", ""} {
		if _, err := parseJobID(s); err == nil {
			t.Fatalf("parseJobID(%q) phải lỗi", s)
		}
	}
}
