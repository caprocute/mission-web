# CORE-04-09 · SCR-0903 Tra cứu vết tính điểm, điểm theo luồng và xuất XLSX

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | S |
| Phụ thuộc | CORE-04-02, DV-07-03 |
| Truy vết | QT-09 → SCR-0903 → API `GET /api/v1/appraisals/profiles/{id}/scores` (`getAppraisalScores`), `GET .../profiles/{id}/score-trace` (`getAppraisalScoreTrace`), `POST /api/v1/appraisals/exports` → MOD-04 → `appraisal.score_snapshot`, `appraisal.rating_explanation`; đọc `scoring.score_trace` qua SVC-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Chủ hồ sơ, người thẩm định và người quyết định xem được điểm, mức hệ thống tính, câu giải thích hạ mức và vết tính điểm theo từng luồng (`DANG`, `NHA_NUOC`), kèm phiên bản Policy Pack đã dùng (MSG-09-10). Phiếu xếp loại có giải thích là căn cứ để cán bộ hiểu kết quả và là điều kiện cho khiếu nại có cơ sở.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09: BR-09-10, BR-09-11 (dòng 87), "SCR-0903. Phiếu xếp loại có giải thích" (dòng 233, MSG-09-10).
- TL-03 bảng `score_snapshot` (dòng 2849: `rule_checks`, `downgrade_reasons`, `group_scores_json`, `detail_json`), "Schema scoring" (dòng 3316, `score_trace`).
- `contracts/openapi/modules/appraisals.yaml`: `getAppraisalScores`, `getAppraisalScoreTrace`, `exportAppraisalProfiles`.
- Bản trích `tl02b §QT-09 BE-Q09-13`.

## 3. Phạm vi công việc

- `getAppraisalScores`: trả ảnh chụp hiện hành theo `flow` (tham số `flow`), điểm nhóm, mức tự động, câu giải thích từ `rating_explanation`/`downgrade_reasons`, mã gói và phiên bản Policy Pack; luồng `NHA_NUOC` trả riêng 30/70 (BR-09-11).
- `getAppraisalScoreTrace`: đọc vết qua cổng của SVC-07 (hoặc bảng đọc được cấp quyền, chọn theo DV-07-03 và ghi rõ), phân trang con trỏ, chỉ trả mã quy tắc, căn cứ pháp lý, đầu vào và đầu ra ngắn.
- Kiểm OPA: chỉ chủ hồ sơ, người thẩm định, người đề xuất, người quyết định, `inspector` trong phạm vi được đọc; mỗi lần đọc nội dung C2 ghi nhật ký truy cập (CORE-00-05).
- Bổ sung vào tác vụ xuất XLSX của CORE-04-05 các cột điểm và mức theo luồng, không có trường C2.
- Bộ đệm phản hồi đọc theo `snapshot_hash` để giảm tải trong ngày công bố kết quả (suy luận); không lưu C2 vào Valkey (ADR-18).

## 4. Ngoài phạm vi

- Tính điểm và sinh vết (DV-07-03, DV-07-05); tính thử (DV-07-08, CORE-07-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; phản hồi khớp `appraisals.yaml`.
- Kiểm thử: người ngoài phạm vi bị 403; hồ sơ có hai luồng trả đúng hai ảnh chụp; p95 đọc điểm ≤ 300 ms (NFR-03).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
