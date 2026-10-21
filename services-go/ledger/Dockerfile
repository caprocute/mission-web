# syntax=docker/dockerfile:1
# SVC-09 ledger-service: ảnh distroless, chạy không root (UID 65532), hệ tệp gốc chỉ đọc khi triển khai.
FROM golang:1.25-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOTOOLCHAIN=local go build -trimpath -ldflags="-s -w" -o /out/ledger-service ./cmd/ledger-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/ledger-service /ledger-service
# Migration Flyway đi kèm ảnh để Job migrate (vai trò mig_ledger) dùng cùng phiên bản mã.
COPY db/migration/ledger /db/migration/ledger
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/ledger-service"]
