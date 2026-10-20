# NEN-008 · TL-01 AsyncAPI 3.0 cho các topic công bố, lint trong CI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-008` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | S |
| Phụ thuộc | NEN-007 |
| Truy vết | ADR-03, ADR-20 → TL-01 "AsyncAPI" (tiêu chí L10) → SVC-13 integration-gateway (CloudEvents 1.0 JSON ra ngoài) → EVT-04, EVT-12, EVT-19 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có tài liệu AsyncAPI 3.0 mô tả các topic hệ thống công bố cho bên thứ ba qua SVC-13, sinh từ lược đồ Avro của NEN-007 và bổ sung mô tả nghiệp vụ tiếng Việt, được lint tự động. Đây là hợp đồng mà đối tác (CSDL CBCCVC, hệ điều hành văn bản, nơi nhận bản neo sổ cái) dựa vào; nó phải có phiên bản và chính sách tương thích ngược như OpenAPI.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Phong bì sự kiện" (L1285–1315: thuộc tính CloudEvents khi ra ngoài, ví dụ EVT-12 chia sẻ lên CSDL CBCCVC, mTLS + JWS) và "AsyncAPI" (L1317–1350: trích đoạn kênh EVT-04); mục SVC-13 (L1170–1182: sự kiện SVC-13 nhận để chia sẻ ra ngoài).
- `contracts/events/catalog.yaml`, `contracts/events/avro/**` (kết quả NEN-007).
- `contracts/package.json`, `contracts/openapi/README.md` (cách tổ chức hợp đồng hiện có).

## 3. Phạm vi công việc

- Tạo `contracts/asyncapi/mission-events.asyncapi.yaml` (AsyncAPI 3.0.0) với `info.title` tiếng Việt, `info.version` dạng semver, mô tả chính sách tương thích (chỉ thêm trường tuỳ chọn; thay đổi phá vỡ tạo topic `.v2` chạy song song ít nhất một quý).
- Khai báo kênh và thao tác `receive` cho các topic công bố: EVT-04 `task.task.assigned.v1` (theo đúng trích đoạn TL-01), EVT-12 `appraisal.rating.decided.v1`, EVT-19 `ledger.seal.created.v1`; danh sách này là suy luận từ TL-01 (SVC-13 nhận EVT-12, EVT-19 và ví dụ EVT-04), ghi "(suy luận)" và để người phụ trách bổ sung.
- Thông điệp dùng `contentType: application/cloudevents+json`; định nghĩa trait chung cho thuộc tính CloudEvents (`specversion`, `id`, `source` dạng `urn:vt-okrkpi:<cell>:<module>`, `type` dạng `vn.viettel.okrkpi.<topic>`, `subject`, `time`, `datacontenttype`, `dataschema`) và phần mở rộng `traceparent`, `tenantid`, `classification`, `policyversion`.
- Payload JSON Schema của từng thông điệp sinh hoặc chép tay từ `.avsc` tương ứng, chỉ giữ trường được phép ra ngoài; mã nội bộ được thay bằng mã ngoài theo TL-01 (ví dụ `subjectRef.scheme=cbccvc`) khi TL ghi rõ.
- Viết script `contracts/asyncapi/check.mjs` (hoặc dùng `@asyncapi/cli validate`) và thêm lệnh `npm run asyncapi:lint` vào `contracts/package.json`; nếu thêm phụ thuộc npm mới thì ghi lý do theo AGENTS.md quy tắc 1.
- Thêm kiểm tra chéo: mọi kênh trong AsyncAPI có topic tồn tại trong `catalog.yaml` và trường payload là tập con trường Avro (kiểm bằng script Node trong cùng thư mục).

## 4. Ngoài phạm vi

- Chuyển đổi CloudEvents và ký JWS thật trong integration-gateway: nhóm DV-13.
- Cổng tài liệu đối tác: chưa có task riêng, ghi vào báo cáo nếu cần.

## 5. Tệp được phép sửa

- `contracts/asyncapi/**` (mới)
- `contracts/package.json`, `contracts/package-lock.json` (chỉ thêm script và phụ thuộc lint)
- `contracts/events/README.md` (thêm liên kết tới AsyncAPI)

## 6. Tiêu chí hoàn thành

- `cd contracts && npm ci && npm run asyncapi:lint` chạy xanh; `npm run lint` của OpenAPI vẫn xanh.
- Kiểm thử âm: thêm vào payload một trường không có trong `.avsc` thì kiểm tra chéo báo lỗi.
- Trích đoạn kênh EVT-04 khớp TL-01 L1321–1350 (tên kênh, `address`, trường bắt buộc).
- Báo cáo cuối task theo AGENTS.md mục 6, nêu danh sách topic công bố đã chọn và lý do để người phụ trách xác nhận.

## 7. Báo cáo

Theo AGENTS.md mục 6.
