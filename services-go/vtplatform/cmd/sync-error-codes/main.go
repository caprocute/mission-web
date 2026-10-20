package main

import (
	"bytes"
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	checkOnly := flag.Bool("check", false, "verify that problem/error-codes.json matches vt-kernel error-codes.json")
	srcFlag := flag.String("src", "../../backend/libs/vt-kernel/src/main/resources/error-codes.json", "path to canonical error-codes.json")
	dstFlag := flag.String("dst", "problem/error-codes.json", "destination path inside services-go/vtplatform")
	flag.Parse()

	srcBytes, err := os.ReadFile(*srcFlag)
	if err != nil {
		fmt.Fprintf(os.Stderr, "sync-error-codes: cannot read source %s: %v\n", *srcFlag, err)
		os.Exit(1)
	}

	if *checkOnly {
		dstBytes, err := os.ReadFile(*dstFlag)
		if err != nil {
			fmt.Fprintf(os.Stderr, "sync-error-codes: cannot read destination %s: %v\n", *dstFlag, err)
			os.Exit(1)
		}
		if !bytes.Equal(srcBytes, dstBytes) {
			fmt.Fprintf(os.Stderr, "sync-error-codes: %s differs from %s\n", *dstFlag, *srcFlag)
			os.Exit(1)
		}
		fmt.Printf("sync-error-codes: %s matches canonical source (%d bytes)\n", *dstFlag, len(srcBytes))
		return
	}

	if err := os.MkdirAll(filepath.Dir(*dstFlag), 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "sync-error-codes: cannot create directory: %v\n", err)
		os.Exit(1)
	}
	if err := os.WriteFile(*dstFlag, srcBytes, 0o644); err != nil {
		fmt.Fprintf(os.Stderr, "sync-error-codes: cannot write %s: %v\n", *dstFlag, err)
		os.Exit(1)
	}
	fmt.Printf("sync-error-codes: synced %d bytes to %s\n", len(srcBytes), *dstFlag)
}
