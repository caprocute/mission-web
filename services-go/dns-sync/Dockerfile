# INF-20 · Ảnh không root cho dns-sync (vùng điều phối toàn cục) và edge-health (/healthz/edge của cell).
FROM golang:1.26-alpine AS builder
WORKDIR /workspace/services-go/dns-sync
COPY services-go/dns-sync ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/ ./cmd/...

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /out/dns-sync /out/edge-health /usr/local/bin/
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/dns-sync"]
