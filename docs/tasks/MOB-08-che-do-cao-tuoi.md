# MOB-08 · QT-18/SCR-1803 Chế độ Đảng viên cao tuổi trên di động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | MOB-03 (bổ sung hiển nhiên: MOB-06 cho luồng tự đánh giá rút gọn, DL-06 cho bộ token "elder") |
| Truy vết | QT-18 → SCR-1803 (BR-18-09, MSG-18-09, MSG-18-10); NFR-11 → API `GET /api/v1/ui/packs/current` (`getCurrentUiPack`), `POST /api/v1/tasks/{id}/progress` (`updateTaskProgress`), `GET /api/v1/appraisals/profiles/{id}` (`getAppraisalProfile`), `PUT /api/v1/appraisals/profiles/{id}/self-assessment` (`saveAppraisalSelfAssessment`), `POST /api/v1/ai/speech-to-text` (`transcribeSpeech`) → MOD-08, MOD-02, MOD-04, SVC-14 → bảng `config.ui_pack_version` (`elder_mode`, `few_steps`), `appraisal.self_assessment`, `appraisal.general_criteria_result` → EVT-05 |
| Trạng thái | Chưa bắt đầu (lưu lựa chọn hiển thị theo từng người ở máy chủ đang chờ người dùng chốt; task này chỉ lưu trên máy) |

## 1. Mục tiêu

Đảng viên cao tuổi ở chi bộ khu dân cư làm được các việc thường ngày (xác nhận tham gia sinh hoạt, tự đánh giá cuối năm) trên điện thoại với chữ lớn, tương phản cao, một câu hỏi mỗi màn hình, nhập bằng giọng nói và không quá ba chạm từ trang chủ. Chế độ này không phải ứng dụng riêng: cùng dữ liệu, cùng API, chỉ khác bộ token và cách trình bày luồng (SCR-1803).

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`, đặc tả SCR-1803 "Chế độ Đảng viên cao tuổi (di động)" (dòng 2154: vùng 1–4, bảng trường, bảng chức năng, MSG-18-09, MSG-18-10) và QT-18 "Quy tắc nghiệp vụ" (BR-18-09).
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`, mục "Chế độ chữ lớn và người dùng cao tuổi".
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-18 mobile-app" (token chữ lớn, luồng rút gọn).
- Hợp đồng `contracts/openapi/modules/config.yaml`, `tasks.yaml`, `appraisals.yaml`, `platform.yaml` (`transcribeSpeech`); `wiki/09-hieu-nang-va-trai-nghiem.md`.

## 3. Phạm vi công việc

- Áp bộ token "elder" (chữ cơ sở 17–19 px, nút cao ≥ 48 px, viền 2 px, tương phản ≥ 7 : 1) từ UI Pack khi tenant bật hoặc khi người dùng bật trong "Tôi › Hiển thị"; lựa chọn của người dùng lưu trên máy, không thấp hơn 17 px.
- Trang chủ chế độ cao tuổi: tối đa 5 thẻ lớn, mỗi thẻ một việc và một nút chính chiếm toàn chiều ngang; thẻ "Tự đánh giá cuối năm" ghi số ngày còn lại và số bước; hai nút "Nói" và "Gọi hỗ trợ".
- "Đã tham gia" cho việc sinh hoạt chi bộ gửi `updateTaskProgress` (EVT-05) và hiện MSG-18-09; mất sóng thì xếp hàng qua MOB-02.
- Luồng tự đánh giá rút gọn khi cờ `few_steps` bật: một câu hỏi mỗi màn hình với hai lựa chọn dạng nút cao, ô ghi chú không bắt buộc, nút "Quay lại", "Tiếp tục" lớn; mỗi bước lưu vào nháp mã hoá và gọi `saveAppraisalSelfAssessment`; phiếu sinh ra cùng bản ghi `appraisal.self_assessment` như chế độ thường.
- "Nói": ghi âm, chuyển văn bản tiếng Việt (trên thiết bị hoặc `transcribeSpeech`), hiện MSG-18-10 để người dùng bấm "Đúng" hoặc "Nói lại" trước khi lưu.
- "Gọi hỗ trợ" mở trình gọi điện với số hỗ trợ của chi bộ hoặc tổng đài lấy từ cấu hình tenant.
- Đọc màn hình (TalkBack, VoiceOver) đọc đúng nhãn mọi nút; mọi màn hình chế độ thường của MOB-03..MOB-06 cũng hiển thị đúng khi bật bộ token "elder".

## 4. Ngoài phạm vi

- Lưu lựa chọn hiển thị theo từng người ở máy chủ (chờ người dùng chốt); soạn bộ token "elder" (DL-06).

## 5. Tệp được phép sửa

- `mobile/lib/features/elder/**`, `mobile/lib/core/theme/**`, `mobile/test/**`, `mobile/integration_test/**`, tệp ARB tiếng Việt trong `mobile/`

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh; `meetsGuideline` (tương phản, vùng chạm Android và iOS, nhãn) đạt cho mọi màn hình chế độ cao tuổi với ngưỡng tương phản 7 : 1.
- Đếm chạm từ trang chủ tới hoàn thành "Đã tham gia" và tới hoàn thành một bước tự đánh giá ≤ 3.
- Kiểm thử tích hợp với máy chủ giả lập: luồng ba bước tạo đúng một phiếu, MSG-18-09 và MSG-18-10 hiển thị đúng lúc.
- Chạy trên trình giả lập Android và iOS với cỡ chữ hệ thống lớn nhất, không vỡ bố cục (ghi rõ nền tảng chưa chạy được); báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
