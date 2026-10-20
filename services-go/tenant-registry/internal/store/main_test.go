package store_test

import (
	"context"
	"fmt"
	"os"
	"testing"
)

var db *pg

func TestMain(m *testing.M) {
	ctx := context.Background()
	var err error
	db, err = startPG(ctx, true)
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được PostgreSQL kiểm thử:", err)
		os.Exit(1)
	}
	code := m.Run()
	db.Terminate(ctx)
	os.Exit(code)
}
