#!/usr/bin/env bash
# Kiểm thử các pipeline INF-06 trên Jenkins thật (Jenkinsfile Runner, cùng cách với chay-kiem-thu.sh của INF-05).
#
#   ci/vt-pipeline/tests/chay-kiem-thu-inf06.sh [go python helm policy ui]   (mặc định: tất cả)
#
# Mỗi loại có dự án mẫu tối thiểu (tests/fixture-inf06 hoặc chính thư mục của repo) và các kịch bản:
#   go      sach: MR sạch → QUA | govuln: gọi golang.org/x/text v0.3.7 → chặn ở govulncheck | main: ảnh, ký, GitOps
#   python  sach → QUA | pipaudit: jinja2 2.11.3 có lỗ hổng → chặn ở pip-audit | main: ảnh, ký, GitOps
#   helm    sach → QUA | root: thêm pod chạy root vào chart → chặn ở "Trivy config" (Kyverno ở "Ba mô hình phân phối" là lớp thứ hai) | main: đóng gói, ký chart
#   policy  sach → QUA | cavang: sửa một ca vàng → chặn ở "Ca vàng 100%" | main: đóng gói, ký, so sánh bóng, duyệt, gửi
#   ui      sach → QUA | css31: token phình CSS quá 30 KB → chặn ở "Lược đồ, registry, kích thước" | main: ảnh staging,
#           ký, tải lên, duyệt, phát hành
# API của MOD-05, MOD-08 là máy chủ giả tests/jenkins/api-gia.py (kiểm Idempotency-Key, If-Match, 202 + Location).
# Bước `input` dùng stub tự duyệt (tests/jenkins/stub-lib/vars/input.groovy). Không có bí mật thật: khoá cosign,
# khoá GPG, token giả đều sinh lúc chạy và xoá khi xong (VT_GIU_THU_MUC=1 để giữ).
# Công cụ cần trong PATH: git, docker (buildx), python3 (+ PyYAML, jsonschema, pytest, pytest-cov), go (bản vá mới
# nhất, govulncheck dựng bằng chính bản Go đó), pip-audit, node, npm, gitleaks, semgrep, trivy (đã có CSDL lỗ hổng),
# syft, cosign, helm, kubeconform, kube-linter, kyverno, gpg, jq, curl.
# Mạng: proxy.golang.org, vuln.go.dev, PyPI, npm, mirror.gcr.io, kho chart Helm của deploy/charts.
set -euo pipefail

LOAI=("$@"); [ ${#LOAI[@]} -gt 0 ] || LOAI=(go python helm policy ui)
GOC_REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
TESTS="${GOC_REPO}/ci/vt-pipeline/tests"
W="${VT_TEST_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/vt-inf06.XXXXXX")}"
KQ="${W:?}/ket-qua"; mkdir -p "${KQ}" "${W}/bin"
REG_PORT="${VT_REG_PORT:-5056}"; API_PORT="${VT_API_PORT:-5057}"; STG_PORT="${VT_STG_PORT:-5058}"
REG_NAME="vt-inf06-registry"
JFR_IMAGE="${VT_JFR_IMAGE:-jenkins/jenkinsfile-runner:latest}"
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL="${W}/gitconfig"
printf "[user]\n\tname = kiem-thu\n\temail = kiem-thu@vt.local\n[init]\n\tdefaultBranch = main\n[advice]\n\tdetachedHead = false\n" > "${GIT_CONFIG_GLOBAL}"
log() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
gitq() { git -c commit.gpgsign=false "$@"; }
PIDS=()
don_dep() {
  docker rm -f "${REG_NAME}" >/dev/null 2>&1 || true
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
  if [ -n "${VT_GNUPG:-}" ]; then rm -rf "${VT_GNUPG:?}"; fi
  if [ "${VT_GIU_THU_MUC:-0}" != 1 ]; then rm -rf "${W:?}/khoa" "${W:?}/ws" "${W:?}/jhome"; fi
}
trap don_dep EXIT

