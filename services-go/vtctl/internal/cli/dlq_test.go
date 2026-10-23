package cli_test

import (
	"bytes"
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kfake"
	"github.com/twmb/franz-go/pkg/kgo"

	"vn.viettel.okrkpi/services-go/vtctl/internal/cli"
	"vn.viettel.okrkpi/services-go/vtctl/internal/config"
)

func TestDlqReplayQuaCliMotBanGhiKiemToan(t *testing.T) {
	e := newEnv(t, nil, nil, nil)
	kc, err := kfake.NewCluster(kfake.NumBrokers(1), kfake.SeedTopics(-1))
	if err != nil {
		t.Fatal(err)
	}
	defer kc.Close()
	seeds := kc.ListenAddrs()
	cl, err := kgo.NewClient(kgo.SeedBrokers(seeds...), kgo.RecordPartitioner(kgo.ManualPartitioner()))
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()
	ctx := context.Background()
	adm := kadm.NewClient(cl)
	for _, tp := range []string{"org.unit.changed.v1.dlq.task-service", "org.unit.changed.v1.replay.task-service"} {
		if _, err := adm.CreateTopic(ctx, 1, 1, nil, tp); err != nil {
			t.Fatal(err)
		}
	}
	var recs []*kgo.Record
	for i := 0; i < 5; i++ {
		recs = append(recs, &kgo.Record{Topic: "org.unit.changed.v1.dlq.task-service", Value: []byte(fmt.Sprint(i)),
			Headers: []kgo.RecordHeader{{Key: "x-dlq-error-code", Value: []byte("E")}}})
	}
	if err := cl.ProduceSync(ctx, recs...).FirstErr(); err != nil {
		t.Fatal(err)
	}
	run := func(args ...string) (int, string, string) {
		var out, errb bytes.Buffer
		a := &cli.App{Out: &out, Err: &errb, Runner: e.run, Interactive: true,
			KafkaOpts: func(config.Kafka) ([]kgo.Opt, error) { return []kgo.Opt{kgo.SeedBrokers(seeds...)}, nil }}
		code := cli.Execute(ctx, a, append([]string{"--config", e.cfg, "--env", "staging", "--cell", "hn"}, args...))
		return code, out.String(), errb.String()
	}
	base := []string{"dlq", "replay", "--topic", "org.unit.changed.v1", "--consumer", "task-service", "--partition", "0", "--from", "1", "--to", "3",
		"--reason", "INC-2026-1023 sửa dữ liệu nguồn"}
	code, out, errs := run(base...)
	if code != 0 || !strings.Contains(out, "soBanGhi: 3") || !strings.Contains(out, "Chạy thử") || len(e.audits()) != 0 {
		t.Fatalf("chạy thử: %d %s %s", code, out, errs)
	}
	code, out, errs = run(append(base, "--confirm")...)
	if code != 0 || !strings.Contains(out, "soBanGhi: 3") || !strings.Contains(out, "Đã thực hiện") {
		t.Fatalf("chạy thật: %d %s %s", code, out, errs)
	}
	rs := e.audits()
	if len(rs) != 1 || rs[0].Command != "dlq replay" || rs[0].Args["from"] != "1" || rs[0].Args["to"] != "3" || rs[0].Result["soBanGhi"] != float64(3) {
		t.Fatalf("kiểm toán: %+v", rs)
	}
	code, out, _ = run("dlq", "list")
	if code != 0 || !strings.Contains(out, "org.unit.changed.v1.dlq.task-service\tchưa xử lý 1") {
		t.Fatalf("list: %s", out)
	}
	// Bỏ qua cần người duyệt khác người chạy.
	code, _, errs = run("dlq", "skip", "--topic", "org.unit.changed.v1", "--consumer", "task-service", "--partition", "0", "--to", "4",
		"--reason", "bản ghi hỏng vĩnh viễn", "--approver", "ops.truc-ca-1", "--confirm")
	if code != 1 || !strings.Contains(errs, "khác người chạy") {
		t.Fatalf("cùng người duyệt: %d %s", code, errs)
	}
	code, out, errs = run("dlq", "skip", "--topic", "org.unit.changed.v1", "--consumer", "task-service", "--partition", "0", "--to", "4",
		"--reason", "bản ghi hỏng vĩnh viễn", "--approver", "ops.truong-ca", "--confirm")
	if code != 0 || len(e.audits()) != 2 {
		t.Fatalf("bỏ qua: %d %s %s", code, out, errs)
	}
	code, out, _ = run("dlq", "list")
	if !strings.Contains(out, "chưa xử lý 0") {
		t.Fatalf("list sau bỏ qua: %s", out)
	}
}
