# QA-01 · TL-01 ch.18 Chiến lược kiểm thử toàn hệ thống và danh mục khoảng 40 hành trình đầu cuối

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-01` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b |
| Luồng đề xuất | B9 |
| Cỡ việc | S (≤ nửa ngày agent) |
| Phụ thuộc | – |
| Truy vết | TL-01 ch.18 "Tháp kiểm thử" → QT-00..QT-18 → SCR theo baseline mục 11 → TL-04 mục 7.4 (PERF), mục 9.8 (ZAP), ch.8 (DR) → tiêu chí J1–J9 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết chiến lược kiểm thử chung cho toàn hệ thống (web, backend, microservice, di động, hạ tầng) trên nền tháp kiểm thử của TL-01 chương 18, và chốt danh mục khoảng 40 hành trình đầu cuối có mã, tác nhân, QT, SCR, API và sự kiện cần kiểm. Khi xong, mỗi task QA-03..QA-16 và mỗi task phát triển biết tầng kiểm thử nào thuộc về mình, công cụ nào đã chốt, chạy ở môi trường nào, và danh mục hành trình là đầu vào trực tiếp cho QA-04..QA-07.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Tháp kiểm thử" (3124–3128), "Đường ống CI/CD" (3130–3138), "Trục J: Kiểm thử và chất lượng" (3355–3369).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Danh mục kịch bản thử tải PERF" (1743–1776), "Kiểm thử bảo mật: ASVS mức 2 và OWASP ZAP" (3173–3206), "Diễn tập khôi phục" (2431–2437).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 10 (AC), mục 11 (QT, SCR).
- `wiki/11-kiem-thu-va-chat-luong.md`, `docs/quy-dinh/BACKEND.md` mục 4, `docs/quy-dinh/HA-TANG.md` mục 4, `frontend/e2e/` (bộ Playwright ma trận phân quyền đã có).

## 3. Phạm vi công việc

- Viết chiến lược kiểm thử toàn hệ thống trong `wiki/11-kiem-thu-va-chat-luong.md` (mở rộng trang hiện có): bảng tầng kiểm thử × phân hệ (đơn vị, tích hợp Testcontainers, Modulith, ArchUnit, jqwik, PIT, Pact, Playwright, k6/Gatling, ZAP, diễn tập DR), công cụ đã chốt, ngưỡng (80% dòng, 70% nhánh mã mới, PIT 70%, ca vàng 100%), nơi chạy (CI, môi trường tạm theo nhánh, staging, CELL-PERF, server dev).
- Định nghĩa năm môi trường kiểm thử và dữ liệu dùng ở từng môi trường (không dữ liệu thật ngoài sản xuất, SEC-52), quy ước tài khoản thử theo vai trò AC-01..AC-10 khớp realm dev.
- Viết danh mục hành trình `frontend/e2e/hanh-trinh/danh-muc.json` (máy đọc được) và bảng tương ứng trong wiki: khoảng 40 hành trình, mỗi hành trình có mã `HT-xx` (mã cục bộ của bộ kiểm thử, không phải mã thiết kế), tên nghiệp vụ, tác nhân AC, QT, SCR, `operationId` chính trong `contracts/openapi/modules/`, EVT phải phát, task QA thực hiện (QA-04, QA-05, QA-06 hoặc QA-07).
- Phân bổ hành trình: QT-00..QT-03 cho QA-04; QT-04..QT-07 cho QA-05; QT-08..QT-10 cho QA-06; QT-11..QT-18 cho QA-07; hành trình di động SCR-0302, SCR-0504, SCR-0702, SCR-1803 đánh dấu thuộc bộ kiểm thử MOB.
- Ghi tiêu chí "hành trình đạt": chạy trên hệ thống thật (không giả lập API), không lỗi console, sự kiện EVT tương ứng xuất hiện trên Kafka, dữ liệu cuối đúng trạng thái chuẩn của baseline mục 9.1, chế độ người cao tuổi chạy được ở ít nhất một hành trình mỗi QT.
- Ghi bảng ánh xạ PERF-01..PERF-26 và DR-01..DR-16 sang QA-08..QA-15 để không sót mã nào.

## 4. Ngoài phạm vi

- Viết mã kiểm thử hành trình (QA-04..QA-07), kịch bản tải (QA-08..QA-11), Pact (QA-02).
- Kiểm thử đơn vị trong từng task phát triển (thuộc chính task đó).

## 5. Tệp được phép sửa

- `wiki/11-kiem-thu-va-chat-luong.md`, `frontend/e2e/hanh-trinh/danh-muc.json`, `frontend/e2e/hanh-trinh/README.md`.

## 6. Tiêu chí hoàn thành

- Danh mục có từ 36 đến 44 hành trình, phủ đủ QT-00..QT-18 (trừ phần chỉ có trên di động), mỗi `operationId` tồn tại thật trong `contracts/openapi/modules/` (kiểm bằng script nhỏ hoặc `grep`, kết quả ghi trong báo cáo).
- Bảng ánh xạ PERF và DR phủ đủ PERF-01..PERF-26, DR-01..DR-16, mỗi mã đúng một task QA.
- Wiki 11 có mục "Chiến lược kiểm thử toàn hệ thống" theo quy tắc cập nhật wiki (dòng "Nguồn", mục "Liên quan", đánh dấu suy luận).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
