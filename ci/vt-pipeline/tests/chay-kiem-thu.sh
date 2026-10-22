#!/usr/bin/env bash
# Kiểm thử thư viện vt-pipeline (INF-05) trên dự án Maven mẫu và năm kịch bản merge request.
#
#   ci/vt-pipeline/tests/chay-kiem-thu.sh [cong|jenkins|tat-ca]     (mặc định: tat-ca)
#
# Hai tầng kiểm thử:
#   cong    – chạy trực tiếp từng cổng chặn đúng như vtJavaPipeline gọi (gitleaks, Semgrep, Trivy fs,
#             ./mvnw verify -Pci, openapi-diff) trên từng kịch bản, in bảng mã thoát và thời gian.
#   jenkins – chạy chính backend/Jenkinsfile và thư viện ci/vt-pipeline trên Jenkins thật (Jenkinsfile
#             Runner trong ảnh jenkins/jenkinsfile-runner, có pipeline-model-definition và
#             pipeline-groovy-lib): bốn MR sai bị chặn ở đúng stage, MR sạch qua hết, rồi một bản dựng
#             nhánh main dựng ảnh bằng Jib vào registry cục bộ, ký và chứng thực bằng cosign với khoá
#             sinh tạm, tự kiểm `cosign verify` và `cosign verify-attestation --type slsaprovenance1`,
#             và đẩy commit có chữ ký GPG cập nhật digests.yaml.
#
# Kịch bản (TL-04 "Đợt 0c", tiêu chí INF-05):
#   sach      MR sạch: thêm chức năng có kiểm thử, thêm một thao tác API (tương thích) → phải QUA
#   test-loi  kiểm thử đơn vị sai                                               → chặn ở mvn verify
#   bi-mat    chuỗi bí mật giả (khoá truy cập dạng AWS sinh ngẫu nhiên lúc chạy) → chặn ở gitleaks
#   cve       thêm log4j-core 2.14.1 (CVE-2021-44228, Critical)                  → chặn ở Trivy fs
#   hop-dong  xoá thao tác cancelJob khỏi hợp đồng OpenAPI                       → chặn ở openapi-diff
#
# Không có bí mật nào nằm thô trong repo: chuỗi bí mật giả, mật khẩu khoá cosign, khoá GPG đều sinh lúc
# chạy trong thư mục tạm và bị xoá khi xong (đặt VT_GIU_THU_MUC=1 để giữ lại xem).
#
# Công cụ cần trong PATH: git, docker, java 21, python3, gpg, jq, curl, gitleaks, semgrep, trivy (đã có
# CSDL lỗ hổng), syft, cosign. Mạng: Maven Central (phụ thuộc, Maven Wrapper), Docker Hub/gcr.io (ảnh nền
# distroless, registry:2, openapitools/openapi-diff, jenkins/jenkinsfile-runner).
#
# Giới hạn đã biết (ghi rõ trong báo cáo INF-05): Semgrep chỉ chạy bộ quy tắc cục bộ vì semgrep.dev (p/java)
# không truy cập được từ máy kiểm thử; plugin kubernetes và coverage không cài được nên khối `agent` được
# thay bằng `agent any` trong bản sao thư viện dùng để kiểm thử và hai bước `container`, `recordCoverage`
# được thay bằng bản stub trong tests/jenkins/stub-lib; SonarQube, Dependency-Track, Pact tắt bằng biến
# VT_* cấp controller vì không có máy chủ.
set -euo pipefail

CHE_DO="${1:-tat-ca}"
GOC_REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
TESTS="${GOC_REPO}/ci/vt-pipeline/tests"
W="${VT_TEST_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/vt-pipeline-test.XXXXXX")}"
mkdir -p "${W}"
KQ="${W}/ket-qua"
mkdir -p "${KQ}"
REG_PORT="${VT_REG_PORT:-5055}"
REG_NAME="vt-inf05-registry"
JFR_IMAGE="${VT_JFR_IMAGE:-jenkins/jenkinsfile-runner:latest}"
OAD_IMAGE="${VT_OAD_IMAGE:-openapitools/openapi-diff:latest}"
KICH_BAN_LOI=(test-loi bi-mat cve hop-dong)
export GIT_CONFIG_NOSYSTEM=1
# Cấu hình git riêng của lần kiểm thử, không phụ thuộc ~/.gitconfig của máy (ký commit, push.negotiate...)
export GIT_CONFIG_GLOBAL="${W}/gitconfig"
printf "[user]\n\tname = kiem-thu\n\temail = kiem-thu@vt.local\n[init]\n\tdefaultBranch = main\n[advice]\n\tdetachedHead = false\n" > "${GIT_CONFIG_GLOBAL}"
# Một số máy chỉ đặt HTTPS_PROXY; wget của Maven Wrapper chỉ đọc biến chữ thường
if [ -n "${HTTPS_PROXY:-}" ] && [ -z "${https_proxy:-}" ]; then export https_proxy="${HTTPS_PROXY}"; fi

log() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
gitq() { git -c user.name=kiem-thu -c user.email=kiem-thu@vt.local -c commit.gpgsign=false "$@"; }

don_dep() {
  docker rm -f "${REG_NAME}" >/dev/null 2>&1 || true
  if [ -n "${VT_GNUPG:-}" ]; then GNUPGHOME="${VT_GNUPG}" gpgconf --kill gpg-agent >/dev/null 2>&1 || true; rm -rf "${VT_GNUPG}"; fi
  if [ "${VT_GIU_THU_MUC:-0}" != 1 ]; then
    rm -rf "${W}/khoa"
    rm -rf "${W}"
  else
    echo "Giữ thư mục kiểm thử: ${W}"
  fi
}
trap don_dep EXIT

# ─────────────────────────── Chuẩn bị repo mẫu ───────────────────────────

chuan_bi_repo() {
  log "Dựng repo mẫu (monorepo thu nhỏ) trong ${W}/goc"
  rm -rf "${W}/goc" "${W}/origin.git"
  mkdir -p "${W}/goc"
  cp -r "${TESTS}/fixture-repo/." "${W}/goc/"
  mkdir -p "${W}/goc/.semgrep" "${W}/goc/deploy/environments/staging"
  cp "${GOC_REPO}/.semgrep/vt-rules.yml" "${W}/goc/.semgrep/"
  cp "${GOC_REPO}/.gitleaks.toml" "${W}/goc/"
  cp "${GOC_REPO}/backend/Jenkinsfile" "${W}/goc/backend/Jenkinsfile"
  cp "${GOC_REPO}/deploy/environments/staging/digests.yaml" "${W}/goc/deploy/environments/staging/"
  printf 'target/\n' > "${W}/goc/backend/.gitignore"
  ( cd "${W}/goc"
    git init -q -b main
    gitq add -A
    gitq commit -q -m "Nền: dự án mẫu cho kiểm thử vt-pipeline"
    git init -q --bare "${W}/origin.git"
    git remote add origin "${W}/origin.git"
    git push -q origin main
    git fetch -q origin )
}

# Tạo bản sao cho một kịch bản: nhánh mr/<tên> từ main, áp thay đổi, commit
tao_kich_ban() {
  local ten="$1" d="${W}/kb/$1"
  rm -rf "${d}"
  git clone -q "${W}/origin.git" "${d}"
  ( cd "${d}"
    git checkout -q -b "mr/${ten}"
    case "${ten}" in
      sach)
        python3 - <<'PY'
p='backend/src/main/java/vn/vt/mission/fixture/domain/TienDoNhiemVu.java'
s=open(p).read()
s=s.replace('''  public boolean hoanThanh() {''','''  /** Phần còn lại tới khi hoàn thành. */
  public int conLai() {
    return 100 - phanTram;
  }

  public boolean hoanThanh() {''')
open(p,'w').write(s)
p='backend/src/test/java/vn/vt/mission/fixture/domain/TienDoNhiemVuTest.java'
s=open(p).read()
s=s.replace('''  @Test
  void hoanThanhKhiDat100() {''','''  @Test
  void conLaiTinhDung() {
    assertEquals(30, new TienDoNhiemVu(70).conLai());
  }

  @Test
  void hoanThanhKhiDat100() {''')
open(p,'w').write(s)
# Thêm một thao tác đọc mới (thay đổi tương thích) vào hợp đồng
p='contracts/openapi/modules/jobs.yaml'
s=open(p).read()
s=s.replace('''paths:
''','''paths:
  /jobs/{id}/log:
    parameters:
      - name: id
        in: path
        required: true
        schema: { type: string }
    get:
      tags: [Tác vụ dài]
      operationId: getJobLog
      summary: Nhật ký tác vụ dài
      responses:
        '200':
          description: Nhật ký.
          content:
            text/plain:
              schema: { type: string }
''',1)
open(p,'w').write(s)
PY
        ;;
      test-loi)
        sed -i 's/assertEquals(60, moi.phanTram());/assertEquals(61, moi.phanTram());/' \
          backend/src/test/java/vn/vt/mission/fixture/domain/TienDoNhiemVuTest.java
        ;;
      bi-mat)
        # Chuỗi bí mật GIẢ sinh ngẫu nhiên lúc chạy, không nằm thô trong repo
        mkdir -p backend/src/main/resources
        python3 - <<'PY'
