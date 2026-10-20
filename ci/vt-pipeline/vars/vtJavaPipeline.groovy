// vtJavaPipeline: pipeline dùng chung cho service Java (Maven, Spring Boot) của Mission (INF-05).
//
// Truy vết: ADR-16 → TL-01 ch.18 "Đường ống CI/CD" → TL-04 mục 9.6 "Chuỗi cung ứng phần mềm: SLSA,
// Jenkins, Argo CD" (Jenkinsfile mẫu) → SEC-09 (gitleaks), SEC-39 (Semgrep chặn merge), SEC-23 (SCA, SBOM
// CycloneDX, Dependency-Track), SEC-37 (SLSA Build mức 3), SEC-38 (cosign ký và chứng thực), SEC-49, SEC-55
// (ảnh distroless không root theo digest) → tiêu chí J1–J3, J8, C10.
//
// Khác Jenkinsfile mẫu của TL-04 ở một điểm có chủ đích: ảnh dựng bằng Jib (theo TL-01 ch.18 và POM của
// NEN-005) thay cho BuildKit không root; xem wiki/14 mục "Điểm lệch giữa các nguồn".
//
// Dùng trong Jenkinsfile:
//   @Library('vt-pipeline@vt-pipeline-v1.0.0') _
//   vtJavaPipeline(service: 'mission-core', dir: 'backend')
// Tham số và biến môi trường cấp controller: xem src/vn/vt/pipeline/CauHinh.groovy và README.md.

import com.cloudbees.groovy.cps.NonCPS
import vn.vt.pipeline.CauHinh

def call(Map thamSo = [:]) {
  Map cfg = CauHinh.ghep(thamSo, env.getEnvironment())

  pipeline {
    agent {
      kubernetes {
        inheritFrom 'vt-build-ephemeral'   // pod dựng dùng một lần, không giữ trạng thái giữa các lần dựng
        defaultContainer 'jdk21'
      }
    }
    options {
      timeout(time: 45, unit: 'MINUTES')
      disableConcurrentBuilds(abortPrevious: true)
      buildDiscarder(logRotator(numToKeepStr: '50'))
    }
    environment {
      SERVICE = "${cfg.service}"
      REGISTRY = "${cfg.registry}"
      COSIGN_KEY = "${cfg.cosignKey}"
      COSIGN_VERIFY_KEY = "${cfg.cosignVerifyKey ?: cfg.cosignKey}"
      VT_MODULE_DIR = "${cfg.dir}"
      // Thông tin đăng nhập registry chỉ sống trong thư mục tạm của lần dựng, xoá ở post
      DOCKER_CONFIG = "${env.WORKSPACE}@tmp/vt-docker"
    }
    stages {
      stage('Bí mật và SAST') {
        parallel {
          stage('gitleaks') {
            steps { script { quetBiMat(cfg) } }
          }
          stage('Semgrep') {
            steps { script { quetSemgrep(cfg) } }
          }
          stage('Trivy fs') {
            steps { script { quetTrivyFs(cfg) } }
          }
        }
      }
      stage('Hợp đồng API') {
        parallel {
          stage('openapi-diff') {
            steps { script { kiemHopDong(cfg) } }
          }
          stage('Pact can-i-deploy') {
            when { expression { return cfg.pact } }   // bật khi QA-02 dựng xong Pact Broker
            steps { script { kiemPact(cfg) } }
          }
        }
      }
      stage('Dựng và kiểm thử') {
        steps { script { dungVaKiemThu(cfg) } }   // unit test, Testcontainers, ArchUnit, Modulith verify(), JaCoCo
        post {
          always {
            junit testResults: "${cfg.dir}/**/target/surefire-reports/*.xml, ${cfg.dir}/**/target/failsafe-reports/*.xml",
                  allowEmptyResults: true
            recordCoverage(tools: [[parser: 'JACOCO', pattern: "${cfg.dir}/**/target/site/jacoco/jacoco.xml"]],
                           qualityGates: [[threshold: cfg.coverageThreshold, metric: 'LINE', baseline: 'MODIFIED_FILES',
                                           criticality: 'FAILURE']])
          }
        }
      }
      stage('SonarQube') {
        when { expression { return cfg.sonar } }
        steps { script { chanNeuDaLoi(); kiemSonar(cfg) } }
      }
      stage('Ảnh, SBOM, quét') {
        steps { script { chanNeuDaLoi(); dungAnh(cfg) } }
      }
      stage('Ký và chứng thực') {
        when { branch 'main' }
        steps { script { chanNeuDaLoi(); kyVaChungThuc(cfg) } }
      }
      stage('Cập nhật GitOps') {
        when { branch 'main' }
        steps {
          script {
            chanNeuDaLoi()
            vtGitopsBump(service: cfg.service, digest: env.DIGEST, env: cfg.gitopsEnv,
                         image: "${cfg.registry}/${cfg.service}", commit: commitHienTai(),
                         repoUrl: cfg.gitopsRepo ?: env.GIT_URL, branch: cfg.gitopsBranch,
                         credentialsId: cfg.gitopsCredentialsId,
                         signingKeyCredentialsId: cfg.gitopsSigningKeyCredentialsId)   // commit có chữ ký vào kho GitOps
          }
        }
      }
    }
    post {
      always {
        archiveArtifacts allowEmptyArchive: true,
                         artifacts: '*.sarif, sbom.cdx.json, provenance.json, cosign-verify*.json, openapi-diff/*.md'
        sh 'rm -rf "$DOCKER_CONFIG"'
      }
      failure { vtNotify(channel: cfg.notifyChannel, service: cfg.service) }
    }
  }
}

