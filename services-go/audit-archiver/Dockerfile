# syntax=docker/dockerfile:1
# INF-24 audit-archiver: ảnh distroless, chạy không root (UID 65532), hệ tệp gốc chỉ đọc khi triển khai.
# Dựng từ thư mục services-go (cần thư viện vtplatform cạnh bên): docker build -f audit-archiver/Dockerfile services-go
FROM golang:1.26-bookworm AS build
WORKDIR /src
COPY vtplatform ./vtplatform
COPY audit-archiver/go.mod audit-archiver/go.sum ./audit-archiver/
WORKDIR /src/audit-archiver
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; go mod download
COPY audit-archiver/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/audit-archiver ./cmd/audit-archiver \
 && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/audit-verify ./cmd/audit-verify

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/audit-archiver /audit-archiver
COPY --from=build /out/audit-verify /audit-verify
USER 65532:65532
EXPOSE 8081
ENTRYPOINT ["/audit-archiver"]
