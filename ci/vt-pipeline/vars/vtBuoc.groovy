// vtBuoc: các bước dùng chung của pipeline INF-06 (Go, Python, Helm, Policy Pack, UI Pack).
// Cùng cổng và cùng lệnh với vtJavaPipeline (INF-05): gitleaks (SEC-09), Semgrep chặn merge (SEC-39), Trivy và
// SBOM CycloneDX (SEC-23), ảnh theo digest không root (SEC-49, SEC-55), ký cosign và provenance SLSA v1 trong
// container riêng (SEC-37, SEC-38). vtJavaPipeline giữ bản riêng của các bước này để không đổi pipeline đã kiểm.
// Gọi trong pipeline: vtBuoc.quetBiMat(cfg), vtBuoc.dungAnh(cfg)...

import com.cloudbees.groovy.cps.NonCPS
import vn.vt.pipeline.CauHinh

/** SEC-09: gitleaks trên dải commit của merge request (ngoài MR: commit mới nhất). */
def quetBiMat(Map cfg) {
  String dai = CauHinh.daiCommitGitleaks(env.getEnvironment())
  if (env.CHANGE_TARGET) {
    canMoc("origin/${env.CHANGE_TARGET}")
  }
  sh "gitleaks git --redact --exit-code 1 --config ${q(cfg.gitleaksConfig)} --log-opts=${q(dai)} " +
     "--report-format sarif --report-path gitleaks.sarif ."
}

/** SEC-39: Semgrep chặn merge với bộ quy tắc của ngôn ngữ cộng quy tắc riêng trong .semgrep/. */
def quetSemgrep(Map cfg, String thuMuc = null) {
  String configs = cfg.semgrepConfigs.collect { "--config ${q(it)}" }.join(' ')
  sh "semgrep scan ${configs} --error --metrics=off --disable-version-check --sarif -o semgrep.sarif ${q(thuMuc ?: cfg.dir)}"
}

/** SEC-23: SCA, cấu hình sai, giấy phép trên mã nguồn; HIGH, CRITICAL thì chặn. */
def quetTrivyFs(Map cfg, String scanners = 'vuln,misconfig,license') {
  // trivySkipDirs: thư mục ca kiểm thử cố ý vi phạm (ví dụ tài nguyên mẫu của kyverno test), không phải cấu hình triển khai.
  String boQua = (cfg.trivySkipDirs ?: []).collect { "--skip-dirs ${q(it)}" }.join(' ')
  // trivyIgnoreFile: ngoại lệ có lý do và hạn (định dạng .trivyignore.yaml), cùng quy trình với ngoại lệ Kyverno.
  if (cfg.trivyIgnoreFile) {
    boQua += " --ignorefile ${q(cfg.trivyIgnoreFile)}"
  }
  sh "trivy fs --scanners ${scanners} --severity HIGH,CRITICAL --exit-code 1 ${boQua} ${q(cfg.dir)}"
}

/** SEC-55, SEC-49: mọi FROM ghim @sha256, USER cuối cùng là UID số ≥ minUid (khớp Kyverno vt-pod-hardening). */
def kiemDockerfile(Map cfg) {
  String tep = "${cfg.dir}/${cfg.dockerfile}"
  String noiDung = readFile(tep)
  List loi = loiDockerfile(noiDung, cfg.minUid as int)
  if (loi) {
    error("Dockerfile ${tep} không đạt: ${loi.join('; ')}")
  }
  echo "Dockerfile ${tep}: ảnh nền ghim digest, chạy UID không root"
}

@NonCPS
List loiDockerfile(String noiDung, int minUid) {
  List loi = []
  List bietDanh = []
  String user = null
  noiDung.readLines().each { dong ->
    String d = dong.trim()
    def m = d =~ /(?i)^FROM\s+(?:--platform=\S+\s+)?(\S+)(?:\s+AS\s+(\S+))?/
    if (m) {
      String anh = m[0][1]
      if (!(anh == 'scratch' || bietDanh.contains(anh) || anh ==~ /.+@sha256:[0-9a-f]{64}/)) {
        loi << "FROM ${anh} chưa ghim digest"
      }
      if (m[0][2]) { bietDanh << m[0][2] }
      user = null          // USER chỉ tính trong stage cuối
    }
    def u = d =~ /(?i)^USER\s+(\S+)/
    if (u) { user = u[0][1] }
  }
  String uid = user?.split(':')?.getAt(0)
  if (!(uid ==~ /\d+/) || (uid as int) < minUid) {
    loi << "stage cuối phải có USER là UID số ≥ ${minUid} (hiện: ${user ?: 'không có'})"
  }
  return loi
}