import secrets, string
ak = 'AKIA' + ''.join(secrets.choice('ABCDEFGHIJKLMNOPQRSTUVWXYZ234567') for _ in range(16))
sk = ''.join(secrets.choice(string.ascii_letters + string.digits + '+/') for _ in range(40))
open('backend/src/main/resources/application-dev.properties','w').write(
  f"# cấu hình kết nối kho tệp (kịch bản kiểm thử: bí mật giả)\nstorage.access-key-id={ak}\nstorage.secret-access-key={sk}\n")
PY
        ;;
      cve)
        python3 - <<'PY'
p='backend/pom.xml'
s=open(p).read()
s=s.replace('''  <dependencies>
''','''  <dependencies>
    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-core</artifactId>
      <version>2.14.1</version>
    </dependency>
''',1)
open(p,'w').write(s)
PY
        ;;
      hop-dong)
        python3 - <<'PY'
import re
p='contracts/openapi/modules/jobs.yaml'
s=open(p).read()
# Xoá thao tác DELETE /jobs/{id} (cancelJob): từ dòng "    delete:" tới hết khối thao tác
m=re.search(r'\n    delete:\n.*?(?=\n  /|\ncomponents:|\Z)', s, re.S)
assert m, 'không tìm thấy thao tác delete'
s=s[:m.start()]+s[m.end():]
open(p,'w').write(s)
PY
        ;;
    esac
    gitq add -A
    gitq commit -q -m "MR kiểm thử: ${ten}" )
}

# ─────────────────────────── Tầng 1: chạy trực tiếp các cổng ───────────────────────────

do_cong() {  # <kịch bản> <tên cổng> <lệnh...>
  local kb="$1" cong="$2"; shift 2
  local bd kt ma
  bd=$(date +%s%N)
  set +e
  ( cd "${W}/kb/${kb}" && "$@" ) > "${KQ}/cong-${kb}-${cong}.log" 2>&1
  ma=$?
  set -e
  kt=$(date +%s%N)
  printf '%s\t%s\t%s\t%d.%d\n' "${kb}" "${cong}" "${ma}" $(( (kt - bd) / 1000000000 )) $(( (kt - bd) / 100000000 % 10 )) >> "${KQ}/cong.tsv"
}

