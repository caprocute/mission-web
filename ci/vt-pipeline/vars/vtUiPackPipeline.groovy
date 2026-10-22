// vtUiPackPipeline: pipeline nội dung cho UI Pack (INF-06, OPS-11 "UI Pack ≤ 1 ngày"; TL-01 chương 7; ADR-05).
// Một lượt chạy: kiểm lược đồ token, SDUI, biểu mẫu bằng validator không sinh mã lúc chạy (AGENTS.md quy tắc 5),
// component chỉ trong registry, chế độ người cao tuổi, CSS biên dịch ≤ 30 KB gzip, axe-core WCAG 2.1 AA trên trang
// mẫu ở hai chế độ, ảnh chụp so sánh trên staging; nhánh main ký gói, tải lên MOD-08 (createUiPackVersion,
// updateUiPackVersion, submitUiPackVersion), chờ người duyệt rồi gọi publishUiPackVersion để máy chủ phát EVT-23.
//   @Library('vt-pipeline@vt-pipeline-v1.1.0') _
//   vtUiPackPipeline(dir: 'ui-packs/default', uiPackId: '<uuid>')
import vn.vt.pipeline.CauHinhLoai

def call(Map thamSo = [:]) {
  Map cfg = CauHinhLoai.ghep('uiPack', thamSo, env.getEnvironment())

  pipeline {
    agent {
      kubernetes {
        inheritFrom 'vt-build-ephemeral'
        defaultContainer 'node'           // node + playwright-core + axe-core + Chromium, python3 + jsonschema
      }
    }
    options {
      timeout(time: 60, unit: 'MINUTES')    // OPS-11: một lượt dưới 60 phút để phát hành trong ngày
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    stages {
      stage('Bí mật') {
        steps { script { vtBuoc.quetBiMat(cfg) } }
      }
      stage('Lược đồ, registry, kích thước') {
        steps {
          script {
            String c = vtBuoc.napTaiNguyen('vt-ui-pack.py')
            String nguon = cfg.registrySources.collect { vtBuoc.q(it) }.join(' ')
            sh 'rm -rf .vt-ui && mkdir -p .vt-ui'
            danhSachGoi(cfg).eachWithIndex { goi, i ->
              echo "UI Pack ${goi}"
              sh "python3 ${c} kiem ${vtBuoc.q(goi)} --registry-src ${nguon} --nguong-kb ${cfg.cssBudgetKb} " +
                 "--css-ra .vt-ui/${i}.css --bao-cao .vt-ui/${i}-kiem.json"
            }
          }
        }
      }
      stage('Khả năng tiếp cận (axe-core)') {
        steps {
          script {
            String c = vtBuoc.napTaiNguyen('vt-ui-pack.py')
            String axe = vtBuoc.napTaiNguyen('vt-ui-pack-axe.mjs')
            danhSachGoi(cfg).eachWithIndex { goi, i ->
              sh "python3 ${c} trang-mau ${vtBuoc.q(goi)} .vt-ui/${i}.html && node ${axe} .vt-ui/${i}.html .vt-ui/${i}-axe.json"
            }
          }
        }
      }
      stage('Ảnh chụp so sánh trên staging') {
        when { expression { return cfg.stagingUrl as boolean } }
        steps {
          script {
            String anh = vtBuoc.napTaiNguyen('vt-ui-pack-anh.mjs')
            danhSachGoi(cfg).eachWithIndex { goi, i ->
              sh "node ${anh} ${vtBuoc.q(cfg.stagingUrl)} .vt-ui/${i}.css .vt-ui/anh-${i} / /okr /appraisal"
            }
          }
        }
      }
      stage('Ký gói') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch } }
        steps { script { vtBuoc.chanNeuDaLoi(); vtBuoc.kyTep(cfg, danhSachGoi(cfg)) } }
      }
      stage('Tải lên và gửi kiểm tra') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch && cfg.uiPackId } }
        steps {
          script {
            String c = vtBuoc.napTaiNguyen('vt-ui-pack.py')
            danhSachGoi(cfg).each { goi ->
              String phienBan = sh(script: "python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))[\"version\"])' ${vtBuoc.q(goi)}",
                                   returnStdout: true).trim()
              String duong = "/ui/packs/${cfg.uiPackId}/versions"
              writeFile file: '.vt-ui/tao.json', text: groovy.json.JsonOutput.toJson([version: phienBan])
              sh "python3 ${c} than-api ${vtBuoc.q(goi)} .vt-ui/cap-nhat.json"
              vtBuoc.goiApi(cfg, "POST ${duong} --body .vt-ui/tao.json")
              vtBuoc.goiApi(cfg, "PUT ${duong}/${phienBan} --if-match-from ${duong}/${phienBan} --body .vt-ui/cap-nhat.json")
              // Máy chủ chạy lại bộ kiểm tra tự động của MOD-08 (schema, registry, tương phản AA, ngân sách CSS)
              vtBuoc.goiApi(cfg, "POST ${duong}/${phienBan}/submit --if-match-from ${duong}/${phienBan} --doi-job")
              env.VT_UI_PHIEN_BAN = phienBan
            }
          }
        }
      }
      stage('Duyệt phát hành') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch && cfg.uiPackId } }
        steps {
          archiveArtifacts artifacts: '.vt-ui/**', allowEmptyArchive: true
          input message: 'Đã xem ảnh chụp so sánh và kết quả kiểm tra; phát hành UI Pack?', submitter: cfg.approverGroup,
                ok: 'Phát hành', parameters: [string(name: 'Y_KIEN', defaultValue: 'Đồng ý phát hành', description: 'Ý kiến duyệt')]
        }
      }
      stage('Phát hành') {
        when { expression { return env.BRANCH_NAME == cfg.releaseBranch && cfg.uiPackId } }
        steps {
          script {
            String duong = "/ui/packs/${cfg.uiPackId}/versions/${env.VT_UI_PHIEN_BAN}"
            writeFile file: '.vt-ui/duyet.json', text: groovy.json.JsonOutput.toJson([comment: "Duyệt qua Jenkins ${env.BUILD_URL ?: ''}".toString()])
            // Máy chủ chuyển trạng thái, phát EVT-23 để vô hiệu cache CDN theo khoá phiên bản
            vtBuoc.goiApi(cfg, "POST ${duong}/publish --if-match-from ${duong} --body .vt-ui/duyet.json --doi-job")
          }
        }
      }
    }
    post {
      always { archiveArtifacts allowEmptyArchive: true, artifacts: '*.sarif, .vt-ui/**, **/*.cosign.bundle' }
      failure { vtNotify(channel: cfg.notifyChannel, service: cfg.service) }
    }
  }
}

/** Các tệp gói (cfg.packFile) trong cfg.dir, sắp theo tên. */
List danhSachGoi(Map cfg) {
  String ra = sh(script: "find ${vtBuoc.q(cfg.dir)} -name ${vtBuoc.q(cfg.packFile)} -type f | sort", returnStdout: true).trim()
  if (!ra) { error("Không thấy ${cfg.packFile} trong ${cfg.dir}") }
  return ra.readLines()
}
