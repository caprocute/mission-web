> Nguồn: TL-04 mục 3.7, 9.5 (SEC-21, SEC-57), mục 9.7; task INF-09, INF-10, INF-11. Ngày đánh giá: 23/10/2026.

# Bằng chứng 03: mTLS (SEC-21, SEC-57)

## Yêu cầu

Mọi kết nối giữa các dịch vụ trong vùng ứng dụng và vùng bảo mật dùng mTLS ở chế độ STRICT. Kết nối tới vùng dữ liệu dùng TLS gốc của từng hệ (PostgreSQL, Kafka, Valkey, OpenSearch) với kiểm chứng đầy đủ chứng thư và danh sách quyền (ACL). TL-04 yêu cầu bằng chứng là cấu hình PeerAuthentication và kiểm tra bắt gói.

## Biện pháp đã có

Chart `deploy/charts/cell-mesh/templates/peerauthentication.yaml` khai báo PeerAuthentication STRICT toàn cụm cho các namespace ambient. Chart từ chối render nếu giá trị `mtlsMode` khác `STRICT`, nên không thể vô tình hạ xuống PERMISSIVE bằng cách đổi values. Ngoại lệ duy nhất là cổng thu số liệu, được khai báo tường minh. Ở vùng dữ liệu, `deploy/charts/vt-platform/ha-tang/data-pg/templates/clusters.yaml` bật `hostssl` kèm SCRAM cho PostgreSQL. `deploy/charts/vt-platform/ha-tang/kafka/templates/users.yaml` khai báo người dùng Kafka xác thực bằng mTLS, với ACL theo topic. `deploy/charts/vt-platform/ha-tang/valkey/values.yaml` bật TLS và ACL theo người dùng. Chứng thư do cert-manager cấp từ PKI của OpenBao (SEC-59, `ops/runbooks/openbao-pki.md`).

Về kiểm thử, `deploy/environments/kind/kiem-thu-mang-cell.sh` kiểm trên cụm kind rằng pod ứng dụng dùng HBONE, tức mTLS của ztunnel (qua `istioctl ztunnel-config`). Script còn kiểm rằng lưu lượng không mTLS từ namespace ngoài mesh bị từ chối, và gọi thẳng IP pod để bỏ qua waypoint cũng bị từ chối. `deploy/environments/kind/kiem-thu-su-kien.sh` kiểm kết nối Kafka qua listener mTLS.

## Cách thu bằng chứng

Script `thu-bang-chung.sh` xuất các PeerAuthentication của cụm ra `peer-authentication.yaml` và kết quả `istioctl ztunnel-config workloads` ra `ztunnel-workloads.json`. Phần bắt gói được làm thủ công trong buổi kiểm tra, theo hướng dẫn của TL-04: bắt gói trên node và xác nhận không có tải tin HTTP dạng rõ giữa các pod.

## Trạng thái và điểm còn thiếu

Trạng thái: **Đáp ứng (giả lập)**. Cấu hình và kiểm thử trên kind đã có. Biên bản bắt gói trên cụm staging thật chưa có, vì chờ INF-21.