chay_cong() {
  log "Tầng 1: chạy trực tiếp từng cổng trên từng kịch bản"
  : > "${KQ}/cong.tsv"
  cp "${GOC_REPO}/ci/vt-pipeline/resources/vn/vt/pipeline/openapi-diff.sh" "${W}/bin/openapi-diff.sh"
  for kb in sach "${KICH_BAN_LOI[@]}"; do
    tao_kich_ban "${kb}"
    echo "-- ${kb}"
    do_cong "${kb}" gitleaks gitleaks git --redact --exit-code 1 --config .gitleaks.toml --log-opts=origin/main..HEAD .
    do_cong "${kb}" semgrep semgrep scan --config .semgrep/vt-rules.yml --error --metrics=off --disable-version-check --sarif -o semgrep.sarif backend
    do_cong "${kb}" trivy-fs trivy fs --scanners vuln,misconfig,license --severity HIGH,CRITICAL --exit-code 1 backend
    do_cong "${kb}" mvn-verify bash -c 'cd backend && ./mvnw -B -ntp verify -Pci'
    do_cong "${kb}" openapi-diff env OPENAPI_DIFF="${W}/bin/openapi-diff" bash "${W}/bin/openapi-diff.sh" origin/main contracts/openapi openapi-diff
  done
  local ma_bang=0
  python3 - "${KQ}/cong.tsv" > "${KQ}/cong.md" <<'PY' || ma_bang=1
import sys, collections
rows=[l.rstrip('\n').split('\t') for l in open(sys.argv[1]) if l.strip()]
mong_doi={'sach':None,'test-loi':'mvn-verify','bi-mat':'gitleaks','cve':'trivy-fs','hop-dong':'openapi-diff'}
cong=['gitleaks','semgrep','trivy-fs','mvn-verify','openapi-diff']
bang=collections.defaultdict(dict)
for kb,c,ma,t in rows: bang[kb][c]=(int(ma),float(t))
print('| Kịch bản | ' + ' | '.join(cong) + ' | Kết luận |')
print('|---|' + '---|'*len(cong) + '---|')
dat=0
for kb in mong_doi:
    o=[]
    chan=[c for c in cong if bang[kb][c][0]!=0]
    for c in cong:
        ma,t=bang[kb][c]
        o.append(('CHẶN' if ma else 'qua')+f' ({t:.1f} s)')
    ok = (chan==[] if mong_doi[kb] is None else chan==[mong_doi[kb]])
    dat+=ok
    kl=('ĐÚNG' if ok else 'SAI')+(': qua hết' if not chan else ': chặn ở '+', '.join(chan))
    print(f'| {kb} | ' + ' | '.join(o) + f' | {kl} |')
print(f'\nKết quả tầng 1: {dat}/{len(mong_doi)} kịch bản đúng kỳ vọng')
sys.exit(0 if dat==len(mong_doi) else 1)
PY
  cat "${KQ}/cong.md"
  return ${ma_bang}
}

# ─────────────────────────── Tầng 2: Jenkins thật ───────────────────────────

