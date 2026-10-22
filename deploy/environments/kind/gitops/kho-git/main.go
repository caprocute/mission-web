// Máy chủ Git HTTP "smart" tối giản cho kiểm thử GitOps trên kind (INF-04): Argo CD dùng go-git, không đọc được
// giao thức HTTP "dumb", còn ảnh alpine/git không có git-daemon hay git-http-backend. Chỉ phục vụ đọc (upload-pack)
// các kho trần trong thư mục GOC (mặc định /srv), cổng 8080.
package main

import (
	"compress/gzip"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

func main() {
	goc := os.Getenv("GOC")
	if goc == "" {
		goc = "/srv"
	}
	kho := func(p, hauTo string) (string, bool) {
		ten := strings.TrimSuffix(strings.TrimPrefix(p, "/"), hauTo)
		if ten == "" || strings.Contains(ten, "..") {
			return "", false
		}
		d := filepath.Join(goc, ten)
		_, err := os.Stat(filepath.Join(d, "HEAD"))
		return d, err == nil
	}
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/info/refs") && r.URL.Query().Get("service") == "git-upload-pack":
			d, ok := kho(r.URL.Path, "/info/refs")
			if !ok {
				http.NotFound(w, r)
				return
			}
			out, err := exec.Command("git", "upload-pack", "--stateless-rpc", "--advertise-refs", d).Output()
			if err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
			w.Header().Set("Content-Type", "application/x-git-upload-pack-advertisement")
			w.Header().Set("Cache-Control", "no-cache")
			dau := "# service=git-upload-pack\n"
			fmt.Fprintf(w, "%04x%s0000", len(dau)+4, dau)
			w.Write(out)
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/git-upload-pack"):
			d, ok := kho(r.URL.Path, "/git-upload-pack")
			if !ok {
				http.NotFound(w, r)
				return
			}
			var than io.Reader = r.Body
			if r.Header.Get("Content-Encoding") == "gzip" {
				g, err := gzip.NewReader(r.Body)
				if err != nil {
					http.Error(w, err.Error(), 400)
					return
				}
				than = g
			}
			lenh := exec.Command("git", "upload-pack", "--stateless-rpc", d)
			lenh.Stdin = than
			w.Header().Set("Content-Type", "application/x-git-upload-pack-result")
			w.Header().Set("Cache-Control", "no-cache")
			lenh.Stdout = w
			if err := lenh.Run(); err != nil {
				log.Printf("upload-pack %s: %v", d, err)
			}
		default:
			http.Error(w, "chỉ hỗ trợ đọc kho (git-upload-pack)", http.StatusForbidden)
		}
	})
	log.Fatal(http.ListenAndServe(":8080", nil))
}
