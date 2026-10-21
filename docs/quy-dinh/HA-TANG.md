# Quy định riêng cho hạ tầng, triển khai và vận hành

> Tệp này bổ sung `AGENTS.md` cho mọi task có mã `INF-`, `VH-` và các task `QA-` về hiệu năng, dự phòng, ATTT. Nguồn: TL-04 (WI, PERF, DR, SEC, OPS), TL-05 (QM-1..QM-5), TL-01 chương 12 và PL B.

## 1. Nguyên tắc

Hạ tầng production chạy trên Kubernetes với GitOps: mọi thay đổi cụm đi qua Git (`deploy/`), Argo CD đồng bộ, không `kubectl apply` tay lên môi trường dùng chung. Đóng gói bằng Helm chart trong `deploy/charts/`, giá trị theo môi trường trong `deploy/environments/<môi-trường>/`, chính sách Kyverno trong `deploy/policies/`. Ảnh container dựng bằng Jenkins, đẩy lên Harbor, ký bằng cosign, kèm SBOM; Kyverno từ chối ảnh chưa ký. Không đổi công cụ đã chốt (Jenkins, Argo CD, Helm, Harbor, Kyverno, Istio ambient, APISIX + Coraza, CNPG, Strimzi, KEDA, OpenBao, Prometheus/Thanos, Loki, Tempo, Grafana).

## 2. Bí mật và an toàn

Không bao giờ ghi mật khẩu, khoá, token, địa chỉ máy chủ thật hay tên tài khoản vào repo, wiki, task, nhật ký hay thông điệp commit. Bí mật nằm trong OpenBao và được nạp bằng External Secrets hoặc CSI; tệp mẫu chỉ có giá trị giả. Mọi manifest phải qua `kubeconform`, `kube-linter`/`checkov` và chính sách Kyverno ở chế độ kiểm thử trước khi gộp. Pod chạy không root, hệ tệp gốc chỉ đọc, có `resources` và `NetworkPolicy` mặc định chặn.

## 3. Môi trường

Có năm loại môi trường theo TL-05: dev dùng chung (Docker Compose `mission-dev` trên server dev), test tích hợp, staging, perf và production theo cell. Agent chỉ được thao tác server dev qua Remote Control trên máy người dùng và chỉ trong project `mission-dev`. Các môi trường K8s thật (Viettel Cloud, cụm air-gapped) phụ thuộc người dùng cấp quyền; khi chưa có, task hạ tầng kiểm chứng bằng cụm kind/k3d cục bộ và ghi rõ giới hạn trong báo cáo.

## 4. Tiêu chí hoàn thành mặc định

Chart dựng được bằng `helm lint` và `helm template` không lỗi, cài được lên kind/k3d trong kiểm thử, các pod sẵn sàng, kiểm tra sức khoẻ qua. Thay đổi Terraform/OpenTofu (nếu có) phải `plan` sạch. Runbook vận hành viết bằng tiếng Việt, có bước kiểm chứng và bước quay lui. Agent **bắt buộc cập nhật trạng thái task vào tệp theo dõi tiến độ tổng hợp duy nhất `TIEN-DO-HE-THONG.md`** bằng lệnh `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> ...` (xem `AGENTS.md` quy tắc 12). Báo cáo theo `AGENTS.md` mục 6.

## Liên quan

- `AGENTS.md`, `TIEN-DO-HE-THONG.md`, `docs/quy-dinh/BACKEND.md`, `docs/KE-HOACH-TONG-THE-HE-THONG.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`
