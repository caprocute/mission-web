#!/bin/sh
# DV-10-03: máy chủ dấu thời gian RFC 3161 giả lập cho dev (YC-PT-13). Lần đầu sinh gốc CA và chứng thư
# TSA (EKU timeStamping critical) vào /var/lib/tsa; esign-service tin gốc qua
# ESIGN_TSA_TRUST_ANCHORS=infra/dev/softhsm/tsa/ca.pem. Không dùng ngoài dev.
set -eu
D=/var/lib/tsa
mkdir -p "$D"
cd "$D"
if [ ! -f tsa.crt ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out ca.key
  openssl req -new -x509 -key ca.key -sha256 -days 3650 -subj "/O=Mission dev/CN=Mission dev TSA Root" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.pem
  openssl ecparam -name prime256v1 -genkey -noout -out tsa.key
  openssl req -new -key tsa.key -subj "/O=Mission dev/CN=Mission dev TSA" -out tsa.csr
  printf 'extendedKeyUsage=critical,timeStamping\nkeyUsage=critical,digitalSignature\n' > tsa.ext
  openssl x509 -req -in tsa.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 1825 -sha256 \
    -extfile tsa.ext -out tsa.crt
  rm -f tsa.csr tsa.ext
  echo 01 > serial
fi
cat > tsa.cnf <<CNF
[ tsa ]
default_tsa = tsa_dev
[ tsa_dev ]
dir = $D
serial = \$dir/serial
signer_cert = \$dir/tsa.crt
certs = \$dir/ca.pem
signer_key = \$dir/tsa.key
signer_digest = sha256
default_policy = 1.3.6.1.4.1.99999.1
digests = sha256, sha384, sha512
accuracy = secs:1
ordering = no
tsa_name = no
ess_cert_id_chain = no
ess_cert_id_alg = sha256
CNF
exec python3 /opt/mission/tsa_server.py "$D/tsa.cnf"
