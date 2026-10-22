# Ảnh OpenBao thử trên kind (INF-13): giống ảnh production (deploy/openbao/image/Containerfile) nhưng thay HSM thật
# bằng SoftHSM2 và thêm pkcs11-tool để sinh khoá mở trong token thử. Không dùng ngoài kind.
ARG OPENBAO_IMAGE=quay.io/openbao/openbao-ubi:2.7.1@sha256:33c4c3a7aeb8b812d570939bd7275f829901031e0a6b3e655207d8f0945bd248
ARG PLUGIN_IMAGE=ghcr.io/openbao/openbao-plugin-kms-pkcs11:v0.2.1@sha256:babba58243c7db7ffb9b906e25f35643704e8814792f5b678b3c215acba284f5
FROM ${PLUGIN_IMAGE} AS plugin
FROM ${OPENBAO_IMAGE}
USER 0
COPY --from=plugin --chmod=0755 /openbao-plugin-kms-pkcs11 /usr/local/libexec/openbao/openbao-plugin-kms-pkcs11
COPY rpm/*.rpm /tmp/
RUN rpm -i --nodeps /tmp/*.rpm && rm /tmp/*.rpm
USER 100