# ───────────── Repo mẫu: các phần của monorepo mà pipeline INF-06 đọc, cộng dự án mẫu ─────────────
chuan_bi_repo() {
  log "Dựng repo mẫu (origin.git)"
  local r="${W:?}/mau"; rm -rf "${r:?}" "${W:?}/origin.git"; mkdir -p "${r}"
  ( cd "${GOC_REPO}" && git ls-files -co --exclude-standard -- .gitleaks.toml .semgrep data/policy-pack data/golden \
      deploy/.trivyignore.yaml deploy/charts deploy/policies deploy/environments/staging/digests.yaml docs/phan-quyen/ma-tran.json \
      'frontend/projects/*/src/widgets' frontend/projects/sdui-renderer/src/lib/builtin-components.ts \
      frontend/projects/fe-shell/src/app/widgets | grep -v '/charts/[^/]*\.tgz$' | tar -cf - -T - ) | tar -xf - -C "${r}"
  mkdir -p "${r}/services-go" "${r}/services-py" "${r}/ui-packs/default" "${r}/ci-test"
  cp -r "${TESTS}/fixture-inf06/go" "${r}/services-go/mau"
  cp -r "${TESTS}/fixture-inf06/python" "${r}/services-py/mau"
  cp "${TESTS}/fixture-inf06/ui-pack/pack.json" "${r}/ui-packs/default/pack.json"
  # J6: bản phát hành cần bộ ca vàng đã ký; ở repo thật bộ ca còn là giả lập nên repo mẫu đóng vai "nghiệp vụ đã ký"
  python3 - "${r}/data/policy-pack/khoi-chinh-quyen/nd335/tests/bo-ca-mau-gia-lap.yaml" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s = re.sub(r"^gia_lap: .*$", "gia_lap: false", s, flags=re.M)
s = re.sub(r"^nguoi_ky: .*$", 'nguoi_ky: "Đại diện nghiệp vụ (kiểm thử)"', s, flags=re.M)
s = re.sub(r"^trang_thai_ky: .*$", "trang_thai_ky: DA_KY", s, flags=re.M)
open(p, "w", encoding="utf-8").write(s)
PY
  printf "@Library('vt-pipeline@vt-pipeline-v1.1.0') _\nvtGoPipeline(service: 'mau-go', dir: 'services-go/mau')\n" > "${r}/ci-test/go.Jenkinsfile"
  printf "@Library('vt-pipeline@vt-pipeline-v1.1.0') _\nvtPythonPipeline(service: 'mau-py', dir: 'services-py/mau')\n" > "${r}/ci-test/python.Jenkinsfile"
  printf "@Library('vt-pipeline@vt-pipeline-v1.1.0') _\n// INF-03 chưa gộp: lệnh giả để ca sạch và ca main đi hết; thiếu lệnh thì stage UNSTABLE và chặn đóng gói\nvtHelmPipeline(capacityCheckCmd: 'echo kiem-ngan-sach-gia-lap')\n" > "${r}/ci-test/helm.Jenkinsfile"
  cat > "${r}/ci-test/policy.Jenkinsfile" <<'J'
@Library('vt-pipeline@vt-pipeline-v1.1.0') _
vtPolicyPackPipeline(packs: ['khoi-chinh-quyen/nd335'], packIds: ['khoi-chinh-quyen/nd335': 'pp-thu'],
                     effectivePeriodId: 'ky-thu', scopes: [[type: 'TENANT', id: 't-thu']])
J
  printf "@Library('vt-pipeline@vt-pipeline-v1.1.0') _\nvtUiPackPipeline(dir: 'ui-packs', uiPackId: 'ui-thu')\n" > "${r}/ci-test/ui.Jenkinsfile"
  ( cd "${r}" && git init -q -b main && gitq add -A && gitq commit -q -m "repo mẫu INF-06" )
  git clone -q --bare "${r}" "${W}/origin.git"
}

