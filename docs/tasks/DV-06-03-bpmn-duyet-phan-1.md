# DV-06-03 · ADR-04 Mẫu BPMN luồng duyệt phần 1: OKR, kế hoạch quý, đề nghị gia hạn; DMN chọn cấp duyệt, kiểm thử và linter

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-06-02; CORE-00-03 (`org_replica`) để phân giải người duyệt (suy luận) |
| Truy vết | QT-02 (mẫu `duyet-okr`), QT-04 (mẫu `duyet-ke-hoach-quy`), QT-06 (mẫu `duyet-de-nghi`) → SVC-06 → bảng `flowable.wf_template`, `flowable.wf_binding`, `org_replica.*` (đọc) → gọi lại API lõi như `POST /api/v1/plans/{id}/versions/{v}/approve`, `POST /api/v1/okr/objectives/{id}/approve`; ADR-04 tầng 2; BR-15-11, BR-15-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có ba mẫu luồng duyệt đầu tiên chạy được trên Flowable theo nguyên tắc "mẫu + tham số", không nhân bản theo tenant: duyệt OKR, duyệt kế hoạch quý, duyệt đề nghị gia hạn hoặc điều chỉnh nhiệm vụ. Mỗi mẫu dùng DMN `chon-cap-duyet` để tính số cấp và vai trò duyệt theo khối, loại hồ sơ, người đứng đầu; người duyệt cụ thể được phân giải từ cây tổ chức tại thời điểm khởi tạo; bước máy gọi lại API lõi qua external worker có Idempotency-Key. Khi xong, mỗi mẫu có Flowable test, test DMN, mô tả, chủ sở hữu, và linter "chống mạng nhện" chạy trong CI.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương G2: "Thiết kế ba tầng" (dòng 1877–1889), "Ví dụ BPMN rút gọn: duyệt hồ sơ đánh giá" (dòng 1897–1953, làm khuôn), "Ví dụ DMN chọn cấp duyệt" (dòng 1955–2019), "Bảng tham số theo tenant" (dòng 2021), "Quy tắc quản trị chống mạng nhện" (dòng 2149–2153).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 (mẫu `duyet-okr`, dòng 779, 953), QT-04 (mẫu `duyet-ke-hoach`, dòng 1385), QT-06 (mẫu `duyet-de-nghi`, dòng 1849).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` SCR-1504 (dòng 1541–1555, danh sách mẫu và tham số).
- Hợp đồng: `contracts/openapi/modules/okr.yaml`, `tasks.yaml` (thao tác duyệt mà external worker gọi), `platform.yaml` (hộp việc).
- Wiki: `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15-phan-quyen.md` (tách vai trò), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Viết DMN `chon-cap-duyet` hit policy `COLLECT` với đầu vào `khoi`, `loaiHoSo`, `laNguoiDungDau` và đầu ra `thuTu`, `role`, `scope`, `hanNgayLamViec` theo khuôn TL-01, có test DMN cho bốn quy tắc mẫu.
- Viết listener phân giải: sắp theo `thuTu`, phân giải `scope` (`DON_VI_TRUC_TIEP`, `CAP_UY_CO_THAM_QUYEN`, `CAP_TREN_TRUC_TIEP`, `QUAN_LY_TRUC_TIEP`) bằng `org_replica` tại thời điểm khởi tạo, đổi hạn ngày làm việc theo lịch nghỉ tenant, tạo biến `approvalLevels` (biến chỉ mang id, tenant, khối, luồng, phiên bản Policy Pack).
- Viết ba mẫu BPMN: user task đa thể hiện tuần tự trên `approvalLevels`, `candidateGroups="${tenantId}:${level.role}:${level.orgUnitId}"`, kết thúc sớm khi trả lại, timer biên không ngắt `${remindAfter}` tới external worker `notify-remind`, external worker gọi lại API duyệt hoặc trả lại của lõi; tham số đọc từ `wf_binding` (thiếu dòng dùng mặc định khối).
- Khởi tạo tiến trình theo yêu cầu từ module lõi (sự kiện qua Kafka có inbox hoặc API nội bộ, chọn và ghi rõ), business key = id đối tượng, idempotent theo business key.
- Viết linter 8 quy tắc của TL-01 (cấm định nghĩa gắn `tenantId`; ≤ 1 tầng call activity; không gọi chéo tenant, không signal phát rộng; không service task đồng bộ; biến < 4 KB; đa thể hiện song song ≤ 20; mỗi mẫu có test + test DMN + mô tả + chủ sở hữu; ≤ 3 phiên bản chính còn chạy) và chạy trong `mvn verify`.
- Kiểm thử Flowable cho mỗi mẫu: duyệt đủ cấp, trả lại ở cấp giữa, quá hạn nhắc, đổi người duyệt khi điều động là uỷ quyền có lưu vết.
- Điểm lệch cần lưu ý: TL-01 đặt tên mẫu ví dụ `tpl-appraisal-review`, TL-02 và hợp đồng dùng `duyet-okr`, `duyet-ke-hoach-quy`, `duyet-de-nghi`, `duyet-ho-so-danh-gia`; dùng tên của TL-02 và hợp đồng. TL-02a QT-04 ghi `duyet-ke-hoach`, SCR-1504 ghi `duyet-ke-hoach-quy` v2; dùng `duyet-ke-hoach-quy` như hợp đồng `policies.yaml`.
- Gợi ý tách: (a) DMN + listener phân giải + linter; (b) ba mẫu BPMN và kiểm thử.

## 4. Ngoài phạm vi

- Mẫu hồ sơ đánh giá, khiếu nại, mở lại kỳ, phát hành Policy Pack (DV-06-04).
- Logic duyệt trong mission-core (CORE-01-05 và các task CORE-02 tương ứng).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**` (`src/main/resources/processes/`, `dmn/`, listener, worker, linter, kiểm thử)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Flowable test, test DMN và linter; linter đỏ trên fixture vi phạm từng quy tắc.
- Không định nghĩa nào gắn `tenantId`; số định nghĩa BPMN không tăng theo số tenant (kiểm bằng kiểm thử triển khai với ba tenant).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
