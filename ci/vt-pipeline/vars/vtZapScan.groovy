// vtZapScan: bước DAST OWASP ZAP (SEC-40, TL-04 mục 9.8; QA-13). Chạy kế hoạch trong infra/security/zap của repo
// với môi trường đích (staging, dev) rồi đối chiếu ngoại lệ: 0 cảnh báo High, mọi Medium có giải trình trong
// ngoai-le.yaml, nếu không thì dừng pipeline. Địa chỉ đích và bí mật đến từ tham số và Jenkins credentials, không
// nằm trong repo.
//
//   vtZapScan(loai: 'api', module: 'org', apiUrl: env.VT_STAGING_API_URL, issuer: env.VT_STAGING_ISSUER,
//             clientCredentialsId: 'zap-staging-client')
//   vtZapScan(loai: 'web', webUrl: env.VT_STAGING_WEB_URL)
//
// Tham số:
//   loai                 'api' (quét chủ động theo hợp đồng contracts/openapi/modules/<module>.yaml) hoặc 'web'
//   module               module hợp đồng cho loai api, mặc định 'org'; danh sách thì quét lần lượt
//   apiUrl, webUrl       gốc API (kết thúc /api/) hoặc gốc trang web (kết thúc /)
//   issuer               issuer realm để tính địa chỉ cấp token
//   clientId             client của ZAP, mặc định 'mission-zap'
//   clientCredentialsId  Jenkins credential (secret text) chứa client secret cho grant client_credentials
//   refreshCredentialsId thay cho clientCredentialsId: secret text chứa refresh token người dùng thử (dev)
//   image                ảnh ZAP, nên ghim digest (SEC-55); mặc định ghcr.io/zaproxy/zaproxy:stable
//   network              mạng docker cho container ZAP khi đích chỉ tới được từ mạng đó

def call(Map thamSo = [:]) {
  Map cfg = [module: 'org', clientId: 'mission-zap', image: 'ghcr.io/zaproxy/zaproxy:stable'] + thamSo
  if (!(cfg.loai in ['api', 'web'])) {
    error("vtZapScan: loai phải là 'api' hoặc 'web', nhận '${cfg.loai}'")
  }
  List bien = ["ZAP_IMAGE=${cfg.image}", "ZAP_BAO_CAO=${env.WORKSPACE}/zap-ket-qua"]
  if (cfg.network) {
    bien << "ZAP_DOCKER_NETWORK=${cfg.network}"
  }
  if (cfg.loai == 'web') {
    if (!cfg.webUrl) {
      error('vtZapScan: thiếu webUrl')
    }
    withEnv(bien + ["ZAP_WEB_URL=${cfg.webUrl}"]) {
      chay('web', null)
    }
    return
  }
  if (!cfg.apiUrl || !cfg.issuer) {
    error('vtZapScan: loai api cần apiUrl và issuer')
  }
  bien += ["ZAP_API_URL=${cfg.apiUrl}", "ZAP_TOKEN_URL=${cfg.issuer.replaceAll('/+$', '')}/protocol/openid-connect/token",
           "ZAP_CLIENT_ID=${cfg.clientId}"]
  List khoa = []
  if (cfg.clientCredentialsId) {
    khoa << string(credentialsId: cfg.clientCredentialsId, variable: 'ZAP_CLIENT_SECRET')
  } else if (cfg.refreshCredentialsId) {
    khoa << string(credentialsId: cfg.refreshCredentialsId, variable: 'ZAP_REFRESH_TOKEN')
  } else {
    error('vtZapScan: loai api cần clientCredentialsId hoặc refreshCredentialsId')
  }
  List modules = cfg.module instanceof List ? cfg.module : [cfg.module]
  modules.each { m ->
    if (!((m as String) ==~ /[a-z][a-z0-9-]{0,40}/)) {
      error("vtZapScan: tên module không hợp lệ '${m}'")
    }
  }
  withEnv(bien) {
    withCredentials(khoa) {
      modules.each { m -> chay('api', m as String) }
    }
  }
}

/** Một lần quét; giữ báo cáo của từng lần (SARIF, JSON) làm bằng chứng SEC-40 kể cả khi bị chặn. */
private void chay(String loai, String module) {
  String thuMuc = "zap-ket-qua/${loai}${module ? '-' + module : ''}"
  try {
    withEnv(["ZAP_BAO_CAO=${env.WORKSPACE}/${thuMuc}"]) {
      sh "infra/security/zap/chay-zap.sh ${loai}${module ? ' ' + vtBuoc.q(module) : ''}"
    }
  } finally {
    archiveArtifacts artifacts: "${thuMuc}/*", allowEmptyArchive: true, fingerprint: false
  }
}
