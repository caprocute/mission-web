# syntax=docker/dockerfile:1
# Ảnh dev dùng chung cho service Go của profile `backend` (INF-22). Ngữ cảnh dựng là services-go/ để lấy được
# thư viện vtplatform (go.mod của service có `replace ... => ../vtplatform`).
#   SERVICE: thư mục service (ledger, notification, tenant-registry); CMD: tên lệnh trong <SERVICE>/cmd.
# Nền alpine (không phải distroless như ảnh phát hành) để compose kiểm sức khoẻ bằng wget.
FROM golang:1.26-bookworm AS build
ARG SERVICE
ARG CMD
WORKDIR /src
COPY vtplatform ./vtplatform
COPY ${SERVICE}/go.mod ${SERVICE}/go.sum ./${SERVICE}/
WORKDIR /src/${SERVICE}
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -s /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; go mod download
COPY ${SERVICE} ./
RUN CGO_ENABLED=0 GOOS=linux GOTOOLCHAIN=local go build -trimpath -ldflags="-s -w" -o /out/app ./cmd/${CMD}

FROM alpine:3.20
# Chứng thư gốc bổ sung (BUILD_CA_BUNDLE của compose) chỉ dùng lúc tải gói, không vào ảnh.
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -s /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; \
    apk add --no-cache ca-certificates tzdata
COPY --from=build /out/app /app
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/app"]