// ─────────────────────────── Các bước ───────────────────────────

/** SEC-09: gitleaks trên dải commit của merge request (ngoài MR: commit mới nhất). */
def quetBiMat(Map cfg) {
  String dai = CauHinh.daiCommitGitleaks(env.getEnvironment())
  if (env.CHANGE_TARGET) {
    canMoc("origin/${env.CHANGE_TARGET}")
  }
  sh "gitleaks git --redact --exit-code 1 --config ${q(cfg.gitleaksConfig)} --log-opts=${q(dai)} " +
     "--report-format sarif --report-path gitleaks.sarif ."
}

/** SEC-39: Semgrep chặn merge; bộ p/java cộng quy tắc riêng .semgrep/vt-rules.yml. */
def quetSemgrep(Map cfg) {
  String configs = cfg.semgrepConfigs.collect { "--config ${q(it)}" }.join(' ')
  sh "semgrep scan ${configs} --error --metrics=off --disable-version-check --sarif -o semgrep.sarif ${q(cfg.dir)}"
}

/** SEC-23: SCA, cấu hình sai và giấy phép trên mã nguồn; HIGH, CRITICAL thì chặn. */
def quetTrivyFs(Map cfg) {
  sh "trivy fs --scanners vuln,misconfig,license --severity HIGH,CRITICAL --exit-code 1 ${q(cfg.dir)}"
}

/** ADR-32: không thay đổi phá vỡ hợp đồng OpenAPI so với nhánh đích. */
def kiemHopDong(Map cfg) {
  String moc = CauHinh.mocHopDong(cfg, env.getEnvironment())
  if (moc.startsWith('origin/')) {
    canMoc(moc)
  }
  writeFile file: '.vt-pipeline/openapi-diff.sh', text: libraryResource('vn/vt/pipeline/openapi-diff.sh')
  withEnv(["OPENAPI_DIFF=${cfg.openapiDiffCmd}"]) {
    sh "bash .vt-pipeline/openapi-diff.sh ${q(moc)} ${q(cfg.contractsDir)} openapi-diff"
  }
}

/** Kiểm thử hợp đồng Pact (TL-01 ch.18); để sẵn, bật bằng tham số pact: true khi QA-02 xong. */
def kiemPact(Map cfg) {
  withCredentials([string(credentialsId: cfg.pactCredentialsId, variable: 'PACT_BROKER_TOKEN')]) {
    sh "pact-broker can-i-deploy --pacticipant ${q(cfg.service)} --version ${q(commitHienTai())} " +
       "--to-environment staging --broker-base-url ${q(cfg.pactBrokerUrl)}"
  }
}

/** J1–J3: ./mvnw verify -Pci (kiểm thử chặn, ArchUnit, Modulith verify(), JaCoCo); bộ ca vàng nếu có. */
def dungVaKiemThu(Map cfg) {
  dir(cfg.dir) {
    sh "./mvnw -B -ntp verify -P${cfg.mavenProfile}"
    if (cfg.goldenModule) {
      sh "./mvnw -B -ntp -pl ${q(cfg.goldenModule)} test -Dgolden=true"
    }
  }
}

/** Cổng SonarQube: không lỗi Blocker, Critical mới; độ phủ mã mới (TL-01 ch.18). */
def kiemSonar(Map cfg) {
  withSonarQubeEnv(cfg.sonarServer) {
    dir(cfg.dir) {
      sh "./mvnw -B -ntp ${cfg.sonarGoal} -Dsonar.projectKey=${q(cfg.service)}"
    }
  }
  timeout(time: 10, unit: 'MINUTES') {
    waitForQualityGate abortPipeline: true
  }
}

