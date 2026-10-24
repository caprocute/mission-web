# CORE-05-10 · QT-15/EVT-13 Saga phát hành Policy Pack đa cell

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a. các bước saga phía MOD-05 và `policy_binding`; b. bù trừ, phát hành lại, thu hồi) |
| Phụ thuộc | CORE-05-09, DV-06-05; CORE-08-12 cho kiểm tương thích UI Pack (bổ sung so với khung) |
| Truy vết | QT-15 → SCR-1503 → `POST /api/v1/policies/packs/{id}/versions/{v}/approve` (khởi chạy), `POST .../republish` (`republishPolicyPackVersion`), `POST .../revoke` (`revokePolicyPackVersion`) → MOD-05, SVC-06 saga phát hành, SVC-07 → `policy.policy_binding`, `policy.policy_pack_version` (`published_at`) → EVT-13 `policy.pack.published.v1` → MSG-15-08; ADR-19, ADR-25; NFR-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đưa phiên bản đã ký tới trạng thái `DA_PHAT_HANH` một cách an toàn trên nhiều cell: saga ở SVC-06 gắn `policy_binding` với kỳ hiệu lực, đồng bộ gói từ vùng điều phối toàn cục xuống các cell, nạp vào scoring-service, kích hoạt `wf_binding`, có bù trừ khi một bước lỗi, rồi phát EVT-13. Khi xong, hướng dẫn mới áp dụng được trong ≤ 5 ngày làm việc (NFR-07), kỳ đã khoá tiếp tục dùng phiên bản cũ, và thu hồi hay phát hành lại sau lỗi đều có đường đi rõ ràng.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-15 "Mô tả các bước" (dòng 1315, ngày 4–5), "Trạng thái đối tượng" (dòng 1335), "Ngoại lệ và xử lý" (dòng 1372: thu hồi, áp phiên bản sửa cho kỳ đang mở), MSG-15-08.
- `TL-01-thiet-ke-kien-truc.md` "Phát hành, ký số và vòng đời" (dòng 2511), "Hiệu lực theo kỳ" (dòng 2517), "Quản trị phiên bản UI Pack và Policy Pack" (dòng 1799).
- Baseline danh mục EVT (EVT-13: bên nhận SVC-07, SVC-06, SVC-17, SVC-09).
- `contracts/openapi/modules/policies.yaml` `/approve`, `/republish`, `/revoke`; `contracts/events/avro/` lược đồ EVT-13.
- Thẻ DV-06-05 (khung saga tầng 3), CORE-08-12 (`UiPackCompatibilityApi`).

## 3. Phạm vi công việc

- Định nghĩa các bước saga phía MOD-05 dưới khung DV-06-05: (1) kiểm tương thích UI Pack hiện hành của tenant với dải gói mới qua `config.api.UiPackCompatibilityApi`, không tương thích thì dừng trước khi gắn (TL-01 X-G1-12); (2) ghi `policy_binding` cho kỳ hiệu lực và phạm vi đã gửi; (3) chờ xác nhận đồng bộ xuống cell và nạp ở scoring-service (bước của dịch vụ khác, MOD-05 chỉ phát lệnh và nhận kết quả); (4) kích hoạt `wf_binding`; (5) chuyển `DA_PHAT_HANH`, ghi `published_at`, chuyển phiên bản trước cùng phạm vi sang `NGUNG` khi phiên bản mới có hiệu lực, phát EVT-13 qua outbox.
- Bù trừ theo thứ tự ngược: gỡ `policy_binding` vừa ghi (khi chưa `is_frozen`), giữ `CHO_PHE_DUYET` có chữ ký; không xoá dữ liệu.
- `republishPolicyPackVersion`: chạy lại saga cho phiên bản đã ký nhưng saga đã bù trừ; không ký lại nếu `content_hash` không đổi.
- `revokePolicyPackVersion` (`If-Match`): chỉ trước kỳ hiệu lực; kỳ đã khoá (`is_frozen`) bị chặn; kỳ đã bắt đầu thì trả lỗi hướng dẫn phát hành phiên bản sửa (`POLICY-409-PERIOD-STARTED`); phiên bản chuyển `NGUNG`, phiên bản trước tiếp tục áp dụng.
- Khi phiên bản sửa áp cho kỳ đang mở: phát yêu cầu tính lại hồ sơ của kỳ đang mở cho scoring-service qua sự kiện (suy luận cơ chế; không đặt mã EVT mới, dùng EVT-13 làm tín hiệu nếu bên nhận đã thống nhất).

## 4. Ngoài phạm vi

- Khung saga, lưu trạng thái saga (DV-06-05); nạp gói ở scoring-service và kiểm chữ ký khi nạp (nhóm DV-07); đồng bộ vùng điều phối toàn cục xuống cell (nhóm DV/INF tương ứng).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `contracts/events/avro/**` (chỉ khi lược đồ EVT-13 chưa có; thêm mới, tương thích BACKWARD)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers (PostgreSQL, Kafka, saga giả lập) cho: phát hành thành công phát đúng một EVT-13; bước nạp lỗi thì bù trừ và giữ `CHO_PHE_DUYET`; phát hành lại không gọi ký; UI Pack không tương thích chặn trước khi gắn; thu hồi sau khi kỳ khoá bị chặn.
- Lược đồ EVT-13 đăng ký được với Apicurio chế độ BACKWARD (kiểm bằng Testcontainers hoặc công cụ kiểm lược đồ).
- Kiểm thử hợp đồng ba thao tác khớp `policies.yaml`.
- Đổi công thức không cần phát hành phần mềm: kiểm thử phát hành một phiên bản Policy Pack mới đổi công thức trên cùng bản build mission-core đang chạy, kỳ ghim phiên bản mới dùng công thức mới mà không đổi mã, không build lại, không triển khai lại.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
