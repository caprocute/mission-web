// CHỈ DÙNG KHI KIỂM THỬ CỤC BỘ bằng Jenkinsfile Runner (ci/vt-pipeline/tests/chay-kiem-thu.sh).
// Thay bước `recordCoverage` của plugin coverage (không cài được ở môi trường kiểm thử). Bản thay thế đọc
// báo cáo JaCoCo và áp ngưỡng LINE trên TOÀN BỘ mã của báo cáo; plugin thật áp trên tệp thay đổi
// (baseline MODIFIED_FILES). Khi dưới ngưỡng với criticality FAILURE, đặt kết quả FAILURE như plugin thật
// (không ném lỗi), để kiểm chứng việc vtJavaPipeline chặn các stage sau bằng chanNeuDaLoi().
def call(Map a) {
  String mau = a.tools?.getAt(0)?.pattern ?: '**/target/site/jacoco/jacoco.xml'
  def tep = findFiles(glob: mau)
  if (!tep) {
    echo "[stub kiểm thử] recordCoverage: không có báo cáo ${mau}"
    return
  }
  long phu = 0
  long thieu = 0
  for (f in tep) {
    List<Long> so = demDong(readFile(f.path))
    phu += so[0]
    thieu += so[1]
  }
  double tiLe = (phu + thieu) ? (100.0d * phu / (phu + thieu)) : 100.0d
  echo String.format('[stub kiểm thử] recordCoverage: độ phủ dòng %.1f%% (%d/%d dòng)', tiLe, phu, phu + thieu)
  for (g in (a.qualityGates ?: [])) {
    if (g.metric == 'LINE' && tiLe < (g.threshold as double)) {
      echo "[stub kiểm thử] recordCoverage: dưới ngưỡng ${g.threshold}% → ${g.criticality}"
      currentBuild.result = (g.criticality == 'FAILURE') ? 'FAILURE' : 'UNSTABLE'
    }
  }
}

@NonCPS
List<Long> demDong(String xml) {
  def parser = new XmlSlurper(false, false)
  parser.setFeature('http://apache.org/xml/features/disallow-doctype-decl', false)
  parser.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
  def goc = parser.parseText(xml)
  def line = goc.counter.find { it.@type == 'LINE' }
  return [(line.@covered.toString() ?: '0') as long, (line.@missed.toString() ?: '0') as long]
}
