// vtNotify: báo kết quả pipeline cho kênh của đội (INF-05, post failure của vtJavaPipeline).
//
//   vtNotify(channel: 'okr-ci', service: 'mission-core')
//
// Gửi qua webhook vào (incoming webhook kiểu Mattermost/Rocket.Chat, nhận JSON {channel, text}); URL
// webhook là bí mật, lấy từ credential `vt-ci-webhook` (Secret text). Chưa cấu hình credential thì chỉ
// ghi vào log. Lỗi gửi thông báo không bao giờ làm đổi kết quả bản dựng. Kênh thông báo cụ thể của đội
// chưa được TL chốt (suy luận), nên định dạng giữ ở mức tối thiểu.

def call(Map a = [:]) {
  String kenh = a.channel ?: 'okr-ci'
  String service = a.service ?: (env.SERVICE ?: env.JOB_NAME)
  String ketQua = currentBuild.currentResult
  String nhanh = env.BRANCH_NAME ?: 'không rõ nhánh'
  String noiDung = "[${ketQua}] ${service} · ${nhanh} · bản dựng #${env.BUILD_NUMBER}" +
      (env.BUILD_URL ? " · ${env.BUILD_URL}" : '')
  echo "vtNotify(${kenh}): ${noiDung}"
  String credentialsId = a.webhookCredentialsId ?: 'vt-ci-webhook'
  try {
    withCredentials([string(credentialsId: credentialsId, variable: 'VT_WEBHOOK_URL')]) {
      writeFile file: '.vt-notify.json', text: groovy.json.JsonOutput.toJson([channel: kenh, text: noiDung])
      sh '''set +x
        curl -sf -m 10 -X POST -H 'Content-Type: application/json' --data @.vt-notify.json "$VT_WEBHOOK_URL" >/dev/null
        rm -f .vt-notify.json'''
    }
  } catch (err) {
    echo "vtNotify: không gửi được thông báo (${err.getClass().getSimpleName()}); kết quả bản dựng giữ nguyên."
  }
}