# Kịch bản: bản sao mới từ origin, nhánh MR (hoặc main) và thay đổi của kịch bản
tao_ws() {  # <tên> <nhánh>
  local ws="${W:?}/ws/$1"; rm -rf "${ws:?}"; mkdir -p "${W}/ws"
  git clone -q "${W}/origin.git" "${ws}"
  ( cd "${ws}" && { [ "$2" = main ] || git checkout -q -b "$2"; } )
  echo "${ws}"
}
thay_doi() {  # <ws> <kịch bản>
  local ws="$1"
  case "$2" in
    go-sach) printf '\n// Hằng mới của MR sạch.\nconst phienBan = "1.0.1"\n' >> "${ws}/services-go/mau/main.go" ;;
    go-govuln)
      cat > "${ws}/services-go/mau/ngon_ngu.go" <<'G'
package main

import "golang.org/x/text/language"

// Gọi từ init để hàm có lỗ hổng nằm trên đồ thị lời gọi của chương trình (govulncheck bỏ qua mã chỉ test gọi)
// GO-2022-1059 nằm ở ParseAcceptLanguage (đọc header Accept-Language)
var ngonNguMacDinh = ngonNgu("vi-VN,vi;q=0.9")

func ngonNgu(s string) string {
	t, _, err := language.ParseAcceptLanguage(s)
	if err != nil || len(t) == 0 {
		return "vi"
	}
	return t[0].String()
}
G
      cat >> "${ws}/services-go/mau/main_test.go" <<'G'

func TestNgonNgu(t *testing.T) {
	if ngonNgu("vi-VN") != "vi-VN" || ngonNgu("") != "vi" {
		t.Fatal("ngôn ngữ sai")
	}
}
G
      ( cd "${ws}/services-go/mau" && go get golang.org/x/text@v0.3.7 >/dev/null 2>&1 ) ;;
    python-sach) printf '\n\ndef phien_ban() -> str:\n    return "1.0.1"\n' >> "${ws}/services-py/mau/app.py"
      printf '\n\ndef test_phien_ban():\n    from app import phien_ban\n    assert phien_ban() == "1.0.1"\n' >> "${ws}/services-py/mau/test_app.py" ;;
    python-pipaudit) cp "${W}/req-loi.txt" "${ws}/services-py/mau/requirements.txt" ;;
    helm-sach) sed -i 's/^description: .*/&  # MR sạch/' "${ws}/deploy/charts/web-frontend/Chart.yaml" ;;
    helm-root)
      cat > "${ws}/deploy/charts/web-frontend/templates/thu-chay-root.yaml" <<'Y'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: thu-chay-root
  namespace: platform
  labels: {app.kubernetes.io/part-of: vt-platform, app.kubernetes.io/name: thu-chay-root}
spec:
  replicas: 3
  selector: {matchLabels: {app: thu-chay-root}}
  template:
    metadata: {labels: {app: thu-chay-root}}
    spec:
      securityContext: {runAsUser: 0}
      containers:
        - name: app
          image: harbor.vt.local/okr/thu@sha256:0000000000000000000000000000000000000000000000000000000000000000
Y
      ;;
    policy-sach) printf '\n- Ghi chú MR sạch (kiểm thử pipeline).\n' >> "${ws}/data/policy-pack/khoi-chinh-quyen/nd335/CHANGELOG.md" ;;
    policy-cavang) python3 - "${ws}/data/policy-pack/khoi-chinh-quyen/nd335/tests/bo-ca-mau-gia-lap.yaml" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
cu = "mong_doi: {diem: 100.0, dong_dmn: xep-loai-nha-nuoc-dong-1, xep_loai: HTXSNV}"
assert cu in s
open(p, "w", encoding="utf-8").write(s.replace(cu, cu.replace("100.0", "95.0"), 1))
PY
      ;;
    ui-sach) python3 - "${ws}/ui-packs/default/pack.json" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["version"] = "1.0.1"; json.dump(d, open(p, "w"), ensure_ascii=False, indent=2)
PY
      ;;
    ui-css31) python3 - "${ws}/ui-packs/default/pack.json" <<'PY'
