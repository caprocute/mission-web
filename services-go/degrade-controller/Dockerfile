# INF-25 · Ảnh không root cho bộ điều khiển suy giảm ba mức.
FROM golang:1.26-alpine AS builder
WORKDIR /workspace/services-go/degrade-controller
COPY services-go/degrade-controller ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/degrade-controller ./cmd/degrade-controller

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /out/degrade-controller /usr/local/bin/degrade-controller
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/degrade-controller"]