/** SEC-49, SEC-55, SEC-23: Jib dựng ảnh distroless theo digest, SBOM CycloneDX, Trivy ảnh, Dependency-Track. */
def dungAnh(Map cfg) {
  dangNhapRegistry(cfg)
  String anh = "${cfg.registry}/${cfg.service}"
  String sha = commitHienTai()
  String moDun = cfg.jibModule ? "-pl ${q(cfg.jibModule)} -am " : ''
  String nen = cfg.baseImage ? "-Djib.from.image=${q(cfg.baseImage)} " : ''
  dir(cfg.dir) {
    sh "./mvnw -B -ntp -DskipTests ${moDun}compile jib:build ${nen}-Djib.to.image=${q(anh + ':' + sha)}"
  }
  String tepDigest = cfg.jibModule ? "${cfg.dir}/${cfg.jibModule}/target/jib-image.digest" : "${cfg.dir}/target/jib-image.digest"
  String digest = readFile(tepDigest).trim()
  if (!(digest ==~ /sha256:[0-9a-f]{64}/)) {
    error("Jib không trả digest hợp lệ: ${digest}")
  }
  env.DIGEST = digest
  env.IMAGE_REF = "${anh}@${digest}"
  echo "Ảnh đã dựng: ${env.IMAGE_REF}"
  sh 'syft scan "registry:$IMAGE_REF" -o cyclonedx-json=sbom.cdx.json'
  sh 'trivy image --image-src remote --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 "$IMAGE_REF"'
  if (cfg.dependencyTrack) {
    withCredentials([string(credentialsId: cfg.dependencyTrackCredentialsId, variable: 'DTRACK_KEY')]) {
      withEnv(["VT_DTRACK_URL=${cfg.dependencyTrackUrl}", "VT_COMMIT=${sha}"]) {
        // Khoá API ghi vào tệp tạm để không lộ trên dòng lệnh
        sh '''set +x
          hdr="$(mktemp)"; trap 'rm -f "$hdr"' EXIT
          printf 'X-Api-Key: %s\\n' "$DTRACK_KEY" > "$hdr"
          curl -sf -X POST -H @"$hdr" -F "autoCreate=true" -F "projectName=$SERVICE" \
            -F "projectVersion=$VT_COMMIT" -F "bom=@sbom.cdx.json" "$VT_DTRACK_URL/api/v1/bom"'''
      }
    }
  }
}

/** SEC-37, SEC-38: ký, chứng thực SBOM và provenance SLSA v1 trong container riêng, rồi tự kiểm lại. */
def kyVaChungThuc(Map cfg) {
  container(cfg.provenanceContainer) {
    List khoa = cfg.cosignVaultTokenCredentialsId ?
        [string(credentialsId: cfg.cosignVaultTokenCredentialsId, variable: 'VAULT_TOKEN')] : []
    withCredentials(khoa) {
      withEnv(["VAULT_ADDR=${cfg.vaultAddr}", "VT_COMMIT=${commitHienTai()}",
               "VT_BUILDER_ID=${cfg.builderId}", "VT_REPO=${env.GIT_URL ?: ''}",
               "VT_BASE_IMAGE=${cfg.baseImage ?: ''}"]) {
        sh 'cosign sign --yes --key "$COSIGN_KEY" --tlog-upload=false --use-signing-config=false "$IMAGE_REF"'
        sh 'cosign attest --yes --key "$COSIGN_KEY" --tlog-upload=false --use-signing-config=false ' +
           '--type cyclonedx --predicate sbom.cdx.json "$IMAGE_REF"'
        sh '''vt-provenance --builder-id "$VT_BUILDER_ID" --repo "$VT_REPO" --commit "$VT_COMMIT" \
                --digest "$DIGEST" --ref "refs/heads/$BRANCH_NAME" --build-url "${BUILD_URL:-}" \
                ${VT_BASE_IMAGE:+--base-image "$VT_BASE_IMAGE"} > provenance.json'''
        sh 'cosign attest --yes --key "$COSIGN_KEY" --tlog-upload=false --use-signing-config=false ' +
           '--type slsaprovenance1 --predicate provenance.json "$IMAGE_REF"'
        // Kiểm lại ngay bằng khoá công khai, như Kyverno sẽ kiểm ở cụm (SEC-28, SEC-38)
        sh 'cosign verify --key "$COSIGN_VERIFY_KEY" --insecure-ignore-tlog=true "$IMAGE_REF" > cosign-verify.json'
        sh 'cosign verify-attestation --key "$COSIGN_VERIFY_KEY" --insecure-ignore-tlog=true ' +
           '--type slsaprovenance1 "$IMAGE_REF" > cosign-verify-provenance.json'
      }
    }
  }
}

// ─────────────────────────── Tiện ích ───────────────────────────

/** Cổng ghi nhận kết quả (độ phủ, JUnit) đặt trạng thái FAILURE mà không dừng; chặn các stage sau. */
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

/** Nhánh đích phải có trong bản sao (refspec), nếu không gitleaks và openapi-diff không so được. */
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
    sh '''set +x
      umask 077
      host="${REGISTRY%%/*}"
      auth="$(printf '%s:%s' "$VT_REG_USER" "$VT_REG_PASS" | base64 | tr -d '\\n')"
      printf '{"auths":{"%s":{"auth":"%s"}}}\\n' "$host" "$auth" > "$DOCKER_CONFIG/config.json"'''
  }
}

/** Trích dẫn an toàn cho shell (dấu nháy đơn). */
@NonCPS
String q(Object s) {
  return "'" + s.toString().replace("'", "'\"'\"'") + "'"
}
