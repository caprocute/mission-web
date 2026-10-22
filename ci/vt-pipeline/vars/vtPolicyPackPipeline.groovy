// vtPolicyPackPipeline: pipeline nội dung cho Policy Pack (INF-06, OPS-10, NFR-07; TL-04 "Phát hành Policy Pack,
// UI Pack và bản LTS"). Không dựng lại mã: merge request do công cụ soạn quy tắc (MOD-05) tạo chạy kiểm cú pháp,
// kiểm thử đơn vị quy tắc và bộ ca vàng phải đạt 100% (J6). Nhánh main đóng gói gói quy tắc tất định, ký cosign,
// gọi so sánh bóng trên dữ liệu đã che của kỳ trước (runPolicyTests, compareWithVersion) để có báo cáo chênh lệch
// theo tenant, chờ người duyệt, rồi gửi phát hành qua API (submitPolicyPackVersion). Máy chủ phát EVT-13 và ghi
// sổ cái khi phát hành; pipeline không ghi CSDL.
//   @Library('vt-pipeline@vt-pipeline-v1.1.0') _
//   vtPolicyPackPipeline(packIds: ['khoi-chinh-quyen/nd335': '<uuid>'], effectivePeriodId: '<uuid>',
//                        scopes: [[type: 'TENANT', id: '<uuid>']])
import vn.vt.pipeline.CauHinhLoai

def call(Map thamSo = [:]) {
  Map cfg = CauHinhLoai.ghep('policyPack', thamSo, env.getEnvironment())
  String ppGoi = cfg.packs.collect { vtBuoc.q(it) }.join(' ')

  pipeline {
    agent {
      kubernetes {
        inheritFrom 'vt-build-ephemeral'
        defaultContainer 'python'
      }
    }
    options {
      timeout(time: 120, unit: 'MINUTES')       // so sánh bóng chạy trên dữ liệu cả kỳ
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    stages {
      stage('Bí mật') {
        steps { script { vtBuoc.quetBiMat(cfg) } }
      }
      stage('Cú pháp quy tắc') {
        // YAML, biến CEL đã khai báo, miền tham số, DMN phủ kín, vòng đời nhiệm vụ theo trạng thái chuẩn
        steps { sh cfg.staticCheckCmd }
      }
      stage('Kiểm thử quy tắc') {
        parallel {
          stage('Kiểm thử đơn vị quy tắc') { steps { sh cfg.unitTestCmd } }
          // J6: bộ ca vàng phải đạt 100%; giữ mã thoát của bộ chạy khi ghi kết quả ra tệp
          stage('Ca vàng 100%') { steps { sh "${cfg.goldenCmd} > ca-vang.txt 2>&1; ma=\$?; cat ca-vang.txt; exit \$ma" } }
        }
      }
      stage('Đóng gói và ký') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch } }
        steps {
          script {
            vtBuoc.chanNeuDaLoi()
            String c = vtBuoc.napTaiNguyen('vt-policy-pack.py')
            // J6: bộ ca vàng dùng để phát hành phải do nghiệp vụ ký, bộ giả lập không đủ
            sh "python3 ${c} kiem-ky ${vtBuoc.q(cfg.dir)} ${ppGoi}"
            sh "rm -rf goi && python3 ${c} dong-goi ${vtBuoc.q(cfg.dir)} goi ${ppGoi}"
            vtBuoc.kyTep(cfg, sh(script: 'ls goi/*', returnStdout: true).trim().readLines())
          }
        }
      }
      stage('So sánh bóng') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch && cfg.shadow && cfg.packIds } }
        steps {
          script {
            String c = vtBuoc.napTaiNguyen('vt-policy-pack.py')
            sh(script: "python3 ${c} ds ${vtBuoc.q(cfg.dir)} ${ppGoi}", returnStdout: true).trim().readLines().each { dong ->
              def (String id, String phienBan) = dong.tokenize('\t')
              String maGoi = cfg.packIds[id]
              if (!maGoi) { error("Chưa khai packIds cho gói ${id}") }
              writeFile file: '.vt-pipeline/shadow.json',
                        text: groovy.json.JsonOutput.toJson(cfg.shadowCompareWithVersion ? [compareWithVersion: cfg.shadowCompareWithVersion] : [:])
              vtBuoc.goiApi(cfg, "POST /policies/packs/${maGoi}/versions/${phienBan}/test-runs --body .vt-pipeline/shadow.json " +
                                 "--doi-job --ra ${vtBuoc.q('chenh-lech-' + id.replace('/', '__') + '.json')}")
            }
          }
        }
      }
      stage('Duyệt phát hành') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch && cfg.packIds } }
        steps {
          archiveArtifacts artifacts: 'chenh-lech-*.json, ca-vang.txt', allowEmptyArchive: true
          // Chủ sản phẩm và đại diện nghiệp vụ đọc báo cáo chênh lệch rồi ký duyệt (ngày 4 của OPS-10)
          input message: 'Đã đọc báo cáo chênh lệch theo tenant và đồng ý gửi phát hành Policy Pack?',
                submitter: cfg.approverGroup, ok: 'Gửi phát hành'
        }
      }
      stage('Gửi phát hành') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch && cfg.packIds } }
        steps {
          script {
            String c = vtBuoc.napTaiNguyen('vt-policy-pack.py')
            writeFile file: '.vt-pipeline/submit.json', text: groovy.json.JsonOutput.toJson(
                [effectivePeriodId: cfg.effectivePeriodId, scopes: cfg.scopes, note: "Jenkins ${env.BUILD_URL ?: ''}".toString()])
            sh(script: "python3 ${c} ds ${vtBuoc.q(cfg.dir)} ${ppGoi}", returnStdout: true).trim().readLines().each { dong ->
              def (String id, String phienBan) = dong.tokenize('\t')
              String duong = "/policies/packs/${cfg.packIds[id]}/versions/${phienBan}"
              // Máy chủ khởi động mẫu duyệt phát hành; EVT-13 phát khi duyệt xong (không do pipeline ghi)
              vtBuoc.goiApi(cfg, "POST ${duong}/submit --if-match-from ${duong} --body .vt-pipeline/submit.json")
            }
          }
        }
      }
    }
    post {
      always { archiveArtifacts allowEmptyArchive: true, artifacts: '*.sarif, ca-vang.txt, chenh-lech-*.json, goi/*' }
      failure { vtNotify(channel: cfg.notifyChannel, service: cfg.service) }
    }
  }
}