/** Dựng ảnh từ Dockerfile: BuildKit không root trên cụm (TL-04 mục 9.6), docker buildx trên máy kiểm thử. */
def dungAnh(Map cfg) {
  dangNhapRegistry(cfg)
  String anh = "${cfg.registry}/${cfg.service}"
  String sha = commitHienTai()
  String ctx = cfg.dir
  if (cfg.imageBuilder == 'docker') {
    sh "docker buildx build --push --provenance=false --metadata-file .vt-anh.json -f ${q(ctx + '/' + cfg.dockerfile)} " +
       "-t ${q(anh + ':' + sha)} ${q(ctx)}"
  } else {
    sh "buildctl-daemonless.sh build --frontend dockerfile.v0 --local context=${q(ctx)} --local dockerfile=${q(ctx)} " +
       "--opt filename=${q(cfg.dockerfile)} --output type=image,name=${q(anh + ':' + sha)},push=true --metadata-file .vt-anh.json"
  }
  String digest = sh(script: "jq -r '.\"containerimage.digest\"' .vt-anh.json", returnStdout: true).trim()
  if (!(digest ==~ /sha256:[0-9a-f]{64}/)) {
    error("Bộ dựng ảnh không trả digest hợp lệ: ${digest}")
  }
  env.DIGEST = digest
  env.IMAGE_REF = "${anh}@${digest}"
  echo "Ảnh đã dựng: ${env.IMAGE_REF}"
}

/** SEC-23: SBOM CycloneDX bằng syft, Trivy ảnh chặn HIGH/CRITICAL đã có bản vá, đẩy SBOM lên Dependency-Track. */
def sbomVaQuetAnh(Map cfg) {
  sh 'syft scan "registry:$IMAGE_REF" -o cyclonedx-json=sbom.cdx.json'
  sh 'trivy image --image-src remote --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 "$IMAGE_REF"'
  dayDependencyTrack(cfg)
}

def dayDependencyTrack(Map cfg) {
  if (!cfg.dependencyTrack) {
    return
  }
  withCredentials([string(credentialsId: cfg.dependencyTrackCredentialsId, variable: 'DTRACK_KEY')]) {
    withEnv(["VT_DTRACK_URL=${cfg.dependencyTrackUrl}", "VT_COMMIT=${commitHienTai()}", "VT_SVC=${cfg.service}"]) {
      sh '''set +x
        hdr="$(mktemp)"; trap 'rm -f "$hdr"' EXIT
        printf 'X-Api-Key: %s\\n' "$DTRACK_KEY" > "$hdr"
        curl -sf -X POST -H @"$hdr" -F "autoCreate=true" -F "projectName=$VT_SVC" \
          -F "projectVersion=$VT_COMMIT" -F "bom=@sbom.cdx.json" "$VT_DTRACK_URL/api/v1/bom"'''
    }
  }
}

/** SEC-37, SEC-38: ký ảnh, chứng thực SBOM và provenance SLSA v1 trong container riêng, rồi tự kiểm lại. */
def kyVaChungThucAnh(Map cfg) {
  trongContainerKy(cfg) {
    withEnv(["VT_COMMIT=${commitHienTai()}", "VT_BUILDER_ID=${cfg.builderId}", "VT_REPO=${env.GIT_URL ?: ''}"]) {
      // --new-bundle-format=false: Kyverno 1.15 chỉ đọc chữ ký dạng thẻ .sig/.att (INF-07)
      String co = '--yes --key "$COSIGN_KEY" --tlog-upload=false --use-signing-config=false --new-bundle-format=false'
      sh "cosign sign ${co} \"\$IMAGE_REF\""
      sh "cosign attest ${co} --type cyclonedx --predicate sbom.cdx.json \"\$IMAGE_REF\""
      sh '''vt-provenance --builder-id "$VT_BUILDER_ID" --repo "$VT_REPO" --commit "$VT_COMMIT" \
              --digest "$DIGEST" --ref "refs/heads/$BRANCH_NAME" --build-url "${BUILD_URL:-}" > provenance.json'''
      sh "cosign attest ${co} --type slsaprovenance1 --predicate provenance.json \"\$IMAGE_REF\""
      sh 'cosign verify --key "$COSIGN_VERIFY_KEY" --insecure-ignore-tlog=true "$IMAGE_REF" > cosign-verify.json'
      sh 'cosign verify-attestation --key "$COSIGN_VERIFY_KEY" --insecure-ignore-tlog=true ' +
         '--type slsaprovenance1 "$IMAGE_REF" > cosign-verify-provenance.json'
    }
  }
}

/** Ký sản phẩm không phải ảnh (Policy Pack, UI Pack, chart đóng gói) bằng cosign sign-blob. */
def kyTep(Map cfg, List tep) {
  trongContainerKy(cfg) {
    tep.each { t ->
      sh "cosign sign-blob --yes --key \"\$COSIGN_KEY\" --tlog-upload=false --use-signing-config=false " +
         "--bundle ${q(t + '.cosign.bundle')} ${q(t)} >/dev/null"
      sh "cosign verify-blob --key \"\$COSIGN_VERIFY_KEY\" --insecure-ignore-tlog=true --bundle ${q(t + '.cosign.bundle')} ${q(t)}"
    }
  }
}

