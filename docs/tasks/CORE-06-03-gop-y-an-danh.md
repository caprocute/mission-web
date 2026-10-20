# CORE-06-03 · SCR-1101 Góp ý mã hoá C2 và chế độ ẩn danh qua OPA

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | S |
| Phụ thuộc | CORE-06-02, NEN-015 |
| Truy vết | QT-11 bước 6–7 → SCR-1101 → API `GET/POST /api/v1/dossiers/meetings/{id}/comments` (`listDossierMeetingComments`, `createDossierMeetingComment`) → MOD-06 → `dossier.review_comment` (`comment_enc` C2) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Thành viên gửi góp ý cho bản kiểm điểm trước hội nghị, thư ký ghi ý kiến tại hội nghị; nội dung luôn mã hoá C2 ở tầng ứng dụng, và khi tenant bật chế độ ẩn danh thì người được kiểm điểm đọc được nội dung nhưng không thấy ai góp ý (BR-11-06). Việc này bảo vệ người góp ý và giữ nội dung kiểm điểm trong vòng người có thẩm quyền.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11: "Mô tả các bước" (dòng 539, bước 6–7), "Quy tắc nghiệp vụ" (dòng 579, BR-11-06), "SCR-1101. Hội nghị kiểm điểm" (dòng 627; chỉ thư ký ghi ý kiến tại hội nghị).
- TL-03 bảng `dossier.review_comment` (dòng 3005); "Mã hoá phong bì cho cột C2" (dòng 4148).
- `contracts/openapi/modules/dossiers.yaml`: `listDossierMeetingComments`, `createDossierMeetingComment`.
- `wiki/15-phan-quyen.md` (mức dữ liệu C2), `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-1101; bản trích `tl02b §QT-11 BE-Q11-03`.

## 3. Phạm vi công việc

- `createDossierMeetingComment`: `Idempotency-Key`; giai đoạn `TRUOC_HOI_NGHI` cho thành viên trong `participants_json` khi hội nghị `DA_GUI_DU_THAO`; giai đoạn `HOI_NGHI` chỉ thư ký (lỗi `DOSSIER-403-NOT-SECRETARY` theo hợp đồng); kiểm `kind` ∈ `UU_DIEM`, `HAN_CHE`, `KIEN_NGHI`.
- Mã hoá `comment_enc` bằng `FieldEncryptor` với DEK mục đích `GOP_Y_KIEM_DIEM`, AAD gồm tenant, bảng, cột, mã dòng; không ghi nội dung rõ vào nhật ký, sự kiện, bộ đệm.
- `listDossierMeetingComments`: giải mã cho người có quyền; chính sách Rego trả danh sách trường bị che, khi chế độ ẩn danh bật thì bỏ `commenterPersonId` với người được kiểm điểm; ghi nhật ký truy cập C2 mỗi lần đọc (CORE-00-05).
- Góp ý `HAN_CHE` có `remediation_due` được đánh dấu để CORE-06-04 tạo `remediation_item`.
- Không có API sửa hay xoá góp ý.

## 4. Ngoài phạm vi

- Thư viện mã hoá (NEN-015); tạo hạn chế và biên bản (CORE-06-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/dossier/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`
- `deploy/policies/` (chỉ quy tắc Rego che trường người góp ý và ca `opa test`)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `opa test` xanh.
- Kiểm thử: cột `comment_enc` không chứa văn bản rõ; bật ẩn danh thì người được kiểm điểm nhận nội dung không có người góp ý, chủ trì vẫn thấy; thành viên ghi giai đoạn `HOI_NGHI` bị 403.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
