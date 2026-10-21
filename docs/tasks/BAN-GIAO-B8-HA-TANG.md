# Bàn giao luồng B8 (hạ tầng) – trạng thái ngày 20/10/2026

Luồng tạm dừng vì hết quota. Nhánh: `claude/b8-ha-tang-g7g48k` (dựng trên `claude/ke-hoach-tong-the-ds4nmi`). Mọi task chạy theo `./scripts/agent-task.sh <MÃ>`.

| Task | Trạng thái | Chi tiết |
|---|---|---|
| INF-01 chart `vt-service` | Xong (21/10/2026) | Kiểm thử tĩnh 23/23; kiểm thử kind 6/6 (`tests/kiem-thu-kind.sh`). Nguyên nhân hết thời gian chờ trước đó: ảnh nạp theo digest đa nền tảng không khớp manifest amd64, đã sửa trong `nap-anh.sh`. Wiki 12, 14 đã cập nhật. |
| INF-05 pipeline Jenkins Java | Xong (chờ backend thật) | `ci/vt-pipeline`, `backend/Jenkinsfile`, `.semgrep/`, `.gitleaks.toml`, wiki 11, 14. 4/4 MR sai bị chặn trên Jenkinsfile Runner, cosign verify/verify-attestation đạt. Chưa chạy trên `backend/` thật (chờ NEN-005) và Jenkins/Sonar/Dependency-Track thật. Chi tiết trong commit INF-05. |
| INF-08 cụm cell phần 1 | Xong (21/10/2026) | `deploy/clusters/cell-cluster` + `cell-g4-hl` (Cluster API, kiểm tĩnh 11/11), `deploy/charts/cell-base` (tĩnh 8/8, kind 7/7), `deploy/charts/api-gateway` (tĩnh 16/16, kind 16/16). Chế độ controller, etcd, Ingress Controller mới kiểm schema; provider Viettel Cloud ở dạng mẫu. Điểm lệch ghi ở wiki 14. |
| INF-14 quan sát phần 1 | Xong (chờ gộp) | Chart `otel-collector`, `observability-cell`, `observability-mgmt`; kiểm thử kind `deploy/environments/kind/kiem-thu-quan-sat.sh`. Còn thiếu digest thật của ảnh Thanos, rclone (Docker Hub giới hạn lượt kéo). |
| INF-15 SLO, cảnh báo, dashboard | Xong (chờ gộp) | Luật ở `observability-cell/rules` và `observability-mgmt/rules` (Thanos Ruler), Alertmanager `observability-mgmt/alertmanager`, dashboard Grafonnet `observability-mgmt/dashboards`; metric APISIX nhãn `kind`. Tên metric ứng dụng `vt_*` là suy luận (wiki 12). Ảnh chụp đường cảnh báo tới trace: `/mnt/project-files/b8-ha-tang/inf-15/`. |
| INF-02 chart dịch vụ, `vt-platform` | Xong (chờ gộp) | 12 chart dịch vụ dựng trên `vt-service`, Keycloak trong chart `iam` (khoá `mayChu`), chart cha `deploy/charts/vt-platform` với `values-saas/private/airgap.yaml`; kiểm bằng `vt-platform/ci/kiem-ba-mo-hinh.sh`, cài thử bằng `ci/kiem-thu-kind.sh`. org-service và workflow-service vẫn là chart của luồng backend, lệch ghi ở `ci/lech-da-ghi.yaml`. |
| Các INF khác của B8 | Chưa làm | |

## Bẫy môi trường đã gặp (máy cloud chạy kind trong container lồng nhau)

- Không có `CAP_SYS_RESOURCE`: phải bật `restrict_oom_score_adj` cho containerd (đã có trong `cluster.yaml`) và bỏ `SYS_RESOURCE` khỏi capability của Cilium (`cilium-values.yaml`). Máy thường không cần.
- Node kind không kéo ảnh qua proxy cục bộ: dùng `deploy/environments/kind/nap-anh.sh` để nạp ảnh amd64 vào node; Docker Hub có lúc trả 429.
- Proxy chặn get.helm.sh, quay.io, registry.k8s.io, các kho chart `*.github.io`, semgrep.dev, updates.jenkins.io: helm được dựng bằng `go install`, Cilium cài bằng cilium-cli (chart nhúng) với ảnh Docker Hub.
