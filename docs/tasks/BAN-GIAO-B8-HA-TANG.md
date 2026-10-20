# Bàn giao luồng B8 (hạ tầng) – trạng thái ngày 20/10/2026

Luồng tạm dừng vì hết quota. Nhánh: `claude/b8-ha-tang-g7g48k` (dựng trên `claude/ke-hoach-tong-the-ds4nmi`). Mọi task chạy theo `./scripts/agent-task.sh <MÃ>`.

| Task | Trạng thái | Chi tiết |
|---|---|---|
| INF-01 chart `vt-service` | Gần xong | Chart thư viện `deploy/charts/vt-service` xong; `tests/chay-kiem-thu.sh` đạt 23/23 (helm lint, kubeconform -strict kèm CRD, kube-linter, golden TL-04 mục 5.5, 5 ca phải thất bại). **Còn lại:** chạy `tests/kiem-thu-kind.sh` (pod Ready, rải 3 vùng, PDB, NetworkPolicy, xoá node ≤ 120 s) – lần chạy cuối dừng ở `helm upgrade --wait` (context deadline), chưa điều tra; cập nhật wiki 12 (mục cách dùng `vt-service`) và wiki 14 (điểm lệch +2/+4 pod của HPA, compact đổi spread sang node `DoNotSchedule` – suy luận). |
| INF-05 pipeline Jenkins Java | Xong (chờ backend thật) | `ci/vt-pipeline`, `backend/Jenkinsfile`, `.semgrep/`, `.gitleaks.toml`, wiki 11, 14. 4/4 MR sai bị chặn trên Jenkinsfile Runner, cosign verify/verify-attestation đạt. Chưa chạy trên `backend/` thật (chờ NEN-005) và Jenkins/Sonar/Dependency-Track thật. Chi tiết trong commit INF-05. |
| INF-08 cụm cell phần 1 | Chưa làm | Chỉ có cụm kind nền `deploy/environments/kind` (1 control plane + 3 worker vùng hl-a/b/c, Cilium 1.18.1 chạy được). Chưa có Cluster API, `cell-base`, `api-gateway`, `vt-claims`. |
| INF-14 quan sát phần 1 | Chưa làm | Phụ thuộc INF-08. |
| Các INF khác của B8 | Chưa làm | |

## Bẫy môi trường đã gặp (máy cloud chạy kind trong container lồng nhau)

- Không có `CAP_SYS_RESOURCE`: phải bật `restrict_oom_score_adj` cho containerd (đã có trong `cluster.yaml`) và bỏ `SYS_RESOURCE` khỏi capability của Cilium (`cilium-values.yaml`). Máy thường không cần.
- Node kind không kéo ảnh qua proxy cục bộ: dùng `deploy/environments/kind/nap-anh.sh` để nạp ảnh amd64 vào node; Docker Hub có lúc trả 429.
- Proxy chặn get.helm.sh, quay.io, registry.k8s.io, các kho chart `*.github.io`, semgrep.dev, updates.jenkins.io: helm được dựng bằng `go install`, Cilium cài bằng cilium-cli (chart nhúng) với ảnh Docker Hub.
