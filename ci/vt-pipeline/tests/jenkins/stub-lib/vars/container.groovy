// CHỈ DÙNG KHI KIỂM THỬ CỤC BỘ bằng Jenkinsfile Runner (ci/vt-pipeline/tests/chay-kiem-thu.sh).
// Thay bước `container(...)` của plugin kubernetes: plugin này không cài được ở môi trường kiểm thử
// (máy kiểm thử không tới được updates.jenkins.io, repo.jenkins-ci.org). Trên Jenkins thật, bước
// `container` của plugin kubernetes chạy khối lệnh trong container riêng của pod dựng; ở đây chạy tại chỗ.
def call(String ten, Closure khoi) {
  echo "[stub kiểm thử] container('${ten}'): chạy tại chỗ thay cho container riêng của pod"
  khoi()
}