import json, secrets, sys
p = sys.argv[1]; d = json.load(open(p))
# Thêm token màu ngẫu nhiên tới khi CSS biên dịch vượt 30 KB gzip (gzip nén kém trên dữ liệu ngẫu nhiên)
d["tokens"]["mauPhu"] = {f"m{i}": {"$type": "color", "$value": "#" + secrets.token_hex(3)} for i in range(6000)}
json.dump(d, open(p, "w"), ensure_ascii=False, indent=2)
PY
      ;;
  esac
  ( cd "${ws}" && gitq add -A && { git diff --cached --quiet || gitq commit -q -m "kịch bản $2"; } )
}

# ───────────── Jenkinsfile Runner, registry, API giả, staging giả, khoá tạm ─────────────
tk() { python3 -c 'import secrets;print("kiem-thu-"+secrets.token_hex(8))'; }
chuan_bi_jenkins() {
  log "Chuẩn bị Jenkinsfile Runner, registry, API giả, staging giả, khoá tạm"
  local jfr="${VT_JFR_CACHE:-${W:?}/jfr}"
  if [ ! -x "${jfr}/app/bin/jenkinsfile-runner" ]; then
    rm -rf "${jfr:?}"; mkdir -p "${jfr}"
    local cid; cid=$(docker create "${JFR_IMAGE}")
    docker cp -q "${cid}:/app" "${jfr}/app"; docker cp -q "${cid}:/opt/java/openjdk" "${jfr}/jdk"
    docker cp -q "${cid}:/usr/share/jenkins/ref/plugins" "${jfr}/plugins"; docker rm "${cid}" >/dev/null
  fi
  JFR="${jfr}"
  # Bản sao thư viện: thay mọi khối agent kubernetes bằng agent any (không có plugin kubernetes)
  rm -rf "${W:?}/lib" "${W:?}/stub"; mkdir -p "${W}/lib/ci"
  cp -r "${GOC_REPO}/ci/vt-pipeline" "${W}/lib/ci/vt-pipeline"; rm -rf "${W:?}/lib/ci/vt-pipeline/tests"
  python3 - "${W}/lib/ci/vt-pipeline/vars" <<'PY'
import glob, re, sys
for p in glob.glob(sys.argv[1] + "/vt*Pipeline.groovy"):
    s = open(p).read()
    s2, n = re.subn(r"agent \{\n\s+kubernetes \{.*?\n\s+\}\n\s+\}", "agent any   // KIỂM THỬ CỤC BỘ", s, count=1, flags=re.S)
    assert n == 1, p
    open(p, "w").write(s2)
PY
  ( cd "${W}/lib" && git init -q -b main && gitq add -A && gitq commit -q -m "vt-pipeline (bản kiểm thử)" && git tag vt-pipeline-v1.1.0 )
  cp -r "${TESTS}/jenkins/stub-lib" "${W}/stub"
  ( cd "${W}/stub" && git init -q -b main && gitq add -A && gitq commit -q -m "stub kiểm thử" )

  docker rm -f "${REG_NAME}" >/dev/null 2>&1 || true
  docker run -d --rm --name "${REG_NAME}" -p "127.0.0.1:${REG_PORT}:5000" "${VT_REG_IMAGE:-mirror.gcr.io/library/registry:2}" >/dev/null
  for _ in $(seq 1 30); do curl -sf "http://127.0.0.1:${REG_PORT}/v2/" >/dev/null && break; sleep 1; done
  : > "${KQ}/api.jsonl"
  python3 "${TESTS}/jenkins/api-gia.py" "${API_PORT}" "${KQ}/api.jsonl" & PIDS+=($!)
  # Staging giả: ba trang dựng từ trang mẫu của UI Pack hiện hành
  mkdir -p "${W}/staging/okr" "${W}/staging/appraisal"
  python3 "${GOC_REPO}/ci/vt-pipeline/resources/vn/vt/pipeline/vt-ui-pack.py" trang-mau "${TESTS}/fixture-inf06/ui-pack/pack.json" "${W}/staging/index.html"
  cp "${W}/staging/index.html" "${W}/staging/okr/index.html"; cp "${W}/staging/index.html" "${W}/staging/appraisal/index.html"
  ( cd "${W}/staging" && exec python3 -m http.server "${STG_PORT}" --bind 127.0.0.1 >/dev/null 2>&1 ) & PIDS+=($!)
  # Phụ thuộc Node của bước axe, ảnh chụp (trong cụm nằm sẵn trong ảnh container node)
  if [ ! -d "${W}/node/node_modules/axe-core" ]; then
    mkdir -p "${W}/node" && ( cd "${W}/node" && npm init -y >/dev/null && npm i -s playwright-core@1.56.1 axe-core@4.11.0 >/dev/null 2>&1 )
  fi
  # Khoá cosign tạm và khoá GPG tạm cho commit GitOps
  mkdir -p "${W}/khoa"; chmod 700 "${W}/khoa"; rm -f "${W:?}/khoa/cosign.key" "${W:?}/khoa/cosign.pub"
  COSIGN_PASSWORD="$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')"; export COSIGN_PASSWORD
  ( cd "${W}/khoa" && cosign generate-key-pair --output-key-prefix cosign >/dev/null 2>&1 )
  VT_GNUPG="$(mktemp -d /tmp/vtgpg.XXXXXX)"; export GNUPGHOME="${VT_GNUPG}"
  gpg --batch --quiet --passphrase '' --quick-gen-key 'vt-pipeline kiem thu <vt-pipeline@jenkins.vt.local>' ed25519 sign never
  gpg --batch --quiet --armor --export-secret-keys > "${W}/khoa/gitops.asc"; unset GNUPGHOME
  cp "${GOC_REPO}/ci/vt-pipeline/resources/vn/vt/pipeline/vt-provenance.py" "${W}/bin/vt-provenance"; chmod +x "${W}/bin/vt-provenance"
  # Gói Python có lỗ hổng, ghim kèm hash, cho kịch bản pip-audit
  rm -rf "${W:?}/pd"; mkdir "${W}/pd"; pip download -q --no-deps --only-binary :all: -d "${W}/pd" jinja2==2.11.3
  printf 'jinja2==2.11.3 --hash=sha256:%s\n' "$(sha256sum "${W}"/pd/*.whl | cut -d' ' -f1)" > "${W}/req-loi.txt"
  cat > "${W}/casc.yaml" <<YAML
jenkins:
  numExecutors: 4
unclassified:
  globalLibraries:
    libraries:
      - name: vt-pipeline
        defaultVersion: vt-pipeline-v1.1.0
        allowVersionOverride: true
        implicit: false
        retriever: {modernSCM: {libraryPath: ci/vt-pipeline/, scm: {git: {remote: "file://${W}/lib"}}}}
      - name: vt-test-stub
        defaultVersion: main
        implicit: true
        retriever: {modernSCM: {scm: {git: {remote: "file://${W}/stub"}}}}
credentials:
  system:
    domainCredentials:
      - credentials:
          - string: {scope: GLOBAL, id: openbao-cosign-token, secret: "$(tk)"}
          - string: {scope: GLOBAL, id: mission-ci-policy-pack, secret: "$(tk)"}
          - string: {scope: GLOBAL, id: mission-ci-ui-pack, secret: "$(tk)"}
          - file: {scope: GLOBAL, id: gitops-signing-gpg, fileName: gitops.asc, secretBytes: "\${readFileBase64:${W}/khoa/gitops.asc}"}
YAML
}

