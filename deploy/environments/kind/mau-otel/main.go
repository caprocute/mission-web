// Dịch vụ mẫu cho kiểm thử quan sát trên kind (INF-14): nhận HTTP sau APISIX, ghi bản ghi Kafka mang traceparent,
// tự tiêu thụ bản ghi trong vai một consumer riêng, ghi log JSON kiểu ECS có trace.id và xuất metric /metrics.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

var prop = propagation.TraceContext{}
var soYeuCau, soTieuThu atomic.Int64

func tracer(ctx context.Context, ten string) (trace.Tracer, func()) {
	exp, err := otlptracegrpc.New(ctx, otlptracegrpc.WithInsecure(), otlptracegrpc.WithEndpoint(os.Getenv("OTLP_ENDPOINT")))
	if err != nil {
		log.Fatal(err)
	}
	tp := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exp, sdktrace.WithBatchTimeout(time.Second)),
		sdktrace.WithResource(resource.NewSchemaless(semconv.ServiceName(ten))))
	return tp.Tracer(ten), func() { _ = tp.Shutdown(context.Background()) }
}

// ghiLog in một dòng JSON theo lược đồ ECS (TL-04 mục 10.3).
func ghiLog(ctx context.Context, dataset, msg string, them map[string]any) {
	sc := trace.SpanContextFromContext(ctx)
	m := map[string]any{"@timestamp": time.Now().UTC().Format(time.RFC3339Nano), "log.level": "info",
		"service.name": "mission-core", "event.dataset": dataset, "message": msg, "cell": os.Getenv("CELL"),
		"trace": map[string]string{"id": sc.TraceID().String()}, "span": map[string]string{"id": sc.SpanID().String()},
		"tenant": map[string]string{"id": "t-thu"}, "level": "info"}
	for k, v := range them {
		m[k] = v
	}
	b, _ := json.Marshal(m)
	fmt.Println(string(b))
}

type hc struct{ h []kgo.RecordHeader }

func (c *hc) Get(k string) string {
	for _, x := range c.h {
		if x.Key == k {
			return string(x.Value)
		}
	}
	return ""
}
func (c *hc) Set(k, v string) { c.h = append(c.h, kgo.RecordHeader{Key: k, Value: []byte(v)}) }
func (c *hc) Keys() []string {
	var ks []string
	for _, x := range c.h {
		ks = append(ks, x.Key)
	}
	return ks
}

func main() {
	ctx := context.Background()
	tApi, tat1 := tracer(ctx, "mission-core")
	defer tat1()
	tCon, tat2 := tracer(ctx, "consumer-mau")
	defer tat2()
	brokers := strings.Split(os.Getenv("KAFKA_BROKERS"), ",")
	topic := "task.events.v1"
	pro, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.AllowAutoTopicCreation())
	if err != nil {
		log.Fatal(err)
	}
	con, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumeTopics(topic), kgo.ConsumerGroup("consumer-mau"),
		kgo.AllowAutoTopicCreation())
	if err != nil {
		log.Fatal(err)
	}
	go func() {
		for {
			fs := con.PollFetches(ctx)
			fs.EachRecord(func(r *kgo.Record) {
				pctx := prop.Extract(ctx, &hc{r.Headers})
				c2, sp := tCon.Start(pctx, "task.events.v1 process", trace.WithSpanKind(trace.SpanKindConsumer),
					trace.WithAttributes(attribute.String("messaging.system", "kafka"), attribute.String("messaging.destination.name", topic)))
				ghiLog(c2, "mission.consumer", "đã xử lý sự kiện", map[string]any{"task.id": string(r.Value)})
				soTieuThu.Add(1)
				sp.End()
			})
		}
	}()
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		pctx := prop.Extract(r.Context(), propagation.HeaderCarrier(r.Header))
		c1, sp := tApi.Start(pctx, r.Method+" /api/v1/tasks", trace.WithSpanKind(trace.SpanKindServer),
			trace.WithAttributes(attribute.String("http.request.method", r.Method), attribute.String("url.path", r.URL.Path)))
		defer sp.End()
		c2, sp2 := tApi.Start(c1, "task.events.v1 publish", trace.WithSpanKind(trace.SpanKindProducer),
			trace.WithAttributes(attribute.String("messaging.system", "kafka"), attribute.String("messaging.destination.name", topic)))
		h := &hc{}
		prop.Inject(c2, h)
		rec := &kgo.Record{Topic: topic, Value: []byte(r.URL.Query().Get("id")), Headers: h.h}
		if err := pro.ProduceSync(c2, rec).FirstErr(); err != nil {
			sp2.RecordError(err)
		}
		sp2.End()
		// Dòng log cố ý chứa dữ liệu nhạy cảm để kiểm lớp che thứ hai (Fluent Bit redact.lua).
		ghiLog(c1, "mission.api", "cập nhật nhiệm vụ", map[string]any{"debug": r.Header.Get("X-Thu-Pii")})
		soYeuCau.Add(1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"trace_id":"%s"}`, sp.SpanContext().TraceID())
	})
	go func() {
		m := http.NewServeMux()
		m.HandleFunc("/metrics", func(w http.ResponseWriter, _ *http.Request) {
			fmt.Fprintf(w, "# TYPE mau_requests_total counter\nmau_requests_total %d\n# TYPE mau_consumed_total counter\nmau_consumed_total %d\n",
				soYeuCau.Load(), soTieuThu.Load())
		})
		log.Fatal(http.ListenAndServe(":8081", m))
	}()
	log.Fatal(http.ListenAndServe(":8080", nil))
}
