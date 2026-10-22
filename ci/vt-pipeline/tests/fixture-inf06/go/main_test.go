package main

import (
	"net/http/httptest"
	"testing"
)

func TestXinChao(t *testing.T) {
	if got := xinChao(""); got != "Xin chào Mission" {
		t.Fatalf("được %q", got)
	}
	rec := httptest.NewRecorder()
	mayChu().ServeHTTP(rec, httptest.NewRequest("GET", "/?ten=OKR", nil))
	if rec.Body.String() != "Xin chào OKR" {
		t.Fatalf("được %q", rec.Body.String())
	}
}