chay() {  # <loại> <kịch bản> <nhánh> [CHANGE_TARGET]
  local loai="$1" kb="$2" nhanh="$3" dich="${4:-}" ten="$1-$2"
  local ws; ws=$(tao_ws "${ten}" "${nhanh}"); thay_doi "${ws}" "${ten}"
  local h="${W:?}/jhome/${ten}"; rm -rf "${h:?}"; mkdir -p "${h}"
  local sg=".semgrep/vt-rules-${loai}.yml"
  local args=(-a "BRANCH_NAME=${nhanh}" -a "GIT_URL=file://${W}/origin.git"
    -a "VT_REGISTRY=localhost:${REG_PORT}/okr" -a "VT_REGISTRY_CREDENTIALS_ID=-" -a "VT_SEMGREP_CONFIGS=${sg}"
    -a "VT_DEPENDENCY_TRACK=false" -a "VT_IMAGE_BUILDER=docker"
    -a "VT_COSIGN_KEY=${W}/khoa/cosign.key" -a "VT_COSIGN_VERIFY_KEY=${W}/khoa/cosign.pub" -a "VT_VAULT_ADDR=http://127.0.0.1:1"
    -a "VT_GITOPS_REPO=file://${W}/origin.git" -a "VT_GITOPS_CREDENTIALS_ID=-" -a "VT_BUILDER_ID=https://jenkins.vt.local/okr-builder"
    -a "VT_API_BASE=http://127.0.0.1:${API_PORT}/api/v1" -a "VT_STAGING_URL=http://127.0.0.1:${STG_PORT}/"
    -a "VT_CHART_REPO=oci://localhost:${REG_PORT}/charts" -a "VT_CHART_PUSH_FLAGS=--plain-http")
  if [ -n "${dich}" ]; then args+=(-a "CHANGE_TARGET=${dich}" -a "CHANGE_ID=${ten}"); fi
  local bd ma; bd=$(date +%s)
  set +e
  PATH="${W}/bin:${PATH}" NODE_PATH="${W}/node/node_modules" CASC_JENKINS_CONFIG="${W}/casc.yaml" JAVACMD="${JFR}/jdk/bin/java" \
  JAVA_OPTS="-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true -Djenkins.install.runSetupWizard=false -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Xmx1g" \
  LC_ALL=C.UTF-8 LANG=C.UTF-8 TRIVY_SKIP_DB_UPDATE=true TRIVY_SKIP_JAVA_DB_UPDATE=true TRIVY_INSECURE=true \
  TRIVY_NO_PROGRESS=true SYFT_REGISTRY_INSECURE_USE_HTTP=true SYFT_CHECK_FOR_APP_UPDATE=false \
    "${JFR}/app/bin/jenkinsfile-runner" -w "${JFR}/app/jenkins" -p "${JFR}/plugins" -f "${ws}/ci-test/${loai}.Jenkinsfile" \
      --runWorkspace "${ws}" --runHome "${h}" -n "inf06-${ten}" -u "${args[@]}" > "${KQ}/jenkins-${ten}.log" 2>&1
  ma=$?
  set -e
  python3 "${TESTS}/jenkins/thoi-gian-stage.py" "${h}" > "${KQ}/jenkins-${ten}-stage.tsv" 2>/dev/null || true
  printf '%s\t%s\t%s\n' "${ten}" "${ma}" "$(( $(date +%s) - bd ))" >> "${KQ}/jenkins.tsv"
  echo "   ${ten}: mã thoát ${ma}, $(( $(date +%s) - bd )) s"
}
stage_loi() { awk -F'\t' '$2=="FAILURE"{print $1}' "${KQ}/jenkins-$1-stage.tsv" 2>/dev/null | paste -sd, -; }

