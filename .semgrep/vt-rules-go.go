package mau

import (
	"crypto/md5"
	"crypto/tls"
	"fmt"
	"log"
	"math/rand"
)

const cotBang = "id, ten"

func sqlNoi(db DB, ctx Ctx, id string) {
	// ruleid: vt-go-sql-string-concat
	db.QueryContext(ctx, fmt.Sprintf("SELECT * FROM t WHERE id = '%s'", id))
	// ruleid: vt-go-sql-string-concat
	db.Exec("DELETE FROM t WHERE id = " + id)
	// ok: vt-go-sql-string-concat
	db.QueryContext(ctx, "SELECT * FROM t WHERE id = $1", id)
	// ok: vt-go-sql-string-concat
	db.QueryRow(ctx, `SELECT `+cotBang+` FROM t WHERE id = $1`, id)
}

func tlsCfg() {
	// ruleid: vt-go-tls-insecure
	_ = &tls.Config{MinVersion: tls.VersionTLS12, InsecureSkipVerify: true}
	// ok: vt-go-tls-insecure
	_ = &tls.Config{MinVersion: tls.VersionTLS13}
}

func bam(b []byte) {
	// ruleid: vt-go-weak-crypto
	_ = md5.Sum(b)
}

func sinh() {
	// ruleid: vt-go-math-rand-for-secret
	token := rand.Int63()
	// ok: vt-go-math-rand-for-secret
	soNgauNhien := rand.Intn(10)
	_, _ = token, soNgauNhien
}

func ghiLog(password, ten string) {
	// ruleid: vt-go-log-secret
	log.Printf("đăng nhập %s", password)
	// ok: vt-go-log-secret
	log.Printf("đăng nhập %s", ten)
}
