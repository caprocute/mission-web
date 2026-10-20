# DV-16-02 · SCR-0002/SVC-16 API tìm kiếm toàn cục, kiểm quyền lại ở lõi trước khi trả kết quả

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-16-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-16-01, CORE-00-02; NEN-014 (OPA, đánh giá từng phần) và NEN-013 (phân trang con trỏ) (bổ sung) |
| Truy vết | QT-00 → SCR-0002 (ô tìm kiếm trang chủ) → API `GET /api/v1/search` (`searchFullText`) → SVC-16, kiểm quyền lại ở MOD-02 và nguồn văn bản chỉ đạo → chỉ mục OpenSearch của DV-16-01 → ADR-15 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực `searchFullText`: tìm trên OpenSearch qua bí danh của tenant người gọi, loại khỏi kết quả mọi mục người gọi không có quyền xem bằng cách kiểm quyền lại ở lõi, trả đoạn trích văn bản thuần kèm vị trí khớp (`highlights`), không trả HTML và không trả tổng số bản ghi. Khi xong, ô tìm kiếm trên SCR-0002 trả kết quả đúng phạm vi, kể cả khi chỉ mục chậm hơn quyền thực tế.

## 2. Đầu vào bắt buộc đọc

- `contracts/openapi/modules/platform.yaml` operation `searchFullText` (`/search`: `q` 2–200 ký tự, `type`, `periodId`, `unitId`, `limit`, `cursor`), schema `SearchResultPage`, `SearchResult`, `SearchResultType`.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "SVC-16 search" (L1206: "Kết quả tìm kiếm được kiểm quyền lại ở lõi trước khi hiển thị chi tiết"), ADR-15 (L3532).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Đánh chỉ mục tìm kiếm OpenSearch" (L4351).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` SCR-0002 (L426).
- `wiki/15a-ma-tran-phan-quyen.md` mục module search; `wiki/08-bao-mat-frontend.md` (không trả HTML).

## 3. Phạm vi công việc

- Hiện thực `searchFullText` trong `backend/services/search/` theo giao diện sinh từ hợp đồng: kiểm `q` (2–200 ký tự), chọn chỉ mục hoặc bí danh theo tenant trong JWT (không bao giờ theo tham số), lọc `type`, `periodId`, `unitId`.
- Trả `snippet` văn bản thuần và `highlights` dạng `{start, length}` tính trên chuỗi đã trả; tắt highlighter HTML của OpenSearch hoặc chuyển thẻ thành vị trí, không để thẻ nào lọt ra phản hồi.
- Kiểm quyền lại ở lõi: với mỗi trang ứng viên, gọi OPA đánh giá từng phần (NEN-014) hoặc API kiểm quyền theo lô của mission-core bằng token người gọi, loại mục ngoài phạm vi trước khi trả; nếu loại bớt làm trang thiếu, lấy thêm ứng viên (tối đa số vòng có giới hạn, suy luận 3).
- Phân trang con trỏ `limit ≤ 200` dựa trên `search_after`; phản hồi không có trường tổng số; `nextCursor` mã hoá không lộ cấu trúc chỉ mục.
- Giới hạn tần suất theo người dùng (429) theo cấu hình APISIX hoặc thư viện nền, nhật ký không ghi nguyên văn `q` nếu có thể chứa dữ liệu cá nhân (ghi băm, suy luận).
- Điểm lệch cần lưu ý: hợp đồng chưa có API kiểm quyền theo lô của mission-core cho SVC-16 dùng; task ưu tiên đánh giá OPA từng phần với dữ liệu thuộc tính có trong tài liệu chỉ mục (đơn vị, người giao, người nhận) rồi kiểm lại chi tiết khi người dùng mở mục; nếu cần API lô thì đề xuất bổ sung hợp đồng, không tự thêm. Phụ thuộc khung CORE-00-02 (cấu trúc module) không trực tiếp cung cấp năng lực này.

## 4. Ngoài phạm vi

- Indexer và chỉ mục (DV-16-01); ô tìm kiếm trên giao diện (WEB); tìm kiếm cơ cấu tổ chức (`searchOrgUnits` thuộc DV-04).

## 5. Tệp được phép sửa

- `backend/services/search/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/search -am verify` xanh: Testcontainers OpenSearch + Keycloak + OPA, so khớp phản hồi với `platform.yaml`, `openapi-diff` không phá tương thích.
- Kiểm thử: người không có quyền với một nhiệm vụ có trong chỉ mục không nhận nhiệm vụ đó; tenant A không nhận kết quả tenant B dù gửi `unitId` của B; phản hồi không chứa ký tự `<` từ highlighter; không có trường tổng số; `limit=201` bị 400.
- p95 thời gian phản hồi ghi lại ở tải thử cục bộ (mục tiêu suy luận ≤ 300 ms).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
