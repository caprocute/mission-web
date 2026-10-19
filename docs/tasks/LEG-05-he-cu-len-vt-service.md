# LEG-05 · Đợt 0b/SEC-28 Đưa hệ cũ lên chart `vt-service`: probe, PDB, heap 70%, pool kết nối, ba replica

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b – nền vận hành tối thiểu (6 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách theo repo: xem cuối mục 3) |
| Phụ thuộc | LEG-01, INF-01 |
| Truy vết | C01, C02, C03, F01, B01, B02, G01, G02; SEC-28 (Kyverno chế độ Audit); PERF-07 (rút gọn); TL-04 "Đợt 0b"; TL-01 "Lộ trình chuyển đổi…", mục "Đợt 0b" |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Các dịch vụ của hệ hiện hành chạy trên Kubernetes bằng chart thư viện `vt-service`: có probe liveness, readiness, startup, PodDisruptionBudget, phân bố theo vùng, tối thiểu ba replica, heap 70–75% giới hạn bộ nhớ, pool kết nối 20–40 mỗi pod (giảm kết nối Oracle từ khoảng 5.000 xuống dưới 600), nhật ký JSON ra stdout và endpoint Prometheus. Điều kiện chuyển bước: rút một node trong giờ làm việc không gây gián đoạn quan sát được.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/HA-TANG.md`, `docs/quy-dinh/BACKEND.md`.
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: "Đợt 0b: nền vận hành tối thiểu (6 tuần)" (dòng 3899–3913: hạng mục, tiêu chí, cách đo), chương cấu hình triển khai từng service (probe, tài nguyên, heap), danh mục PERF (PERF-07).
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, "Lộ trình chuyển đổi theo Strangler Fig", mục "Đợt 0b: nền vận hành trên Kubernetes (6 tuần)".
- Kết quả INF-01 (chart `vt-service`), LEG-01 (sơ đồ hạ tầng, số kết nối Oracle).
- Đầu vào người dùng cần cấp:
  - Quyền vào namespace Kubernetes dành cho hệ cũ ở môi trường thử và production (hoặc quyết định dựng cụm mới), quyền đẩy ảnh lên Harbor.
  - Quyền ghi các repo hệ cũ (thêm Actuator, cấu hình Hikari, nhật ký JSON).
  - DBA Oracle theo dõi số kết nối và giới hạn phiên trong lúc chuyển.
  - Khung giờ chuyển đổi và phương án quay lui được duyệt.

## 3. Phạm vi công việc

- Thêm vào từng dịch vụ hệ cũ: Spring Boot Actuator với nhóm `liveness`, `readiness`, endpoint Prometheus (Micrometer), nhật ký JSON ra stdout, tắt nhật ký ra tệp trên đĩa node.
- Đặt JVM theo phần trăm bộ nhớ (`MaxRAMPercentage` 70–75) và pool Hikari 20–40 mỗi pod, có `connectionTimeout`, `maxLifetime` phù hợp Oracle; ghi bảng tính tổng kết nối theo số replica.
- Viết tệp giá trị cho chart `vt-service` cho từng dịch vụ: ba replica, PDB, phân bố vùng, tài nguyên requests/limits, probe ba loại, NetworkPolicy mặc định chặn rồi mở đúng luồng cần.
- Bật chính sách Kyverno ở chế độ Audit cho namespace hệ cũ (SEC-28) và ghi danh sách vi phạm còn lại.
- Dựng ảnh không root, hệ tệp gốc chỉ đọc nếu ứng dụng cho phép; ghi rõ ngoại lệ.
- Chạy thử PERF-07 rút gọn: tắt một node mỗi 10 phút trong 1 giờ dưới tải nền, đo lỗi 5xx.
- Gợi ý tách: mỗi repo hệ cũ một task con (LEG-05a, LEG-05b…), cộng một task con cho Kyverno Audit và PERF-07.

## 4. Ngoài phạm vi

- APISIX, OTel, Fluent Bit (LEG-06); Resilience4j, cổng chất lượng (LEG-07); chart `vt-service` gốc (INF-01).

## 5. Tệp được phép sửa

- Mã và cấu hình (Actuator, Hikari, Logback, Dockerfile) trong các repo hệ cũ (đường dẫn do người dùng cấp)
- `deploy/environments/<môi-trường>/legacy/**` (giá trị chart cho dịch vụ hệ cũ)
- `ops/runbooks/he-cu-tren-kubernetes.md`

## 6. Tiêu chí hoàn thành

- `helm lint` và `helm template` với các tệp giá trị mới không lỗi; `kubeconform` và Kyverno ở chế độ kiểm thử báo cáo đủ.
- Trên môi trường thử: mọi pod sẵn sàng, ba replica mỗi dịch vụ; tắt một node mỗi 10 phút trong 1 giờ cho 0 lỗi 5xx.
- Số kết nối Oracle đo được dưới 600 ở tải thường; số liệu trước và sau ghi trong báo cáo.
- Runbook có bước kiểm chứng và quay lui; báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
