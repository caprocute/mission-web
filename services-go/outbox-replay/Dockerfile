# NEN-022 · ADR-03 · Ảnh container không root cho công cụ phát lại outbox (chạy dạng Kubernetes Job).
FROM golang:1.26-alpine AS builder

WORKDIR /workspace
COPY services-go/vtplatform ./services-go/vtplatform
COPY services-go/outbox-replay ./services-go/outbox-replay

WORKDIR /workspace/services-go/outbox-replay
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -trimpath -ldflags="-s -w" -o /out/outbox-replay ./cmd/outbox-replay

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=builder /out/outbox-replay /usr/local/bin/outbox-replay
COPY contracts/events/catalog.yaml /etc/mission/events/catalog.yaml
COPY contracts/events/outbox-sources.yaml /etc/mission/events/outbox-sources.yaml

USER 65532:65532
ENTRYPOINT ["/usr/local/bin/outbox-replay"]
