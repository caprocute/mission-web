package main

import (
	"context"
	"encoding/hex"
	"flag"
	"fmt"
	"log/slog"
	"os"

	"github.com/google/uuid"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/ingest"
)

// runAdmin chạy lệnh quản trị ghi bản ghi đặc biệt (TL-02b QT-13, di trú tenant giữa các cell):
//
//	ledger-service admin tenant-moved-in --tenant --chain --origin-cell --last-seal --origin-hash --origin-seq --actor --reason
//	ledger-service admin incident-recovery --tenant --chain --last-valid-seal --incident --actor --reason
//	ledger-service admin seal [--window-end RFC3339] [--kind DOT_XUAT]   (bước 6 saga chốt kỳ, DV-09-03)
//	ledger-service admin anchor --day YYYY-MM-DD
//
// Người thực hiện và lý do được ghi vào chính bản ghi sổ cái và nhật ký vận hành.
func runAdmin(ctx context.Context, ing *ingest.Ingester, sealers sealerFactory, args []string, log *slog.Logger) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "cần lệnh con: tenant-moved-in, incident-recovery, seal hoặc anchor")
		return 2
	}
	if args[0] == "seal" || args[0] == "anchor" {
		return runSealAdmin(ctx, sealers, args, log)
	}
	fs := flag.NewFlagSet(args[0], flag.ContinueOnError)
	tenant := fs.String("tenant", "", "mã tenant")
	chain := fs.Int("chain", -1, "phân vùng chuỗi 0..63")
	actor := fs.String("actor", "", "mã người thực hiện")
	reason := fs.String("reason", "", "lý do, mã biên bản")
	originCell := fs.String("origin-cell", "", "cell nguồn")
	lastSeal := fs.String("last-seal", "", "phong bì niêm phong cuối ở cell nguồn")
	originHash := fs.String("origin-hash", "", "entry_hash cuối của chuỗi ở cell nguồn (hex)")
	originSeq := fs.Int64("origin-seq", 0, "seq_no cuối của chuỗi ở cell nguồn")
	lastValidSeal := fs.String("last-valid-seal", "", "phong bì niêm phong hợp lệ cuối")
	incident := fs.String("incident", "", "mã sự cố")
	if err := fs.Parse(args[1:]); err != nil {
		return 2
	}
	tid, err1 := uuid.Parse(*tenant)
	aid, err2 := uuid.Parse(*actor)
	if err1 != nil || err2 != nil || *chain < 0 || *chain >= canon.ChainPartitionCount {
		fmt.Fprintln(os.Stderr, "--tenant, --actor phải là UUID và --chain trong 0..63")
		return 2
	}
	var entry *ingest.AppendedEntry
	var err error
	switch args[0] {
	case "tenant-moved-in":
		seal, e := uuid.Parse(*lastSeal)
		h, e2 := hex.DecodeString(*originHash)
		if e != nil || e2 != nil {
			fmt.Fprintln(os.Stderr, "--last-seal phải là UUID và --origin-hash là hex")
			return 2
		}
		entry, err = ing.AppendTenantMovedIn(ctx, ingest.TenantMovedInCmd{TenantID: tid, ChainPartition: int16(*chain),
			OriginCell: *originCell, LastSealID: seal, OriginLastEntryHash: h, OriginLastSeqNo: *originSeq, AdminActorID: aid, AuditReason: *reason})
	case "incident-recovery":
		seal, e := uuid.Parse(*lastValidSeal)
		if e != nil {
			fmt.Fprintln(os.Stderr, "--last-valid-seal phải là UUID")
			return 2
		}
		entry, err = ing.AppendIncidentRecoveryMarker(ctx, ingest.IncidentRecoveryCmd{TenantID: tid, ChainPartition: int16(*chain),
			LastValidSealID: seal, IncidentRef: *incident, AdminActorID: aid, AuditReason: *reason})
	default:
		fmt.Fprintln(os.Stderr, "lệnh con không hỗ trợ:", args[0])
		return 2
	}
	if err != nil {
		log.Error("lệnh quản trị sổ cái thất bại", "lenh", args[0], "err", err.Error())
		return 1
	}
	fmt.Printf("seq_no=%d entry_hash=%s\n", entry.SeqNo, hex.EncodeToString(entry.EntryHash))
	return 0
}
