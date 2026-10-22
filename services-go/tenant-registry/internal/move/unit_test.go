package move

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

// TestSignV4Vectors: ví dụ chính thức của AWS (S3 API Reference, "Signature Calculations for the Authorization
// Header", ví dụ GET Object và GET Bucket (List Objects)).
func TestSignV4Vectors(t *testing.T) {
	when := time.Date(2013, 5, 24, 0, 0, 0, 0, time.UTC)
	ak, sk := "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
	empty := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

	req, _ := http.NewRequest(http.MethodGet, "https://examplebucket.s3.amazonaws.com/test.txt", nil)
	req.Header.Set("Range", "bytes=0-9")
	SignV4Payload(req, ak, sk, "us-east-1", "s3", when, empty)
	if want := "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"; !strings.HasSuffix(req.Header.Get("Authorization"), "Signature="+want) {
		t.Fatalf("GET Object: %s", req.Header.Get("Authorization"))
	}
	if !strings.Contains(req.Header.Get("Authorization"), "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date") {
		t.Fatalf("SignedHeaders: %s", req.Header.Get("Authorization"))
	}

	req, _ = http.NewRequest(http.MethodGet, "https://examplebucket.s3.amazonaws.com/", nil)
	req.URL.RawQuery = canonicalQuery(url.Values{"max-keys": {"2"}, "prefix": {"J"}})
	SignV4Payload(req, ak, sk, "us-east-1", "s3", when, empty)
	if want := "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"; !strings.HasSuffix(req.Header.Get("Authorization"), "Signature="+want) {
		t.Fatalf("List Objects: %s", req.Header.Get("Authorization"))
	}
}

func TestNamesAndHelpers(t *testing.T) {
	id := uuid.MustParse("0192f1b0-11aa-7b22-9c33-44d55e66f701")
	if Name(id) != "mv_0192f1b011aa7b229c3344d55e66f701" || len(Name(id)) > 63 {
		t.Fatalf("tên: %s", Name(id))
	}
	if awsEscape("t/ab c~") != "t%2Fab%20c~" {
		t.Fatal(awsEscape("t/ab c~"))
	}
	if !sumEqual("123456789012345678901234", "123456789012345678901234") || sumEqual("1", "2") || sumEqual("x", "x") {
		t.Fatal("so tổng băm sai")
	}
	p := &PGCells{}
	if !p.excluded(Table{Schema: "okr", Name: "outbox_event"}) || p.excluded(Table{Schema: "okr", Name: "objective"}) {
		t.Fatal("danh sách loại trừ mặc định sai")
	}
	if objectPath("dossier", "t/x/sha256/ab/c d") != "/dossier/t/x/sha256/ab/c%20d" {
		t.Fatal(objectPath("dossier", "t/x/sha256/ab/c d"))
	}
}
