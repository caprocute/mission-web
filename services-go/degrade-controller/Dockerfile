# INF-25 · Ảnh không root cho bộ điều khiển suy giảm ba mức.
FROM golang:1.26-alpine@sha256:27f2d3b83cfc5803c8078f8e730af0f7c431bf067afaec8f60a36fb763187cba AS builder
WORKDIR /workspace/services-go/degrade-controller
COPY services-go/degrade-controller ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/degrade-controller ./cmd/degrade-controller

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=builder /out/degrade-controller /usr/local/bin/degrade-controller
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/degrade-controller"]
