# CORE-01-02 · QT-02/SCR-0201 `OkrApi` công bố và API đọc cây, bảng, bản đồ liên kết, tính tiến độ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-01-01 |
| Truy vết | QT-02 → SCR-0201 → API `GET /api/v1/okr/tree`, `/okr/objectives`, `/okr/objectives/{id}`, `/okr/key-results`, `/okr/key-results/{id}`, `/okr/alignment`, `/okr/links`, `/okr/bases`, `POST /okr/objectives/export` → MOD-01 (`okr::api`) → bảng `okr.objective`, `okr.key_result`, `okr.okr_link`, `okr.basis` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng khung lục giác của module `okr` (entity, repository, mapper, controller sinh từ hợp đồng) và toàn bộ phía đọc của SCR-0201: cây mục tiêu, dạng bảng, bản đồ liên kết dọc và ngang, chi tiết mục tiêu và KR, thư viện căn cứ. Đồng thời công bố interface `OkrApi` để MOD-02 kiểm KR khi giao việc và gợi ý dòng kế hoạch. Tiến độ KR và mục tiêu được tính theo đúng một công thức ở máy chủ để web, di động và bảng điều hành thấy cùng con số.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 "Đặc tả màn hình" SCR-0201 (L844–888), nhất là công thức tiến độ ở L858.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Cấu trúc gói Java theo module" (L389), "Giao tiếp giữa module" (L553), "API chính" (L809).
- `contracts/openapi/modules/okr.yaml`: `getOkrTree`, `listOkrObjectives`, `getOkrObjective`, `listOkrKeyResults`, `getOkrKeyResult`, `getOkrAlignment`, `listOkrLinks`, `listOkrBases`, `exportOkrObjectives`; `contracts/openapi/modules/jobs.yaml`.
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md` (SCR-0201), `wiki/06-quy-uoc-api.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0201 và module API `okr`).

## 3. Phạm vi công việc

- Sinh interface controller từ `okr.yaml` bằng openapi-generator (`interfaceOnly=true`) và viết lớp hiện thực trong `okr.infrastructure`; viết entity JPA và repository cho `objective`, `key_result`, `okr_link`, `basis`, luôn kèm điều kiện `period_bucket` để truy vấn chỉ chạm phân vùng kỳ.
- Hiện thực `getOkrTree` và `listOkrObjectives` với `view=tree|table|map`, phân trang con trỏ `limit ≤ 200`; API đọc một đối tượng trả `ETag` lấy từ `row_version`.
- Tính tiến độ KR = (hiện tại − đầu)/(chỉ tiêu − đầu) kẹp trong [0; 1], xử lý chiều `GIAM` và loại `CO_KHONG` (cách xử lý hai trường hợp này là suy luận, ghi rõ trong chú thích mã); tiến độ mục tiêu = trung bình có trọng số các KR; đặt trong lớp domain `ProgressCalculator` thuần, không phụ thuộc Spring.
- Hiện thực `getOkrAlignment` và `listOkrLinks` dựng đồ thị từ `okr.okr_link` (`PHAN_RA`, `DONG_GOP`, `NGANG`, `CAN_CU`), giới hạn ba tầng phân rã.
- Hiện thực `exportOkrObjectives` trả 202 kèm `Location` tới `/api/v1/jobs/{id}`; nếu SVC-11 chưa sẵn sàng, job kết thúc với lỗi RFC 9457 rõ nghĩa và báo cáo ghi lại.
- Công bố `vn.viettel.okrkpi.okr.api.OkrApi` tối thiểu gồm: lấy ảnh chụp KR theo id (trạng thái mục tiêu, kỳ, đơn vị, người sở hữu) và liệt kê KR thuộc mục tiêu `DA_DUYET` theo đơn vị và kỳ (CORE-02-05 dùng để gợi ý kế hoạch, CORE-02-14 dùng để kiểm KR khi giao); tên phương thức cụ thể là suy luận.
- Áp kiểm tra phạm vi qua lớp OPA dùng chung (đơn vị mình và cấp dưới; `ai_readonly` chỉ đọc) theo `wiki/15a`; không tự đặt danh sách vai trò trong mã.

## 4. Ngoài phạm vi

- API ghi mục tiêu, KR, liên kết (CORE-01-03); hàng chờ duyệt `listOkrReviews` và lịch sử `listOkrObjectiveHistory` (CORE-01-06).
- Sinh tệp DOCX/PDF thật của mẫu báo cáo (thuộc SVC-11).
- Danh sách nhiệm vụ theo KR `GET /api/v1/tasks?krId=` (thuộc MOD-02, CORE-02-18).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/pom.xml` (chỉ để khai báo bước sinh mã từ `okr.yaml` nếu task nền chưa khai báo)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, gồm `ApplicationModules.verify()` và ArchUnit (mô-đun khác chỉ truy cập `okr.api`).
- Kiểm thử đơn vị `ProgressCalculator` phủ chiều `TANG`, `GIAM`, `DAT`, giá trị vượt chỉ tiêu, KR chưa có check-in; độ phủ dòng lớp `domain` và `application` ≥ 80%.
- Kiểm thử hợp đồng so khớp phản hồi của chín operationId nêu trên với `okr.yaml`; `openapi-diff` không báo phá tương thích.
- Kiểm thử tích hợp Testcontainers: tenant khác không thấy mục tiêu; danh sách 2.000 mục tiêu phân trang con trỏ không trùng, không sót.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
