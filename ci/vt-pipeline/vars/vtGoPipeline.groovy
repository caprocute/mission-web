// vtGoPipeline: pipeline dùng chung cho microservice Go (SVC-03 ledger, SVC-09, SVC-12 và các công cụ Go) (INF-06).
// Truy vết: TL-04 mục 9.6 "Chuỗi cung ứng phần mềm" → SEC-09, SEC-23, SEC-37, SEC-38, SEC-39, SEC-49, SEC-55 → J1–J3.
//   @Library('vt-pipeline@vt-pipeline-v1.1.0') _
//   vtGoPipeline(service: 'ledger-service', dir: 'services-go/ledger')
import vn.vt.pipeline.CauHinhLoai

def call(Map thamSo = [:]) {
  Map cfg = CauHinhLoai.ghep('go', thamSo, env.getEnvironment())

  pipeline {
    agent {
      kubernetes {
        inheritFrom 'vt-build-ephemeral'
        defaultContainer 'go'
      }
    }
    options {
      timeout(time: 30, unit: 'MINUTES')
      disableConcurrentBuilds(abortPrevious: true)
      buildDiscarder(logRotator(numToKeepStr: '50'))
    }
    environment {
      DOCKER_CONFIG = "${env.WORKSPACE}@tmp/vt-docker"
      GOFLAGS = '-mod=readonly'
    }
    stages {
      stage('Bí mật và SAST') {
        parallel {
          stage('gitleaks') { steps { script { vtBuoc.quetBiMat(cfg) } } }
          stage('Semgrep') { steps { script { vtBuoc.quetSemgrep(cfg) } } }
          stage('Trivy fs') { steps { script { vtBuoc.quetTrivyFs(cfg) } } }
          stage('Dockerfile') { steps { script { vtBuoc.kiemDockerfile(cfg) } } }
          // Chỉ chặn lỗ hổng mà mã thực sự gọi tới (phân tích đồ thị lời gọi; govulncheck trả mã 3 khi có)
          stage('govulncheck') { steps { dir(cfg.dir) { sh "govulncheck -show verbose ${cfg.goPackages}" } } }
        }
      }
      stage('go vet và kiểm thử') {
        steps {
          dir(cfg.dir) {
            sh "go vet ${cfg.goPackages}"
            sh "go test -race -covermode=atomic -coverprofile=coverage.out ${cfg.goPackages}"
          }
          script {
            vtBuoc.kiemDoPhu(cfg, 'Go', "cd ${vtBuoc.q(cfg.dir)} && go tool cover -func=coverage.out | awk '/^total:/ {print \$NF}'")
          }
        }
      }
      stage('Ảnh, SBOM, quét') {
        steps { script { vtBuoc.chanNeuDaLoi(); vtBuoc.dungAnh(cfg); vtBuoc.sbomVaQuetAnh(cfg) } }
      }
      stage('Ký và chứng thực') {
        when { branch 'main' }
        steps { script { vtBuoc.chanNeuDaLoi(); vtBuoc.kyVaChungThucAnh(cfg) } }
      }
      stage('Cập nhật GitOps') {
        when { branch 'main' }
        steps { script { vtBuoc.chanNeuDaLoi(); vtBuoc.capNhatGitops(cfg) } }
      }
    }
    post {
      always {
        archiveArtifacts allowEmptyArchive: true,
                         artifacts: '*.sarif, sbom.cdx.json, provenance.json, cosign-verify*.json, **/coverage.out'
        sh 'rm -rf "$DOCKER_CONFIG"'
      }
      failure { vtNotify(channel: cfg.notifyChannel, service: cfg.service) }
    }
  }
}
