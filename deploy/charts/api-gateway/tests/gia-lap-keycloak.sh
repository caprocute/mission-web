#!/usr/bin/env bash
# Keycloak giả lập trong namespace identity cho kiểm thử trên kind: sinh khoá RS256 mới, phục vụ discovery và JWKS tĩnh
# của realm mission, ghi hai JWT vào <thư mục>/tok (claim cell=CELL-KIND) và <thư mục>/tok_sai (cell=CELL-HCM).
# Cần python3 có PyJWT + cryptography. Sau khi chạy, khởi động lại APISIX để bỏ JWKS cũ đã lưu đệm.
set -euo pipefail
TMP=$1
PY=docker.io/library/python@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4
python3 - "$TMP" <<'PY'
import json, sys, time, jwt
from cryptography.hazmat.primitives.asymmetric import rsa
d = sys.argv[1]
k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
iss = "http://keycloak.identity.svc:8080/realms/mission"
jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(k.public_key())); jwk.update(kid="thu", use="sig", alg="RS256")
open(f"{d}/jwks", "w").write(json.dumps({"keys": [jwk]}))
open(f"{d}/discovery", "w").write(json.dumps({"issuer": iss, "jwks_uri": iss + "/protocol/openid-connect/certs",
  "authorization_endpoint": iss + "/protocol/openid-connect/auth", "token_endpoint": iss + "/protocol/openid-connect/token",
  "id_token_signing_alg_values_supported": ["RS256"]}))
now = int(time.time())
for ten, cell in (("tok", "CELL-KIND"), ("tok_sai", "CELL-HCM")):
    t = jwt.encode({"iss": iss, "sub": "u-thu", "aud": "apisix-gateway", "azp": "mission-web", "tenant_id": "t-thu",
                    "cell": cell, "iat": now, "exp": now + 3600}, k, algorithm="RS256", headers={"kid": "thu"})
    open(f"{d}/{ten}", "w").write(t)
PY
# Mỗi lần chạy sinh khoá mới: tạo lại pod giả lập để volume ConfigMap mang JWKS mới.
kubectl -n identity delete pod keycloak --ignore-not-found >/dev/null
kubectl -n identity create configmap keycloak-thu --from-file=discovery="$TMP/discovery" --from-file=jwks="$TMP/jwks" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n identity apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: { name: keycloak, labels: { app.kubernetes.io/name: keycloak } }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 10001, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: c
      image: $PY
      imagePullPolicy: IfNotPresent
      command: ["python", "-m", "http.server", "8080", "--directory", "/srv"]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
      volumeMounts: [{ name: d, mountPath: /srv }]
  volumes:
    - name: d
      configMap:
        name: keycloak-thu
        items:
          - { key: discovery, path: realms/mission/.well-known/openid-configuration }
          - { key: jwks, path: realms/mission/protocol/openid-connect/certs }
---
apiVersion: v1
kind: Service
metadata: { name: keycloak }
spec: { selector: { app.kubernetes.io/name: keycloak }, ports: [{ port: 8080 }] }
YAML
kubectl -n identity wait --for=condition=Ready pod/keycloak --timeout=90s >/dev/null
