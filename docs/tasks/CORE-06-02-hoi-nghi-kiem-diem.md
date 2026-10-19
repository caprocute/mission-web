# CORE-06-02 · QT-11/SCR-1101 Hội nghị kiểm điểm: tạo, thành phần, quyền thư ký và chủ trì, gửi dự thảo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-06-01, CORE-08-04 |
| Truy vết | QT-11 bước 1, 4, 5 → SCR-1101 → API `GET/POST /api/v1/dossiers/meetings`, `GET/PATCH .../meetings/{id}`, `POST .../meetings/{id}/send-draft` → MOD-06 → `dossier.review_meeting` → EVT-21 (MSG-11-02) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cơ quan tổ chức (AC-07) lập kế hoạch hội nghị kiểm điểm với chủ trì, thư ký, thành phần, ngày giờ; cán bộ gửi dự thảo bản kiểm điểm và hệ thống tự đếm số ngày làm việc tới hội nghị theo lịch nghỉ, cảnh báo khi chưa đủ 03 ngày (BR-11-01, MSG-11-01). Quy định "ít nhất 03 ngày làm việc" vì thế được kiểm bằng máy thay vì dựa vào sự cẩn thận của thư ký.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11: "Mô tả các bước" (dòng 539, bước 1, 4, 5 và ví dụ đếm ngày ngay sau bảng), "Quy tắc nghiệp vụ" (dòng 579, BR-11-01), "Ngoại lệ và xử lý" (dòng 613, giữ ngày có lý do), "SCR-1101. Hội nghị kiểm điểm" (dòng 627, MSG-11-01, MSG-11-02; chỉ thư ký ghi ý kiến, chỉ chủ trì kết thúc).
- TL-03 bảng `dossier.review_meeting` (dòng 2985).
- `contracts/openapi/modules/dossiers.yaml`: `listDossierMeetings`, `createDossierMeeting`, `getDossierMeeting`, `updateDossierMeeting`, `sendDossierMeetingDraft`.
- `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-1101; bản trích `tl02b §QT-11 BE-Q11-01, 02`.

## 3. Phạm vi công việc

- `createDossierMeeting`: `Idempotency-Key`, OPA cho AC-07 trong phạm vi đơn vị, trạng thái đầu `DU_KIEN`; khi tạo, gọi bộ phát hiện trường hợp đặc thù qua cổng nội bộ (CORE-06-05, trước khi có thì bỏ qua) và yêu cầu SVC-11 sinh dự thảo Mẫu 1A–1D (≤ 5 phút) qua `createDossierRenderJob`/cổng SVC-11.
- `updateDossierMeeting` (PATCH, `If-Match`): chỉ khi `DU_KIEN` hoặc `DA_GUI_DU_THAO`; dời ngày, địa điểm, thành phần, ghi lý do giữ ngày được cấp uỷ chấp thuận (lưu để in vào biên bản).
- `sendDossierMeetingDraft`: ghi `draft_sent_at`, đếm ngày làm việc trọn vẹn giữa thời điểm gửi và ngày hội nghị bằng thư viện của CORE-08-04 theo tham số `kiem_diem.cach_dem_ngay`; chưa đủ 03 ngày thì trả cảnh báo MSG-11-01 trong phản hồi, không chặn; chuyển `DA_GUI_DU_THAO`; phát EVT-21 mời góp ý (MSG-11-02).
- Lưu quyền thư ký và chủ trì theo `secretary_person_id`, `chair_person_id`; chính sách Rego dùng chung cho CORE-06-03, CORE-06-04.
- `listDossierMeetings`, `getDossierMeeting`: phân trang con trỏ, chỉ hội nghị trong phạm vi; hội nghị kỳ sau trả kèm hạn chế kỳ trước còn mở (BR-11-08).
- Kiểm thử đếm ngày với ví dụ của TL-02b (gửi 16:20 thứ Sáu 08/01/2027, họp thứ Năm 14/01/2027) và các ngày nghỉ, làm bù.

## 4. Ngoài phạm vi

- Góp ý (CORE-06-03); kết thúc hội nghị, biên bản (CORE-06-04); sinh Mẫu 1A–1D (DV-11-02).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/dossier/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`
- `deploy/policies/` (chỉ quy tắc Rego cho hội nghị kiểm điểm)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; phản hồi khớp `dossiers.yaml`; `opa test` xanh.
- Kiểm thử: kết quả đếm ngày đúng với ví dụ TL-02b; sửa hội nghị đã `DA_HOP` bị 409; người ngoài đơn vị bị 403.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
