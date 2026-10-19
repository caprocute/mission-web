# DV-01-03 · SVC-01 Danh sách tuyến công khai tường minh và kiểm thử CI quét CRD

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-01-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | S |
| Phụ thuộc | DV-01-01 |
| Truy vết | SVC-01 → khắc phục S03 (TL-01 dòng 921); NFR-08 (ASVS L2); tuyến không cần xác thực trong hợp đồng (`security: []`), ví dụ `/registry/resolve` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Bảo đảm không tuyến nào lọt ra ngoài mà không có xác thực, trừ một danh sách công khai được khai báo tường minh theo đúng cặp đường dẫn và phương thức. Khi xong, CI quét toàn bộ CRD `ApisixRoute` của chart và đỏ ngay khi có route thiếu plugin xác thực mà không nằm trong danh sách, khắc phục lỗi S03 của hệ hiện tại (để lộ API không xác thực).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-01 api-gateway", đoạn về S03 (dòng 921) và cấu hình `openid-connect` (dòng 946–953).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` (tra mã S03 bằng grep để hiểu vấn đề gốc).
- Hợp đồng: mọi thao tác trong `contracts/openapi/modules/*.yaml` có `security: []` hoặc chỉ `serviceClient`; `contracts/openapi/common/common.yaml` mục `securitySchemes`.
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/08-bao-mat-frontend.md`, `wiki/11-kiem-thu-va-chat-luong.md`.

## 3. Phạm vi công việc

- Viết kịch bản trích từ hợp đồng OpenAPI danh sách thao tác không yêu cầu token người dùng (ví dụ `GET /registry/resolve`, `/.well-known/*`, thông tin đăng nhập công khai của tenant) và sinh tệp `public-routes.yaml` (đường dẫn chính xác + phương thức).
- Khai báo route công khai trong chart chỉ từ tệp trên, khớp chính xác đường dẫn và phương thức, không dùng ký tự đại diện rộng.
- Viết kiểm thử CI (script chạy được cục bộ) dựng `helm template`, đọc mọi `ApisixRoute`, khẳng định mỗi route hoặc tham chiếu `ApisixPluginConfig protected-api` (có `openid-connect`) hoặc khớp đúng một dòng trong `public-routes.yaml`.
- Viết kiểm thử ngược: thêm tạm một route không xác thực vào fixture và chứng minh kịch bản báo lỗi với thông điệp chỉ rõ tên route.
- Kiểm tra thêm: route thao tác chỉ dành cho client nội bộ (`serviceClient`, ví dụ `POST /workflow/sagas/period-close`, `GET /appraisals/inputs`) không được công bố ra cổng ngoài, chỉ đi qua mạng nội bộ cell.
- Ghi quy ước thêm tuyến công khai (phải có duyệt bảo mật trong PR) vào `wiki/07-xac-thuc-phan-quyen.md`.

## 4. Ngoài phạm vi

- Cấu hình xác thực của từng route (DV-01-01); quét bảo mật ứng dụng (QA).

## 5. Tệp được phép sửa

- `deploy/charts/api-gateway/**` (thêm `public-routes.yaml`, `tests/`)
- `scripts/` (chỉ thêm kịch bản kiểm tra route nếu không đặt được trong chart)
- `wiki/07-xac-thuc-phan-quyen.md`

## 6. Tiêu chí hoàn thành

- Kịch bản kiểm tra chạy xanh trên chart hiện tại và đỏ trên fixture có route không xác thực.
- Danh sách công khai sinh lại từ hợp đồng không khác tệp đã commit (kiểm tra trôi lệch trong CI).
- Không thao tác `serviceClient` nào xuất hiện trong route ngoài.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
