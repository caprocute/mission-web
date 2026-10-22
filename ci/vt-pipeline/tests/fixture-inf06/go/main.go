// Dịch vụ Go mẫu tối thiểu để kiểm thử vtGoPipeline (INF-06).
package main

import (
	"fmt"
	"log"
	"net/http"
)

func xinChao(ten string) string {
	if ten == "" {
		ten = "Mission"
	}
	return fmt.Sprintf("Xin chào %s", ten)
}

func xuLy(w http.ResponseWriter, r *http.Request) {
	fmt.Fprint(w, xinChao(r.URL.Query().Get("ten")))
}

func mayChu() *http.ServeMux {
	m := http.NewServeMux()
	m.HandleFunc("/", xuLy)
	return m
}

func main() { log.Fatal(http.ListenAndServe(":8080", mayChu())) }
