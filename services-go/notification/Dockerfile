# syntax=docker/dockerfile:1
# SVC-12 notification-service: ảnh distroless, chạy không root (UID 65532). Dựng từ thư mục services-go để
# lấy được thư viện vtplatform: docker build -f notification/Dockerfile services-go
FROM golang:1.26-bookworm AS build
WORKDIR /src
COPY vtplatform ./vtplatform
COPY notification/go.mod notification/go.sum ./notification/
WORKDIR /src/notification
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; go mod download
COPY notification ./
RUN CGO_ENABLED=0 GOOS=linux GOTOOLCHAIN=local go build -trimpath -ldflags="-s -w" -o /out/notification-service ./cmd/notification-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/notification-service /notification-service
# Migration đi kèm ảnh để Job migrate (vai trò mig_notify) dùng cùng phiên bản mã.
COPY notification/db/migration/notify /db/migration/notify
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/notification-service"]
