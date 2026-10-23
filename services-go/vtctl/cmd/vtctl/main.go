// Lệnh vtctl: công cụ vận hành Mission cho người trực (INF-26, TL-04 OPS-14, OPS-24).
package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	"vn.viettel.okrkpi/services-go/vtctl/internal/cli"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	a := &cli.App{Out: os.Stdout, Err: os.Stderr, Interactive: true}
	code := cli.Execute(ctx, a, os.Args[1:])
	stop()
	os.Exit(code)
}
