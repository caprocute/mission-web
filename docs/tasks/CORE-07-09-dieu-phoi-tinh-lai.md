# CORE-07-09 · QT-13 Điều phối tính lại, sinh lại, ký lại sau EVT-22; hậu kiểm có bằng chứng sổ cái

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-07-08, CORE-08-06 (bổ sung đề xuất: DV-09-05 cho xuất bằng chứng sổ cái) |
| Truy vết | QT-13 bước 1, 9–13 → SCR-1301 → API `GET/POST /api/v1/oversight/post-audits`, `GET .../post-audits/{id}`, `POST .../post-audits/{id}/close`; `POST /api/v1/ledger/exports` (SVC-09) → MOD-07 → `oversight.reopen_request` (`DA_THUC_HIEN` → `DA_DONG`), `oversight.post_audit` → nhận EVT-22, EVT-10, EVT-17, EVT-18, EVT-09 (khoá lại) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Sau khi kỳ được mở lại trong phạm vi, MOD-07 theo dõi và điều phối phần còn lại: SVC-07 tính lại hồ sơ trong phạm vi bằng phiên bản Policy Pack gốc (≤ 15 phút), SVC-11 sinh lại Mẫu 2, Mẫu 3, người có thẩm quyền ký lại, MOD-08 khoá lại kỳ khi xong hoặc hết hạn; yêu cầu mở lại được đóng, khiếu nại hay cảnh báo gắn kèm được báo hoàn tất. Cơ quan kiểm tra mở và đóng đợt hậu kiểm với bằng chứng xuất từ sổ cái.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-13: "Mô tả các bước" (dòng 962, bước 1, 9–13 và đoạn về bước 10), "Trạng thái đối tượng" (dòng 984), BR-13-06..08 (dòng 999), "Ngoại lệ và xử lý" (dòng 1016: hết hạn mở thì giữ thay đổi, đánh dấu "chưa hoàn tất hậu kiểm").
- TL-03 bảng `oversight.reopen_request` (dòng 3144: `executed_at`, `relocked_at`, `DA_THUC_HIEN`, `DA_DONG`), `oversight.post_audit` (dòng 3181).
- `contracts/openapi/modules/oversight.yaml`: `listPostAudits`, `createPostAudit`, `getPostAudit`, `closePostAudit`, `createLedgerExport`.
- Bản trích `tl02b §QT-13 BE-Q13-06, 11`.

## 3. Phạm vi công việc

- Consumer EVT-22 (MOD-07 tự nhận lại để điều phối, hoặc xác nhận từ MOD-08): đặt `DA_THUC_HIEN`, `executed_at`; theo dõi danh sách hồ sơ trong phạm vi.
- Theo dõi hoàn tất qua EVT-10 (ảnh chụp mới `TINH_LAI_HAU_KIEM`, cùng `policy_version` gốc), EVT-17, EVT-18 cho Mẫu 2, Mẫu 3 của hồ sơ trong phạm vi; khi đủ thì yêu cầu MOD-08 khoá lại sớm qua cổng `api`; nhận EVT-09 khoá lại thì đặt `relocked_at`, chuyển `DA_DONG`, báo khiếu nại `DANG_THI_HANH` hoặc cảnh báo liên quan để đóng.
- Hết hạn mở mà chưa xong (MOD-08 tự khoá lại): giữ thay đổi đã làm, đánh dấu yêu cầu "chưa hoàn tất hậu kiểm" trong dữ liệu mô tả và phát EVT-21 cho người tạo.
- Không xoá, không ghi đè ảnh chụp hay biểu mẫu cũ (BR-13-08); kiểm bằng kiểm thử tích hợp rằng ảnh chụp cũ còn và có `superseded_by_id`.
- `createPostAudit`, `getPostAudit`, `listPostAudits`: AC-10 hoặc AC-07; `closePostAudit`: ghi kết luận, `findings_enc` C2, danh sách yêu cầu mở lại liên quan và mã tệp bằng chứng do `createLedgerExport` của SVC-09 sinh.
- Ghi sổ cái giá trị trước, sau, lý do, người làm, người duyệt cho mọi bước (BR-13-07).

## 4. Ngoài phạm vi

- Trạng thái kỳ, bộ đệm phạm vi và timer khoá lại (CORE-08-06); tính lại ở SVC-07 (DV-07-04); sinh và ký biểu mẫu (DV-11-xx, DV-10-xx); sổ cái (DV-09-xx).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `deploy/policies/` (chỉ quy tắc Rego cho hậu kiểm)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka.
- Kiểm thử luồng đầu cuối có bản giả SVC-07, SVC-11, SVC-10: EVT-22 → EVT-10 → EVT-17 → EVT-18 → EVT-09 đưa yêu cầu tới `DA_DONG`; sự kiện đến sai thứ tự hoặc trùng vẫn cho kết quả đúng; hết hạn giữa chừng đánh dấu "chưa hoàn tất hậu kiểm".
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