def trongContainerKy(Map cfg, Closure buoc) {
  container(cfg.provenanceContainer) {
    List khoa = cfg.cosignVaultTokenCredentialsId ?
        [string(credentialsId: cfg.cosignVaultTokenCredentialsId, variable: 'VAULT_TOKEN')] : []
    withCredentials(khoa) {
      withEnv(["VAULT_ADDR=${cfg.vaultAddr}", "COSIGN_KEY=${cfg.cosignKey}",
               "COSIGN_VERIFY_KEY=${cfg.cosignVerifyKey ?: cfg.cosignKey}"]) {
        buoc()
      }
    }
  }
}

/** Cập nhật digest vào kho GitOps bằng commit có chữ ký (dùng lại vtGitopsBump của INF-05). */
def capNhatGitops(Map cfg) {
  vtGitopsBump(service: cfg.service, digest: env.DIGEST, env: cfg.gitopsEnv,
               image: "${cfg.registry}/${cfg.service}", commit: commitHienTai(),
               repoUrl: cfg.gitopsRepo ?: env.GIT_URL, branch: cfg.gitopsBranch,
               credentialsId: cfg.gitopsCredentialsId, signingKeyCredentialsId: cfg.gitopsSigningKeyCredentialsId)
}

/** Gọi API Mission qua vt-api.py (Idempotency-Key, If-Match, RFC 9457, chờ job 202). */
def goiApi(Map cfg, String thamSo) {
  napTaiNguyen('vt-api.py')
  withCredentials([string(credentialsId: cfg.apiCredentialsId, variable: 'VT_API_TOKEN')]) {
    withEnv(["VT_API_BASE=${cfg.apiBase}", "VT_API_CA=${cfg.apiCa ?: ''}",
             "VT_IDEMPOTENCY_KEY=${env.JOB_NAME ?: 'job'}-${env.BUILD_NUMBER ?: '0'}-${thamSo.hashCode()}"]) {
      sh "python3 .vt-pipeline/vt-api.py ${thamSo}"
    }
  }
}

/** Chép một tệp trong resources/vn/vt/pipeline của thư viện vào .vt-pipeline/ của workspace. */
def napTaiNguyen(String ten) {
  writeFile file: ".vt-pipeline/${ten}", text: libraryResource("vn/vt/pipeline/${ten}")
  return ".vt-pipeline/${ten}"
}

/** Cổng ghi nhận kết quả (độ phủ, JUnit) đặt FAILURE mà không dừng; chặn các stage sau. */
def chanNeuDaLoi() {
  if (currentBuild.currentResult != 'SUCCESS') {
    error("Dừng: cổng chất lượng trước đó không đạt (kết quả hiện tại ${currentBuild.currentResult}).")
  }
}

def commitHienTai() {
  if (!env.VT_GIT_SHA) {
    env.VT_GIT_SHA = env.GIT_COMMIT ?: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
  }
  return env.VT_GIT_SHA
}

def canMoc(String moc) {
  int ma = sh(script: "git rev-parse --verify --quiet ${q(moc + '^{commit}')} >/dev/null", returnStatus: true)
  if (ma != 0) {
    error("Bản sao không có ${moc}. Thêm refspec +refs/heads/<nhánh đích>:refs/remotes/origin/<nhánh đích> " +
          'và tắt shallow clone trong cấu hình nguồn của job (ci/vt-pipeline/README.md).')
  }
}

def dangNhapRegistry(Map cfg) {
  sh 'mkdir -p "$DOCKER_CONFIG" && chmod 700 "$DOCKER_CONFIG"'
  if (!cfg.registryCredentialsId) {
    return
  }
  withCredentials([usernamePassword(credentialsId: cfg.registryCredentialsId,
                                    usernameVariable: 'VT_REG_USER', passwordVariable: 'VT_REG_PASS')]) {
    withEnv(["VT_REG_HOST=${cfg.registry.tokenize('/')[0]}"]) {
      sh '''set +x
        umask 077
        auth="$(printf '%s:%s' "$VT_REG_USER" "$VT_REG_PASS" | base64 | tr -d '\\n')"
        printf '{"auths":{"%s":{"auth":"%s"}}}\\n' "$VT_REG_HOST" "$auth" > "$DOCKER_CONFIG/config.json"'''
    }
  }
}

/** Kiểm độ phủ dòng tổng ≥ ngưỡng (J1, J3) từ con số do công cụ của ngôn ngữ in ra. */
def kiemDoPhu(Map cfg, String moTa, String lenhLayPhanTram) {
  String raw = sh(script: lenhLayPhanTram, returnStdout: true).trim()
  BigDecimal phu = raw.replace('%', '') as BigDecimal
  echo "Độ phủ ${moTa}: ${phu}% (ngưỡng ${cfg.coverageThreshold}%)"
  if (phu < (cfg.coverageThreshold as BigDecimal)) {
    error("Độ phủ ${moTa} ${phu}% thấp hơn ngưỡng ${cfg.coverageThreshold}% (J1, J3)")
  }
}

/** Trích dẫn an toàn cho shell (dấu nháy đơn). */
@NonCPS
String q(Object s) {
  return "'" + s.toString().replace("'", "'\"'\"'") + "'"
}
