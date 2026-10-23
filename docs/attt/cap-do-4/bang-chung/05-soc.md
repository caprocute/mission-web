> Nguồn: TL-04 mục 9.7 "SOC và SIEM (SEC-43)", mục 10.3; task INF-19, INF-24, DV-02-05. Ngày đánh giá: 23/10/2026.

# Bằng chứng 05: SOC 24/7 và SIEM (SEC-43)

## Yêu cầu

Đơn vị SOC giám sát hệ thống 24/7 qua SIEM, nhận các luồng nhật ký an ninh và phản hồi sự cố mức Critical trong tối đa 15 phút. Mọi luồng nhật ký an ninh được chuẩn hoá theo ECS và gửi tới SIEM qua topic Kafka `sys.siem.v1`, với kết nối mTLS một chiều ra ngoài. Các luồng này gồm nhật ký kiểm toán API Kubernetes, sự kiện Keycloak, quyết định từ chối của OPA, cảnh báo Falco, nhật ký WAF, nhật ký OpenBao, phiên PAM và sự kiện `sys.audit.v1`. TL-04 yêu cầu bằng chứng là hợp đồng dịch vụ và báo cáo SOC hằng tháng.

## Biện pháp đã có

Phía nguồn nhật ký đã có phần lớn. Sự kiện xác thực của Keycloak được đẩy ra Kafka (`backend/services/iam/keycloak-events/src/main/java/vn/viettel/okrkpi/iam/events/KafkaSink.java`, DV-02-05). Sự kiện kiểm toán của ứng dụng được ghi qua `backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/audit/DefaultAuditLogger.java` vào `sys.audit.v1`. Dịch vụ `audit-archiver` (`services-go/audit-archiver/cmd/audit-archiver/main.go`) lưu luồng kiểm toán vào kho bất biến 10 năm và kiểm tra hằng ngày; cảnh báo của dịch vụ này nằm ở `deploy/charts/audit-archiver/templates/canh-bao.yaml`. Danh mục topic `deploy/charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml` đã khai báo consumer `siem-forwarder`. Bộ cảnh báo tối thiểu (`deploy/charts/observability-cell/rules/canh-bao-toi-thieu.yaml`) đã gồm một số quy tắc phát hiện mà TL-04 nêu, như ảnh không ký bị từ chối và sự kiện Falco.

## Trạng thái và điểm còn thiếu

Trạng thái: **Một phần**. Còn bốn điểm thiếu. Một là dịch vụ chuyển tiếp `siem-forwarder` chuẩn hoá ECS và đẩy ra SIEM chưa có. Hai là nhật ký kiểm toán API Kubernetes và Falco chưa được triển khai (INF-19 đang làm). Ba là bộ quy tắc phát hiện chưa được thống nhất với SOC. Bốn là hợp đồng dịch vụ SOC chưa có, vì đây là việc mua sắm của đơn vị chủ quản. Xem [danh sách thiếu hụt](../thieu-hut.md).
