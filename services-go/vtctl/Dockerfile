# INF-26 · Ảnh không root của công cụ vận hành vtctl, cho Job chạy theo runbook và trạm PAM (TL-04 mục 11.7).
# Ngữ cảnh dựng là thư mục này (vtGoPipeline dùng dir làm context): docker build services-go/vtctl. Ảnh nền ghim
# digest (SEC-55).
FROM golang:1.26-alpine@sha256:27f2d3b83cfc5803c8078f8e730af0f7c431bf067afaec8f60a36fb763187cba AS builder
WORKDIR /src
COPY . ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/vtctl ./cmd/vtctl

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=builder /out/vtctl /usr/local/bin/vtctl
# UID số ≥ 10000 theo cổng Dockerfile của vtGoPipeline.
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/vtctl"]