chuan_bi_jenkins() {
  log "Chuẩn bị Jenkinsfile Runner, registry cục bộ, khoá tạm"
  local jfr="${VT_JFR_CACHE:-${W}/jfr}"
  if [ ! -x "${jfr}/app/bin/jenkinsfile-runner" ]; then
    rm -rf "${jfr}"; mkdir -p "${jfr}"
    local cid; cid=$(docker create "${JFR_IMAGE}")
    docker cp -q "${cid}:/app" "${jfr}/app"
    docker cp -q "${cid}:/opt/java/openjdk" "${jfr}/jdk"
    docker cp -q "${cid}:/usr/share/jenkins/ref/plugins" "${jfr}/plugins"
    docker rm "${cid}" >/dev/null
  fi
  JFR="${jfr}"

  # Bản sao thư viện để kiểm thử: chỉ thay khối agent kubernetes bằng agent any (không có plugin kubernetes)
  rm -rf "${W}/lib" "${W}/stub"
  mkdir -p "${W}/lib/ci"
  cp -r "${GOC_REPO}/ci/vt-pipeline" "${W}/lib/ci/vt-pipeline"
  rm -rf "${W}/lib/ci/vt-pipeline/tests"
  python3 - "${W}/lib/ci/vt-pipeline/vars/vtJavaPipeline.groovy" <<'PY'
import re, sys
p=sys.argv[1]; s=open(p).read()
s2,n=re.subn(r"agent \{\n\s+kubernetes \{.*?\n\s+\}\n\s+\}", "agent any   // KIỂM THỬ CỤC BỘ: thay agent kubernetes", s, count=1, flags=re.S)
assert n==1, 'không tìm thấy khối agent kubernetes'
open(p,'w').write(s2)
PY
  ( cd "${W}/lib" && git init -q -b main && gitq add -A && gitq commit -q -m "vt-pipeline (bản kiểm thử)" \
      && git tag vt-pipeline-v1.0.0 )
  cp -r "${TESTS}/jenkins/stub-lib" "${W}/stub"
  ( cd "${W}/stub" && git init -q -b main && gitq add -A && gitq commit -q -m "stub kiểm thử" )

  # Registry cục bộ
  docker rm -f "${REG_NAME}" >/dev/null 2>&1 || true
  docker run -d --rm --name "${REG_NAME}" -p "127.0.0.1:${REG_PORT}:5000" registry:2 >/dev/null
  for _ in $(seq 1 30); do curl -sf "http://127.0.0.1:${REG_PORT}/v2/" >/dev/null && break; sleep 1; done

  # Khoá cosign tạm (trong pipeline thật: hashivault://cosign-release)
  mkdir -p "${W}/khoa"; chmod 700 "${W}/khoa"
  COSIGN_PASSWORD="$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')"
  export COSIGN_PASSWORD
  ( cd "${W}/khoa" && cosign generate-key-pair --output-key-prefix cosign >/dev/null 2>&1 )

  # Khoá GPG tạm cho commit GitOps
  # Đường dẫn socket của gpg-agent giới hạn ~100 ký tự nên GNUPGHOME đặt ở thư mục tạm ngắn
  VT_GNUPG="$(mktemp -d /tmp/vtgpg.XXXXXX)"
  export GNUPGHOME="${VT_GNUPG}"
  gpg --batch --quiet --passphrase '' --quick-gen-key 'vt-pipeline kiem thu <vt-pipeline@jenkins.vt.local>' ed25519 sign never
  gpg --batch --quiet --armor --export-secret-keys > "${W}/khoa/gitops.asc"
  gpg --batch --quiet --armor --export > "${W}/khoa/gitops.pub.asc"
  GPG_FPR=$(gpg --batch --list-secret-keys --with-colons | awk -F: '/^fpr/ {print $10; exit}')
  unset GNUPGHOME

  # Lệnh phụ: vt-provenance từ tài nguyên của thư viện (ở cụm thật nằm trong container provenance)
  cp "${GOC_REPO}/ci/vt-pipeline/resources/vn/vt/pipeline/vt-provenance.py" "${W}/bin/vt-provenance"
  chmod +x "${W}/bin/vt-provenance"

  # Cấu hình Jenkins bằng Configuration as Code: thư viện toàn cục và credential giả
  local token_gia; token_gia="$(python3 -c 'import secrets;print("kiem-thu-"+secrets.token_hex(8))')"
  cat > "${W}/casc.yaml" <<EOF
jenkins:
  numExecutors: 4
unclassified:
  globalLibraries:
    libraries:
      - name: vt-pipeline
        defaultVersion: vt-pipeline-v1.0.0
        allowVersionOverride: true
        implicit: false
        retriever:
          modernSCM:
            libraryPath: ci/vt-pipeline/
            scm:
              git:
                remote: "file://${W}/lib"
      - name: vt-test-stub
        defaultVersion: main
        implicit: true
        retriever:
          modernSCM:
            scm:
              git:
                remote: "file://${W}/stub"
credentials:
  system:
    domainCredentials:
      - credentials:
          - string:
              scope: GLOBAL
              id: openbao-cosign-token
              description: "Token giả cho kiểm thử (khoá cục bộ không dùng OpenBao)"
              secret: "${token_gia}"
          - file:
              scope: GLOBAL
              id: gitops-signing-gpg
              fileName: gitops.asc
              secretBytes: "\${readFileBase64:${W}/khoa/gitops.asc}"
EOF
}

