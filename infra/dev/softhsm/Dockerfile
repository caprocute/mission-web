# DV-10-03 · SoftHSM2 (HSM giả lập cho dev) và máy chủ dấu thời gian RFC 3161 giả lập (YC-PT-13).
# Một ảnh cho cả hai service softhsm-init và tsa-mock của profile backend.
FROM ubuntu:24.04
RUN apt-get update \
 && apt-get install -y --no-install-recommends softhsm2 opensc openssl python3 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY softhsm2.conf /etc/softhsm/softhsm2.conf
COPY init-token.sh tsa-mock.sh tsa_server.py /opt/mission/
RUN chmod +x /opt/mission/*.sh
ENV SOFTHSM2_CONF=/etc/softhsm/softhsm2.conf
