# LEG-07 · Đợt 0c/SEC-23, SEC-37 Resilience4j cho lời gọi của hệ cũ và pipeline có cổng chất lượng cho bốn repo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c – chịu lỗi và cổng chất lượng (6 tuần) |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | LEG-05, INF-05 |
| Truy vết | F02, F03, F04, C04, J02, K03, A01 (một phần); SEC-23, SEC-28, SEC-37, SEC-38, SEC-39, SEC-49, SEC-55; TL-04 "Đợt 0c"; TL-01 "Lộ trình chuyển đổi…", mục "Đợt 0c" |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Mọi lời gọi ra ngoài của hệ cũ (vOffice, SSO, dịch vụ nội bộ khác) có timeout, circuit breaker, bulkhead và thử lại có giới hạn theo cấu hình chuẩn, executor có giới hạn; và cả bốn repo hệ cũ có pipeline Jenkins với cổng chất lượng chặn bản build có kiểm thử lỗi, bí mật, hay lỗ hổng mức cao, ảnh được ký và có SBOM. Điều kiện chuyển bước đợt 0c: pipeline chặn được 4/4 yêu cầu gộp cố ý sai.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: "Đợt 0c: chịu lỗi và cổng chất lượng (6 tuần)" (dòng 3915–3927), "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD", "Danh mục SEC và ánh xạ trục D".
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: chương "Độ tin cậy và khả năng chịu lỗi", mục "Cấu hình Resilience4j chuẩn" (dòng 2809), chương "Kiểm thử và tích hợp, triển khai liên tục".
- Kết quả INF-05 (thư viện `vt-pipeline`), NEN-016 (cấu hình Resilience4j chuẩn trong `vt-platform-starter`, nếu đã có để chép thông số).
- Đầu vào người dùng cần cấp:
  - Quyền ghi các repo hệ cũ và quyền tạo job Jenkins cho chúng.
  - Quyền đẩy ảnh vào Harbor và khoá cosign (qua OpenBao).
  - Danh sách hệ ngoài mà hệ cũ gọi và giới hạn chịu tải của từng hệ (để đặt timeout, bulkhead).

## 3. Phạm vi công việc

- Kiểm kê mọi lời gọi ra ngoài của hệ cũ (HTTP, SOAP, JDBC tới hệ khác) từ kết quả LEG-01; bọc bằng Resilience4j với thông số chuẩn (timeout, circuit breaker, bulkhead, thử lại có giới hạn), xuất chỉ số Resilience4j.
- Đặt timeout ở mọi tầng (client HTTP, JDBC `statement_timeout` tương đương phía Oracle, APISIX), thay thread pool không giới hạn bằng executor có giới hạn.
- Viết Jenkinsfile cho từng repo dùng `vt-pipeline`: build, kiểm thử đơn vị, độ phủ tệp thay đổi, SonarQube, Semgrep, gitleaks, Trivy, SBOM CycloneDX (SEC-23), ký ảnh cosign và chứng thực (SEC-37, SEC-38), ảnh không root theo digest (SEC-49, SEC-55).
- Chuyển chính sách Kyverno cho namespace hệ cũ từ Audit sang Enforce sau khi hết vi phạm (SEC-28, SEC-38).
- Tạo bốn yêu cầu gộp thử cố ý (kiểm thử lỗi, bí mật giả, CVE mức Critical, ảnh chưa ký) để chứng minh cổng chặn.
- Gợi ý tách: LEG-07a Resilience4j và timeout; LEG-07b pipeline cho từng repo; LEG-07c Kyverno Enforce.

## 4. Ngoài phạm vi

- Flyway và thử tải 500 phiên (LEG-08); thư viện `vt-pipeline` (INF-05).

## 5. Tệp được phép sửa

- Mã, cấu hình Resilience4j, Jenkinsfile trong các repo hệ cũ (đường dẫn do người dùng cấp)
- `deploy/policies/**` (chỉ ràng buộc áp cho namespace hệ cũ), `deploy/environments/<môi-trường>/legacy/**`
- `ops/runbooks/cong-chat-luong-he-cu.md`

## 6. Tiêu chí hoàn thành

- Pipeline của cả bốn repo chạy xanh trên nhánh chính; bốn yêu cầu gộp cố ý đều bị chặn (4/4).
- Chỉ số Resilience4j hiện cho 100% lời gọi ra ngoài đã kiểm kê; thử tắt một hệ ngoài thì circuit breaker mở và hệ cũ trả lỗi nhanh có kiểm soát.
- Kyverno Enforce bật cho namespace hệ cũ, không còn vi phạm.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