# Chạy backend/Jenkinsfile của một bản sao bằng Jenkinsfile Runner
chay_jenkins() {  # <tên lần chạy> <thư mục làm việc> <BRANCH_NAME> [CHANGE_TARGET]
  local ten="$1" ws="$2" nhanh="$3" dich="${4:-}"
  local h="${W}/jhome/${ten}"
  rm -rf "${h}"; mkdir -p "${h}"
  local args=(-a "BRANCH_NAME=${nhanh}" -a "GIT_URL=file://${W}/origin.git"
    -a "VT_REGISTRY=localhost:${REG_PORT}/okr" -a "VT_REGISTRY_CREDENTIALS_ID=-"
    -a "VT_SEMGREP_CONFIGS=.semgrep/vt-rules.yml" -a "VT_SONAR=false" -a "VT_DEPENDENCY_TRACK=false"
    -a "VT_COSIGN_KEY=${W}/khoa/cosign.key" -a "VT_COSIGN_VERIFY_KEY=${W}/khoa/cosign.pub"
    -a "VT_OPENAPI_DIFF_CMD=${W}/bin/openapi-diff" -a "VT_VAULT_ADDR=http://127.0.0.1:1"
    -a "VT_GITOPS_REPO=file://${W}/origin.git" -a "VT_GITOPS_CREDENTIALS_ID=-"
    -a "VT_BUILDER_ID=https://jenkins.vt.local/okr-builder")
  if [ -n "${dich}" ]; then args+=(-a "CHANGE_TARGET=${dich}" -a "CHANGE_ID=${ten}"); fi
  local bd kt ma
  bd=$(date +%s)
  set +e
  CASC_JENKINS_CONFIG="${W}/casc.yaml" \
  JAVACMD="${JFR}/jdk/bin/java" \
  JAVA_OPTS="-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true -Djenkins.install.runSetupWizard=false -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Xmx1g" LC_ALL=C.UTF-8 LANG=C.UTF-8 \
  MAVEN_ARGS="-Djib.allowInsecureRegistries=true -Djib.console=plain" \
  TRIVY_SKIP_DB_UPDATE=true TRIVY_SKIP_JAVA_DB_UPDATE=true TRIVY_INSECURE=true TRIVY_NO_PROGRESS=true \
  SYFT_REGISTRY_INSECURE_USE_HTTP=true SYFT_CHECK_FOR_APP_UPDATE=false \
    "${JFR}/app/bin/jenkinsfile-runner" -w "${JFR}/app/jenkins" -p "${JFR}/plugins" \
      -f "${ws}/backend/Jenkinsfile" --runWorkspace "${ws}" --runHome "${h}" -n "inf05-${ten}" -u "${args[@]}" \
      > "${KQ}/jenkins-${ten}.log" 2>&1
  ma=$?
  set -e
  kt=$(date +%s)
  python3 "${TESTS}/jenkins/thoi-gian-stage.py" "${h}" > "${KQ}/jenkins-${ten}-stage.tsv" 2>/dev/null || true
  printf '%s\t%s\t%s\n' "${ten}" "${ma}" "$((kt - bd))" >> "${KQ}/jenkins.tsv"
  echo "   ${ten}: mã thoát ${ma}, $((kt - bd)) s"
  return 0
}

# Stage nào thất bại (FAILURE) theo log và theo dữ liệu flow node
stage_loi() {  # <tên lần chạy>
  awk -F'\t' '$2=="FAILURE"{print $1}' "${KQ}/jenkins-$1-stage.tsv" 2>/dev/null | paste -sd, -
}

