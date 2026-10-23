# syntax=docker/dockerfile:1
# OpenSearch dev kèm plugin analysis-icu: mẫu chỉ mục của search (SVC-16) dùng icu_tokenizer cho tiếng Việt, ảnh chính
# thức không có sẵn plugin này (search-service dừng lúc khởi động với lỗi 400 khi nạp mẫu). Tải zip bằng curl để dùng
# được CA của proxy dựng ảnh (build secret ca_bundle, tuỳ chọn) rồi cài từ tệp cục bộ.
ARG OPENSEARCH_VERSION=2.18.0
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}
ARG OPENSEARCH_VERSION
RUN --mount=type=secret,id=ca_bundle,required=false,uid=1000 \
    set -eu; ca=""; if [ -s /run/secrets/ca_bundle ]; then ca="--cacert /run/secrets/ca_bundle"; fi; \
    curl -fsSL ${ca} -o /tmp/analysis-icu.zip \
      "https://artifacts.opensearch.org/releases/plugins/analysis-icu/${OPENSEARCH_VERSION}/analysis-icu-${OPENSEARCH_VERSION}.zip"; \
    bin/opensearch-plugin install --batch file:///tmp/analysis-icu.zip; \
    rm -f /tmp/analysis-icu.zip
