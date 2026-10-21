# syntax=docker/dockerfile:1
# SVC-03 tenant-registry: ảnh distroless, chạy không root (UID 65532), hệ tệp gốc chỉ đọc khi triển khai.
# Dựng từ thư mục services-go/tenant-registry:  docker build -t tenant-registry .
FROM golang:1.26.9-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
# Bí mật tuỳ chọn ca_bundle: bộ CA của proxy doanh nghiệp (docker build --secret id=ca_bundle,src=...).
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOTOOLCHAIN=local go build -trimpath -ldflags="-s -w" -o /out/tenant-registry ./cmd/tenant-registry

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/tenant-registry /tenant-registry
# Migration Flyway đi kèm ảnh để Job migrate (Flyway, vai trò mig_registry) dùng cùng phiên bản mã.
COPY db/migration/registry /db/migration/registry
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/tenant-registry"]