kiem_jenkins() {
  log "Tầng 2: chạy backend/Jenkinsfile và thư viện trên Jenkins (Jenkinsfile Runner)"
  : > "${KQ}/jenkins.tsv"
  chuan_bi_jenkins

  # Kiểm cú pháp declarative bằng linter của Jenkins (jenkinsfile-runner lint) trên khối pipeline của
  # vtJavaPipeline: (1) bản kiểm thử (agent any) phải đạt; (2) bản gốc (agent kubernetes) chỉ được báo
  # đúng một lỗi là thiếu plugin kubernetes; (3) bản cố ý sai phải bị linter bắt, chứng tỏ linter có kiểm.
  log "Declarative linter (jenkinsfile-runner lint)"
  mkdir -p "${W}/lint"
  python3 - "${W}/lib/ci/vt-pipeline/vars/vtJavaPipeline.groovy" "${GOC_REPO}/ci/vt-pipeline/vars/vtJavaPipeline.groovy" "${W}/lint" <<'PY'
import sys
cfg=('def cfg = [service: "lint", dir: "backend", registry: "r", cosignKey: "k", cosignVerifyKey: "", pact: false, '
     'sonar: true, coverageThreshold: 80.0, notifyChannel: "c", gitopsEnv: "staging", gitopsRepo: "", '
     'gitopsBranch: "main", gitopsCredentialsId: "", gitopsSigningKeyCredentialsId: ""]\n')
def khoi(p):
    s=open(p).read()
    i=s.index('  pipeline {')
    out=[]
    for l in s[i:].split('\n'):
        out.append(l[2:] if l.startswith('  ') else l)
        if l=='  }': break
    return cfg+'\n'.join(out)+'\n'
kt=khoi(sys.argv[1]); goc=khoi(sys.argv[2])
open(sys.argv[3]+'/kiem-thu.groovy','w').write(kt)
open(sys.argv[3]+'/goc.groovy','w').write(goc)
sai=kt.replace('disableConcurrentBuilds(abortPrevious: true)','disableConcurrentBuild(abortPrevious: true)')
assert sai!=kt
open(sys.argv[3]+'/co-y-sai.groovy','w').write(sai)
PY
  : > "${KQ}/lint.txt"
  for f in kiem-thu goc co-y-sai; do
    set +e
    JAVACMD="${JFR}/jdk/bin/java" JAVA_OPTS="-Dfile.encoding=UTF-8" \
      "${JFR}/app/bin/jenkinsfile-runner" lint -w "${JFR}/app/jenkins" -p "${JFR}/plugins" \
      -f "${W}/lint/${f}.groovy" > "${KQ}/lint-${f}.log" 2>&1
    local ml=$?
    set -e
    echo "lint ${f}: mã ${ml}; $(grep -E '^WorkflowScript|^Done' "${KQ}/lint-${f}.log" | head -2 | tr '\n' ' ')" | tee -a "${KQ}/lint.txt"
  done
  LINT_DAT=1
  grep -q '^lint kiem-thu: mã 0' "${KQ}/lint.txt" || LINT_DAT=0
  { grep '^lint goc:' "${KQ}/lint.txt" | grep -q 'Invalid agent type "kubernetes"'; } || LINT_DAT=0
  [ "$(grep -c '^WorkflowScript' "${KQ}/lint-goc.log")" = 1 ] || LINT_DAT=0
  { grep '^lint co-y-sai:' "${KQ}/lint.txt" | grep -q 'Invalid option type "disableConcurrentBuild"'; } || LINT_DAT=0
  echo "   linter: $([ ${LINT_DAT} = 1 ] && echo 'ĐẠT' || echo 'KHÔNG ĐẠT')"

  log "Năm kịch bản merge request"
  for kb in sach "${KICH_BAN_LOI[@]}"; do
    tao_kich_ban "${kb}"
    chay_jenkins "mr-${kb}" "${W}/kb/${kb}" "PR-${kb}" main
  done

  log "Bản dựng nhánh main sau khi gộp MR sạch (ký, chứng thực, cập nhật GitOps)"
  ( cd "${W}/kb/sach" && git push -q origin "mr/sach:main" )
  rm -rf "${W}/main"; git clone -q "${W}/origin.git" "${W}/main"
  chay_jenkins "main" "${W}/main" main

  # Kiểm độc lập kết quả của bản dựng main
  local dg="" anh="localhost:${REG_PORT}/okr/mission-core"
  dg=$(sed -n 's/^.*Ảnh đã dựng: .*@\(sha256:[0-9a-f]\{64\}\).*$/\1/p' "${KQ}/jenkins-main.log" | tail -1)
  : > "${KQ}/kiem-doc-lap.txt"
  if [ -n "${dg}" ]; then
    {
      echo "Ảnh: ${anh}@${dg}"
      cosign verify --key "${W}/khoa/cosign.pub" --insecure-ignore-tlog=true "${anh}@${dg}" 2>&1 | tail -3
      echo "cosign verify: mã ${PIPESTATUS[0]}"
      cosign verify-attestation --key "${W}/khoa/cosign.pub" --insecure-ignore-tlog=true --type slsaprovenance1 \
        "${anh}@${dg}" 2>/dev/null | jq -r '.payload' | base64 -d | jq -c '{predicateType, subject, builder: .predicate.runDetails.builder}'
      echo "cosign verify-attestation slsaprovenance1: mã ${PIPESTATUS[0]}"
      cosign verify-attestation --key "${W}/khoa/cosign.pub" --insecure-ignore-tlog=true --type cyclonedx \
        "${anh}@${dg}" >/dev/null 2>&1; echo "cosign verify-attestation cyclonedx: mã $?"
      rm -rf "${W}/gitops-kiem"; git clone -q "${W}/origin.git" "${W}/gitops-kiem"
      echo "digests.yaml sau bump:"; grep -A4 'mission-core:' "${W}/gitops-kiem/deploy/environments/staging/digests.yaml"
      export GNUPGHOME="${VT_GNUPG}"
      ( cd "${W}/gitops-kiem" && git log -1 --format='commit %H | %s | chữ ký %G? khoá %GF' && git verify-commit HEAD 2>&1 | tail -1 )
      echo "Vân tay khoá GPG kỳ vọng: ${GPG_FPR}"
      unset GNUPGHOME
    } >> "${KQ}/kiem-doc-lap.txt" 2>&1
  else
    echo "Không tìm thấy digest trong log bản dựng main" >> "${KQ}/kiem-doc-lap.txt"
  fi
  cat "${KQ}/kiem-doc-lap.txt"

  # Tổng hợp
  local ma_tong=0
  python3 - "${KQ}" > "${KQ}/jenkins.md" <<'PY' || ma_tong=1
import sys, os, re
kq=sys.argv[1]
rows=[l.rstrip('\n').split('\t') for l in open(os.path.join(kq,'jenkins.tsv')) if l.strip()]
mong_doi={'mr-sach':(0,None),'mr-test-loi':(1,'Dựng và kiểm thử'),'mr-bi-mat':(1,'gitleaks'),
          'mr-cve':(1,'Trivy fs'),'mr-hop-dong':(1,'openapi-diff'),'main':(0,None)}
dau={'mr-test-loi':r'Tests run: .*Failures: [1-9]','mr-bi-mat':r'leaks found: [1-9]',
     'mr-cve':r'CVE-2021-44228','mr-hop-dong':r'PHÁ VỠ TƯƠNG THÍCH'}
print('| Lần chạy | Kết quả Jenkins | Stage thất bại | Dấu hiệu trong log | Thời gian | Kết luận |')
print('|---|---|---|---|---|---|')
dat=0; chan=0
for ten,ma,t in rows:
    ma=int(ma)
    log=open(os.path.join(kq,f'jenkins-{ten}.log'),errors='replace').read()
    m=re.findall(r'Finished: (\w+)',log)
    kqj=m[-1] if m else ('SUCCESS' if ma==0 else 'FAILURE')
    st=[]
    p=os.path.join(kq,f'jenkins-{ten}-stage.tsv')
    if os.path.exists(p):
        st=[l.split('\t')[0] for l in open(p) if l.split('\t')[1:2]==['FAILURE']]
    kv_ma,kv_st=mong_doi.get(ten,(None,None))
    dh=dau.get(ten)
    co_dau = bool(re.search(dh,log)) if dh else None
    ok = (ma==0)==(kv_ma==0) and (kv_st is None or kv_st in st) and (co_dau in (None,True))
    dat+=ok
    if ten.startswith('mr-') and ten!='mr-sach' and ok: chan+=1
    print(f"| {ten} | {kqj} (mã {ma}) | {', '.join(st) or '-'} | {('có' if co_dau else 'KHÔNG') if dh else '-'} | {t} s | {'ĐÚNG' if ok else 'SAI'} |")
print(f'\nMR cố ý sai bị chặn: {chan}/4; lần chạy đúng kỳ vọng: {dat}/{len(rows)}')
sys.exit(0 if dat==len(rows) else 1)
PY
  cat "${KQ}/jenkins.md"
  log "Thời gian từng stage (bản dựng main và MR sạch)"
  for ten in mr-sach mr-test-loi mr-bi-mat mr-cve mr-hop-dong main; do
    echo "-- ${ten}"
    awk -F'\t' '{printf "   %-28s %-24s %s s\n", $1, $2, $3}' "${KQ}/jenkins-${ten}-stage.tsv" 2>/dev/null || true
  done
  [ "${LINT_DAT}" = 1 ] || ma_tong=1
  return ${ma_tong}
}