DAT=0; HONG=0
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT+1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG+1)); }
ma_cua() { awk -F'\t' -v t="$1" '$1==t{print $2}' "${KQ}/jenkins.tsv"; }
ky_vong_qua() {
  local m; m=$(ma_cua "$1")
  if [ "$m" = 0 ]; then ok "$1 qua hết các stage"; else hong "$1 lẽ ra phải qua (mã ${m}, stage lỗi: $(stage_loi "$1"))"; tail -40 "${KQ}/jenkins-$1.log"; fi
}
ky_vong_chan() {
  local m s; m=$(ma_cua "$1"); s=$(stage_loi "$1")
  if [ "$m" != 0 ] && [[ ",$s," == *",$2,"* ]]; then ok "$1 bị chặn đúng ở stage \"$2\""
  else hong "$1: mong chặn ở \"$2\", thực tế mã ${m}, stage lỗi \"${s}\""; tail -40 "${KQ}/jenkins-$1.log"; fi
}

chuan_bi_repo
chuan_bi_jenkins
: > "${KQ}/jenkins.tsv"
for l in "${LOAI[@]}"; do
  log "Loại ${l}"
  case "$l" in
    go)      chay go sach mr-sach main; chay go govuln mr-govuln main; chay go main main
             ky_vong_qua go-sach; ky_vong_chan go-govuln govulncheck; ky_vong_qua go-main
             if curl -sf "http://127.0.0.1:${REG_PORT}/v2/okr/mau-go/tags/list" | grep -q '\.sig"'; then ok "ảnh mau-go có chữ ký cosign trong registry"; else hong "ảnh mau-go thiếu chữ ký"; fi ;;
    python)  chay python sach mr-sach main; chay python pipaudit mr-pipaudit main; chay python main main
             ky_vong_qua python-sach; ky_vong_chan python-pipaudit pip-audit; ky_vong_qua python-main ;;
    helm)    chay helm sach mr-sach main; chay helm root mr-root main; chay helm main main
             ky_vong_qua helm-sach; ky_vong_chan helm-root "Trivy config"; ky_vong_qua helm-main ;;
    policy)  chay policy sach mr-sach main; chay policy cavang mr-cavang main; chay policy main main
             ky_vong_qua policy-sach; ky_vong_chan policy-cavang "Ca vàng 100%"; ky_vong_qua policy-main ;;
    ui)      chay ui sach mr-sach main; chay ui css31 mr-css31 main; chay ui main main
             ky_vong_qua ui-sach; ky_vong_chan ui-css31 "Lược đồ, registry, kích thước"; ky_vong_qua ui-main
             if ls "${W}"/ws/ui-main/.vt-ui/anh-0/*-khac.png >/dev/null 2>&1; then ok "ảnh chụp so sánh trên staging đã sinh"; else hong "thiếu ảnh chụp so sánh"; fi ;;
  esac
done
if [ -s "${KQ}/api.jsonl" ]; then
  log "Đối chiếu lời gọi API với quy ước baseline mục 13"
  if python3 - "${KQ}/api.jsonl" "${LOAI[*]}" <<'PY'
import json, sys
d = [json.loads(x) for x in open(sys.argv[1])]
loai = sys.argv[2].split()
ops = [x["op"] for x in d if x.get("op") and x["op"] != "getJob"]
for x in d:
    print(f"    {x['method']:4} {x['path']:52} {x.get('op', '?'):24} {x['ket_qua']} idem={x['idempotencyKey']} if-match={x['ifMatch']}")
can = (["runPolicyTests", "submitPolicyPackVersion"] if "policy" in loai else []) + \
      (["createUiPackVersion", "updateUiPackVersion", "submitUiPackVersion", "publishUiPackVersion"] if "ui" in loai else [])
thieu = [o for o in can if o not in ops]
sai = [x for x in d if x["ket_qua"] >= 400]
if thieu or sai:
    print("    thiếu:", thieu, "lỗi:", [(x.get("op"), x["ket_qua"]) for x in sai]); sys.exit(1)
PY
  then ok "lời gọi API đúng quy ước (Idempotency-Key, If-Match, 202 + Location)"; else hong "lời gọi API sai quy ước"; fi
fi
log "Thời gian từng stage"
for f in "${KQ}"/jenkins-*-stage.tsv; do
  echo "  $(basename "$f" -stage.tsv | sed 's/^jenkins-//'): $(awk -F'\t' '{printf "%s=%s/%ss; ", $1, $2, $3}' "$f")"
done
echo; echo "Kết quả: ${DAT} đạt, ${HONG} hỏng (chi tiết: ${KQ})"
[ "${HONG}" -eq 0 ]
