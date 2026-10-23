// Command outbox-replay is the platform CLI tool for inspecting and replaying events
// from `<schema>.outbox_event` to Kafka (NEN-022, ADR-03, TL-03, TL-04 DR-10 & OPS-24).
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/connect"
	"vn.viettel.okrkpi/services-go/outbox-replay/internal/replay"
	"vn.viettel.okrkpi/services-go/outbox-replay/internal/sources"
)

// getenv is replaced in tests.
var getenv = os.Getenv

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	code := runCLI(ctx, os.Args[1:], os.Stdout, os.Stderr, nil, nil)
	os.Exit(code)
}

func runCLI(
	ctx context.Context,
	args []string,
	stdout io.Writer,
	stderr io.Writer,
	store replay.OutboxStore,
	producer replay.Producer,
) int {
	// Support optional subcommand: "validate", "inspect", "replay"
	var subcmd string
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		switch args[0] {
		case "validate", "inspect", "replay":
			subcmd = args[0]
			args = args[1:]
		}
	}

	fs := flag.NewFlagSet("outbox-replay", flag.ContinueOnError)
	fs.SetOutput(stderr)

	var (
		schemaFlag         = fs.String("schema", "", "PostgreSQL schema containing <schema>.outbox_event (required)")
		tenantFlag         = fs.String("tenant", "", "Tenant UUID to filter outbox_event (required)")
		fromFlag           = fs.String("from", "", "Inclusive start timestamp in RFC3339 format (created_at >= from)")
		toFlag             = fs.String("to", "", "Inclusive end timestamp in RFC3339 format (created_at <= to)")
		safePointFlag      = fs.Bool("from-safe-point", false, "Failover (DR-10 step 8): start from last replayed WAL commit (pg_last_xact_replay_timestamp) minus --safe-point-margin")
		safeMarginFlag     = fs.Duration("safe-point-margin", 15*time.Minute, "Margin subtracted from the last replayed WAL commit for --from-safe-point")
		eventIDFlag        = fs.String("event-id", "", "Optional specific event UUID (id)")
		aggregateIDFlag    = fs.String("aggregate-id", "", "Optional specific aggregate_id filter")
		eventTypeFlag      = fs.String("event-type", "", "Optional specific event_type filter")
		topicFlag          = fs.String("topic", "", "Optional specific topic filter (validated against outbox-sources.yaml)")
		consumerFlag       = fs.String("consumer", "", "Optional consumer name; routes to <topic>.replay.<consumer>")
		dryRunFlag         = fs.Bool("dry-run", true, "Dry-run mode: count matching rows by topic and tenant without publishing (default true)")
		confirmFlag        = fs.Bool("confirm", false, "Confirm real replay and emit audit event to sys.audit.v1")
		rateLimitFlag      = fs.Int("rate-limit", 500, "Max messages per second during replay (0 = unlimited)")
		batchSizeFlag      = fs.Int("batch-size", replay.DefaultBatchSize, "Batch size for keyset pagination")
		afterCreatedAtFlag = fs.String("after-created-at", "", "Resume checkpoint: created_at of last published row (RFC3339Nano)")
		afterIDFlag        = fs.String("after-id", "", "Resume checkpoint: id of last published row")
		actorFlag          = fs.String("actor", os.Getenv("OUTBOX_REPLAY_ACTOR"), "Operator or job identity recorded in sys.audit.v1")
		reasonFlag         = fs.String("reason", "", "Operational ticket/reason recorded in sys.audit.v1")
		sourcesFlag        = fs.String("sources", defaultFilePath("/etc/mission/events/outbox-sources.yaml", "contracts/events/outbox-sources.yaml"), "Path to outbox-sources.yaml")
		catalogFlag        = fs.String("catalog", defaultFilePath("/etc/mission/events/catalog.yaml", "contracts/events/catalog.yaml"), "Path to catalog.yaml")
		validateOnlyFlag   = fs.Bool("validate-catalog", false, "Validate outbox-sources.yaml against catalog.yaml and exit")
	)

	if err := fs.Parse(args); err != nil {
		return 2
	}

	explicitDryRun := false
	for _, a := range args {
		if a == "--dry-run" || strings.HasPrefix(a, "--dry-run=") || a == "-dry-run" || strings.HasPrefix(a, "-dry-run=") {
			explicitDryRun = true
		}
	}

	reg, cat, err := sources.LoadAndValidate(*sourcesFlag, *catalogFlag)
	if err != nil {
		_, _ = fmt.Fprintf(stderr, "outbox-replay: catalog validation error: %v\n", err)
		return 1
	}

	if subcmd == "validate" || *validateOnlyFlag {
		out := map[string]any{
			"status":          "VALID",
			"schemas":         len(reg.Sources),
			"businessEvents":  len(cat.Events),
			"technicalTopics": len(cat.TechnicalTopics),
			"wildcardTopics":  reg.WildcardTopics,
		}
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(out)
		return 0
	}

	opts := replay.NewDefaultOptions()
	opts.Consumer = strings.TrimSpace(*consumerFlag)
	opts.RateLimitPerSec = *rateLimitFlag
	opts.BatchSize = *batchSizeFlag
	opts.ActorID = strings.TrimSpace(*actorFlag)
	opts.Reason = strings.TrimSpace(*reasonFlag)
	opts.Confirm = *confirmFlag

	// Default mode is dry-run unless --confirm is provided (and --dry-run=true was not explicitly forced)
	if subcmd == "inspect" {
		opts.DryRun = true
	} else if *confirmFlag && !explicitDryRun {
		opts.DryRun = false
	} else {
		opts.DryRun = *dryRunFlag
	}

	opts.Filter = replay.Filter{
		Schema:      strings.TrimSpace(*schemaFlag),
		TenantID:    strings.TrimSpace(*tenantFlag),
		EventID:     strings.TrimSpace(*eventIDFlag),
		AggregateID: strings.TrimSpace(*aggregateIDFlag),
		EventType:   strings.TrimSpace(*eventTypeFlag),
		Topic:       strings.TrimSpace(*topicFlag),
	}

	if strings.TrimSpace(*fromFlag) != "" {
		t, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(*fromFlag))
		if err != nil {
			_, _ = fmt.Fprintf(stderr, "outbox-replay: invalid --from timestamp %q: %v\n", *fromFlag, err)
			return 2
		}
		opts.Filter.From = t.UTC()
	}
	if strings.TrimSpace(*toFlag) != "" {
		t, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(*toFlag))
		if err != nil {
			_, _ = fmt.Fprintf(stderr, "outbox-replay: invalid --to timestamp %q: %v\n", *toFlag, err)
			return 2
		}
		opts.Filter.To = t.UTC()
	}
	if strings.TrimSpace(*afterCreatedAtFlag) != "" {
		t, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(*afterCreatedAtFlag))
		if err != nil {
			_, _ = fmt.Fprintf(stderr, "outbox-replay: invalid --after-created-at timestamp %q: %v\n", *afterCreatedAtFlag, err)
			return 2
		}
		utc := t.UTC()
		opts.Filter.AfterCreatedAt = &utc
		opts.Filter.AfterID = strings.TrimSpace(*afterIDFlag)
	}

	if *safePointFlag && strings.TrimSpace(*fromFlag) != "" {
		_, _ = fmt.Fprintln(stderr, "outbox-replay: --from-safe-point và --from không dùng cùng nhau")
		return 2
	}

	// Without injected test doubles, read and publish against the cell's real PostgreSQL and Kafka
	// configured through environment variables (internal/connect, INF-26 Job envFrom).
	conn := connect.FromEnv(getenv)
	if store == nil {
		pg, closeStore, err := connect.OpenStore(ctx, conn, opts.Filter.TenantID)
		if err != nil {
			_, _ = fmt.Fprintf(stderr, "outbox-replay: %v\n", err)
			return 1
		}
		defer closeStore()
		store = pg
	}
	if *safePointFlag {
		src, ok := store.(replay.SafePointSource)
		if !ok {
			_, _ = fmt.Fprintln(stderr, "outbox-replay: kho outbox không hỗ trợ --from-safe-point")
			return 1
		}
		from, err := replay.SafePoint(ctx, src, *safeMarginFlag)
		if err != nil {
			_, _ = fmt.Fprintf(stderr, "outbox-replay: %v\n", err)
			return 1
		}
		opts.Filter.From = from
		_, _ = fmt.Fprintf(stderr, "ĐIỂM AN TOÀN: --from=%s (WAL cuối trừ %s)\n", from.Format(time.RFC3339Nano), *safeMarginFlag)
	}
	if producer == nil && !opts.DryRun {
		kp, err := connect.NewKafkaProducer(conn)
		if err != nil {
			_, _ = fmt.Fprintf(stderr, "outbox-replay: %v\n", err)
			return 1
		}
		defer kp.Close()
		producer = kp
	}

	replayer, err := replay.NewReplayer(reg, store, producer)
	if err != nil {
		_, _ = fmt.Fprintf(stderr, "outbox-replay: init error: %v\n", err)
		return 1
	}

	res, err := replayer.Run(ctx, opts)
	if err != nil {
		_, _ = fmt.Fprintf(stderr, "outbox-replay: execution error: %v\n", err)
		if res != nil && res.LastCreatedAt != nil {
			_, _ = fmt.Fprintf(stderr, "CHECKPOINT: --after-created-at=%s --after-id=%s\n",
				res.LastCreatedAt.UTC().Format(time.RFC3339Nano), res.LastEventID)
		}
		return 1
	}

	for _, w := range res.Warnings {
		_, _ = fmt.Fprintln(stderr, w)
	}
	if res.Interrupted && res.LastCreatedAt != nil {
		_, _ = fmt.Fprintf(stderr, "INTERRUPTED CHECKPOINT: --after-created-at=%s --after-id=%s\n",
			res.LastCreatedAt.UTC().Format(time.RFC3339Nano), res.LastEventID)
	}

	enc := json.NewEncoder(stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(res)

	if res.Interrupted {
		return 130
	}
	return 0
}

func defaultFilePath(containerPath, repoRelativePath string) string {
	if _, err := os.Stat(containerPath); err == nil {
		return containerPath
	}
	candidates := []string{
		repoRelativePath,
		filepath.Join("..", "..", repoRelativePath),
		filepath.Join("..", "..", "..", "..", repoRelativePath),
	}
	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			return c
		}
	}
	return repoRelativePath
}