# ─────────────────────────── Chạy ───────────────────────────

mkdir -p "${W}/bin"
export PATH="${W}/bin:${PATH}"
# openapi-diff chạy bằng ảnh Docker openapitools/openapi-diff, gắn thư mục hiện tại vào cùng đường dẫn
cat > "${W}/bin/openapi-diff" <<EOF
#!/usr/bin/env bash
exec docker run --rm -u "\$(id -u):\$(id -g)" -v "\$PWD:\$PWD" -w "\$PWD" ${OAD_IMAGE} "\$@"
EOF
chmod +x "${W}/bin/openapi-diff"
chuan_bi_repo
MA=0
case "${CHE_DO}" in
  cong) chay_cong || MA=1 ;;
  jenkins) kiem_jenkins || MA=1 ;;
  tat-ca)
    chay_cong || MA=1
    kiem_jenkins || MA=1 ;;
  *) echo "Chế độ không hợp lệ: ${CHE_DO} (cong|jenkins|tat-ca)" >&2; exit 2 ;;
esac
if [ -n "${VT_KET_QUA_RA:-}" ]; then mkdir -p "${VT_KET_QUA_RA}"; cp -r "${KQ}/." "${VT_KET_QUA_RA}/"; fi
log "Kết thúc: $([ ${MA} -eq 0 ] && echo 'ĐẠT' || echo 'KHÔNG ĐẠT')"
exit ${MA}
