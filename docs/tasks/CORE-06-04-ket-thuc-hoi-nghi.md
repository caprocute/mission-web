# CORE-06-04 · SCR-1101 Kết thúc hội nghị: biểu quyết, sinh biên bản, mục khắc phục

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-06-03, DV-11-01 |
| Truy vết | QT-11 bước 7–9 → SCR-1101 → API `POST /api/v1/dossiers/meetings/{id}/minutes` (`createDossierMeetingMinutes`), `POST .../meetings/{id}/close` (`closeDossierMeeting`), `GET .../meetings/{id}/remediation-items`, `PATCH /api/v1/dossiers/remediation-items/{id}` → MOD-06 → `dossier.review_meeting`, `dossier.remediation_item`, `dossier.form_instance` → SVC-11, SVC-10 → nhận EVT-17, EVT-18 → phát EVT-11, EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Thư ký ghi điểm danh và biểu quyết, hệ thống yêu cầu SVC-11 sinh biên bản (202); chủ trì kết thúc hội nghị, khoá góp ý, tạo danh sách hạn chế cần khắc phục và gửi biên bản cho chủ trì, thư ký ký số. Kết quả biểu quyết chỉ ghi vào biên bản và Mẫu 3, không thay mức quyết định (BR-11-07); hạn chế được theo dõi sang kỳ sau (BR-11-08).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11: "Mô tả các bước" (dòng 539, bước 7–9), "Quy tắc nghiệp vụ" (dòng 579, BR-11-07, BR-11-08), "SCR-1101. Hội nghị kiểm điểm" (dòng 627, MSG-11-03; tổng phiếu ≤ số người có mặt).
- TL-03 bảng `review_meeting` (dòng 2985), `remediation_item` (dòng 3075), `form_instance` (dòng 3040).
- `contracts/openapi/modules/dossiers.yaml`: `createDossierMeetingMinutes` (lỗi `DOSSIER-403-NOT-SECRETARY`, `DOSSIER-409-INVALID-STATE`, `DOSSIER-422-VOTE-EXCEEDS-ATTENDEES`), `closeDossierMeeting` (`DOSSIER-403-NOT-CHAIR`, `DOSSIER-409-NO-VOTE`), `listDossierMeetingRemediationItems`, `updateDossierRemediationItem`.
- Bản trích `tl02b §QT-11 BE-Q11-04`.

## 3. Phạm vi công việc

- `createDossierMeetingMinutes`: chỉ thư ký, AAL2 theo hợp đồng; ghi `participants_json`, `vote_json`, `meeting_at`; tổng phiếu vượt số người có mặt trả 422; chuyển `DA_HOP`; gửi yêu cầu sinh biên bản tới SVC-11 (in cả lý do giữ ngày nếu có), trả 202 + `Location`.
- Consumer EVT-17 cho biên bản: lưu `minutes_object_key`, `minutes_sha256`, tạo `form_instance` loại biên bản (nếu TL-03 không có mã biểu mẫu biên bản thì chỉ lưu trên `review_meeting`, không tự đặt mã), chuyển `DA_LAP_BIEN_BAN`.
- `closeDossierMeeting`: chỉ chủ trì, cần đã có biểu quyết; khoá góp ý, tạo `remediation_item` (mã hoá `content_enc`) từ góp ý `HAN_CHE`, tạo yêu cầu ký biên bản cho chủ trì và thư ký (hạn 2 ngày làm việc), phát EVT-11 cho hồ sơ liên quan và EVT-21; phản hồi kèm MSG-11-03.
- Consumer EVT-18: chuyển `DA_KY` khi đủ hai chữ ký.
- `listDossierMeetingRemediationItems`, `updateDossierRemediationItem` (`If-Match`): theo dõi trạng thái `MO` → `DANG_KHAC_PHUC` → `DA_KHAC_PHUC`/`CHUA_KHAC_PHUC`; hạn chế chưa xong tự hiện ở hội nghị kỳ sau.
- Không ghi `vote_json` vào `appraisal.rating_proposal` (BR-11-07); kiểm bằng ArchUnit rằng MOD-06 không gọi lệnh ghi mức của MOD-04.

## 4. Ngoài phạm vi

- Mẫu biên bản và sinh PDF/A (DV-11-xx); dịch vụ ký (DV-10-xx).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/dossier/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka; mã lỗi khớp `dossiers.yaml`.
- Kiểm thử: biểu quyết 7 phiếu với 6 người có mặt bị 422; người không phải chủ trì kết thúc bị 403; EVT-18 trùng không làm sai trạng thái; hạn chế kỳ trước hiện ở hội nghị kỳ sau.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
