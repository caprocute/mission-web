# Chính sách Kyverno của cụm (INF-07)

Thư mục này chứa bộ chính sách Kyverno bắt buộc của TL-04 mục 9.3 "Bảo mật nền tảng Kubernetes" (SEC-26, SEC-28, SEC-38, SEC-49, SEC-55). Argo CD áp các tệp `vt-*.yaml` và `exceptions/*.yaml` (trừ tệp mẫu bắt đầu bằng `_`) lên mọi cụm; cụm hồ sơ `compact` áp thêm `ho-so/compact/`. Chính sách nhắm namespace theo nhãn `vt.io/security-zone` do chart `cell-base` gắn (`app`, `data`, `secure`, `edge`, `egress`); namespace hệ thống không mang nhãn này nên không bị chặn.

## Các chính sách

| Tệp | Chế độ | Nội dung |
|---|---|---|
| `vt-require-signed-images.yaml` | Enforce, timeout 15 s | Ảnh `harbor.vt.local/*` phải theo digest, có chữ ký cosign của khoá `hashivault://cosign-release` và chứng thực SLSA Provenance v1 với repo `https://git.vt.local/okr/*`, builder `https://jenkins.vt.local/okr-builder`. Bỏ qua Rekor và CT log vì pipeline ký trong mạng nội bộ với `--tlog-upload=false`. |
| `vt-pod-hardening.yaml` | Enforce | Bốn quy tắc: chỉ registry nội bộ theo digest; không root (UID ≥ 10000 ở pod hoặc container), hệ tệp gốc chỉ đọc, cấm leo quyền, drop ALL; có `resources` và probe ở vùng `app`; không gắn token ServiceAccount khi không cần. Kèm chính sách Audit `vt-probe-on-mgmt-port` kiểm probe nằm trên cổng quản trị (8081). |
| `vt-require-ha.yaml` | Enforce, `require-pdb` Audit | Workload có `replicas` thì ≥ 3 và có `topologySpreadConstraints` theo vùng với `DoNotSchedule`; quy tắc PDB ở Audit tới khi chart sinh PDB trước Deployment. |
| `vt-autoscaler-conflict.yaml` | Audit | Phát hiện workload vừa có HPA vừa có ScaledObject của KEDA (TL-04 mục 6.1, cách hiện thực là suy luận). |

Ngoại lệ đang có: `exceptions/quan-sat-node.yaml` (Fluent Bit và node-exporter cần đọc thư mục của node) và `ho-so/compact/mot-vung-su-co.yaml` (hồ sơ compact chỉ có một vùng sự cố nên không trải pod theo vùng được).

## Xin ngoại lệ

Ngoại lệ là một `PolicyException` có hạn, không bao giờ là sửa chính sách hay bỏ nhãn namespace. Quy trình như sau.

1. Chép `exceptions/_mau-ngoai-le.yaml` thành `exceptions/<tên-ngắn>.yaml`. Thu hẹp phạm vi tới mức nhỏ nhất: đúng quy tắc (ghi cả tên `autogen-<quy-tắc>` khi workload là Deployment, DaemonSet, StatefulSet), đúng namespace, đúng tên workload.
2. Điền đủ năm chú thích: `vt.io/ly-do` (vì sao không đáp ứng được và vì sao chưa sửa được), `vt.io/nguoi-xin`, `vt.io/nguoi-duyet`, `vt.io/het-han` (YYYY-MM-DD, tối đa 180 ngày kể từ ngày xin) và `vt.io/phieu`.
3. Mở merge request; người của nhóm bảo mật duyệt mới được gộp. `kiem-ngoai-le.py` chạy trong CI của INF-02 (`kiem-ba-mo-hinh.sh`) và làm đỏ build khi thiếu chú thích, sai định dạng ngày, hạn quá 180 ngày hoặc ngoại lệ đã hết hạn, nên ngoại lệ hết hạn buộc phải gia hạn có duyệt lại hoặc xoá.
4. Gia hạn là một merge request mới với lý do cập nhật; không sửa ngày lặng lẽ.

## Đưa chính sách mới vào

Chính sách hoặc quy tắc mới vào ở chế độ `Audit` ít nhất hai tuần trên mọi cụm. Trong thời gian đó, đội vận hành đọc PolicyReport, sửa chart hoặc xin ngoại lệ cho các vi phạm thật; chỉ khi báo cáo sạch mới đổi sang `Enforce` trong một merge request riêng ghi ngày bắt đầu Audit. Quy tắc tự động gỡ bỏ không có: chuyển ngược về Audit cũng cần merge request.

## Kiểm thử

- `kyverno test deploy/policies/tests`: tài nguyên đạt và không đạt cho từng quy tắc (Kyverno CLI 1.15.2).
- `python3 deploy/policies/kiem-ngoai-le.py`: kiểm hạn và chú thích của ngoại lệ.
- `deploy/charts/vt-platform/ci/kiem-ba-mo-hinh.sh`: áp toàn bộ chính sách (trừ kiểm chữ ký, cần registry) lên bản render của ba mô hình phân phối.
- `deploy/environments/kind/kiem-thu-chinh-sach.sh`: cài Kyverno 1.15 lên kind, ký và chứng thực ảnh bằng khoá cosign tạm trên registry cục bộ, khẳng định ảnh đã ký chạy được còn ảnh chưa ký, thẻ `latest`, pod chạy root, pod thiếu probe, chứng thực sai builder đều bị từ chối.

Lưu ý: Kyverno 1.15 chỉ đọc chữ ký và chứng thực cosign dạng thẻ `.sig`, `.att`; cosign 3 mặc định ghi định dạng bundle mới nên pipeline phải ký với `--new-bundle-format=false` (kiểm thử kind có ca đối chứng cho điều này).
