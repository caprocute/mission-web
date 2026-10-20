# DV-04-02 · QT-01/SCR-0103 Đơn vị và quan hệ: truy vấn theo thời điểm hiệu lực và ghi nhận, cây con, chặn vòng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-04-01 |
| Truy vết | QT-01 → SCR-0103 → `GET /api/v1/org/units` (`listOrgUnits`), `GET /api/v1/org/units/tree` (`getOrgTree`), `GET /api/v1/org/units/search` (`searchOrgUnits`), `GET/PUT /api/v1/org/units/{id}` (`getOrgUnit`, `updateOrgUnit`), `GET /api/v1/org/units/{id}/subtree` (`getOrgSubtree`), `GET /api/v1/org/units/{id}/history` (`listOrgUnitHistory`), `GET /api/v1/org/subject-groups` (`listSubjectGroups`) → SVC-04 → bảng `org.org_unit`, `org.org_unit_relation`, `org.subject_group`; BR-01-06; MSG-01-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp API đọc và sửa đơn vị trên mô hình hai trục thời gian: xem cây tổ chức "tại ngày" hiệu lực bất kỳ và "như đã biết tại" thời điểm ghi nhận bất kỳ, lấy cây con, tìm đơn vị, xem lịch sử phiên bản; sửa đơn vị tạo phiên bản mới chứ không ghi đè (BR-01-06), không cho tạo vòng trong cây. Khi xong, SCR-0103 hiển thị được cây hiện hành và cây quá khứ ở chế độ chỉ đọc (MSG-01-08).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 SCR-0103 (dòng 656–698), BR-01-06, ràng buộc đơn vị cấp trên không tạo vòng (dòng 682), mã đơn vị duy nhất (dòng 674).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `org.org_unit`, `org.org_unit_relation` (dòng 1119–1157) và mục "Mẫu truy vấn" (dòng 4048–4076).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-04 org-service" (dòng 1028–1046).
- Hợp đồng: `contracts/openapi/modules/org.yaml` các thao tác ở bảng, tham số `asOf`, `recordedAsOf`.
- Wiki: `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0103 và API `org`), `wiki/06-quy-uoc-api.md`.

## 3. Phạm vi công việc

- Hiện thực các thao tác đọc ở bảng truy vết: mặc định thời điểm hiện tại; với `asOf` lọc theo `valid_period @> asOf`, với `recordedAsOf` lọc theo `recorded_period @> recordedAsOf`; cây và cây con bằng CTE đệ quy theo cây `DANG` hoặc `CHUYEN_MON`; phân trang con trỏ ≤ 200.
- Hiện thực `updateOrgUnit` (`If-Match` theo `row_version`, 409 khi lệch, Idempotency-Key): tạo phiên bản mới với `valid_from` theo quyết định và `recorded_from` = thời điểm ghi, gắn `org_change_order`; không có đường sửa tại chỗ.
- Chặn vòng: trước khi ghi quan hệ `TRUC_THUOC`, kiểm đơn vị cha mới không thuộc cây con của đơn vị tại mọi thời điểm hiệu lực chồng lấn; vi phạm trả 422 Problem Details.
- Kiểm mã đơn vị duy nhất trong tenant tại thời điểm hiệu lực và đơn vị thuộc đúng một khối của PL 2A.
- Phát EVT-14 `org.unit.changed.v1` qua outbox khi sửa đơn vị (topic compact, khoá `tenantId:unitId`).
- Áp ABAC theo ma trận: quản trị tenant sửa trong tenant mình; người dùng thường chỉ đọc theo phạm vi (gọi OPA qua thư viện nền).
- Kiểm thử: dữ liệu theo kịch bản V1/V2/V3 của TL-03; truy vấn `asOf` và `recordedAsOf` cho kết quả khác nhau đúng kỳ vọng; vòng bị chặn; 409 khi `If-Match` lệch; kiểm thử hợp đồng với `org.yaml`; đo p95 truy vấn cây hiện hành của đơn vị 10.000 nút ≤ 300 ms (NFR-03).
- Điểm lệch cần lưu ý: TL-01 ghi `GET /api/v1/org/units/{id}/tree?asOf=&knownAt=`, TL-02a ghi `GET /api/v1/org/units/tree?asOf=&recordedAsOf=`; hợp đồng dùng `/org/units/tree` với `asOf`, `recordedAsOf` cùng `/org/units/{id}/subtree`. Theo API-first dùng hợp đồng.

## 4. Ngoài phạm vi

- Sáp nhập, chia tách, đổi tên, giải thể (DV-04-03); phân công (DV-04-04); nhập Excel (DV-04-05).

## 5. Tệp được phép sửa

- `backend/services/org-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh gồm Testcontainers, kiểm thử hợp đồng và `openapi-diff` không phá tương thích; độ phủ dòng `domain`, `application` ≥ 80%.
- Số đo p95 truy vấn cây ghi trong báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
