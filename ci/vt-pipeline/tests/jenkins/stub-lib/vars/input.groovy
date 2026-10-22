// CHỈ DÙNG KHI KIỂM THỬ CỤC BỘ bằng Jenkinsfile Runner (ci/vt-pipeline/tests/chay-kiem-thu-inf06.sh).
// Jenkinsfile Runner chạy không người tương tác nên bước `input` (duyệt phát hành Policy Pack, UI Pack) sẽ treo.
// Bản stub ghi lại câu hỏi, nhóm được duyệt và coi như người duyệt bấm đồng ý; trên Jenkins thật bước `input`
// của plugin pipeline-input-step chờ đúng người trong nhóm `submitter` duyệt.
def call(Map thamSo) {
  echo "[stub kiểm thử] input: '${thamSo.message}', nhóm duyệt '${thamSo.submitter}': coi như đã duyệt"
  return thamSo.parameters ? 'Đồng ý (kiểm thử)' : null
}
