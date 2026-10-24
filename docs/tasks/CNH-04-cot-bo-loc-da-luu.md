# CNH-04 · TL-02 quy ước danh sách Web: cột và bộ lọc đã lưu trên các danh sách

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CNH-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | W |
| Cỡ việc | M |
| Phụ thuộc | CNH-02 |
| Truy vết | Quy ước danh sách của TL-02 (phân trang con trỏ, lọc) → các màn hình danh sách (ví dụ SCR-0101, SCR-0503 "Việc của tôi", danh sách minh chứng của QT-07) → API tuỳ chọn cá nhân và bộ lọc đã lưu (CNH-02) → MOD-08 → `config.user_preference`, `config.saved_filter` (đề xuất) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Trên các danh sách dùng thường ngày, cán bộ chọn cột muốn xem, lưu bộ lọc hay dùng với một tên dễ nhớ và mở lại bằng một chạm, trên mọi máy. Khi xong, một cơ chế dùng chung gắn được vào bất kỳ danh sách nào, và ít nhất các danh sách chính của giao việc, minh chứng và quản trị tenant đã dùng.

## 2. Đầu vào bắt buộc đọc

- Đề xuất đã duyệt của CNH-01 và hợp đồng `config.yaml` đã bổ sung.
- `TL-02a-quy-trinh.md` phần quy ước chung về danh sách, phân trang và lọc ở đầu tài liệu; đặc tả các màn hình danh sách được chọn.
- `TL-01-thiet-ke-kien-truc.md` đoạn kết luận lớp 2 (dòng 1502: màn hình nặng chỉ nhận cấu hình nhẹ như ẩn hiện cột).
- `wiki/06-quy-uoc-api.md`, `wiki/08-bao-mat-frontend.md`, `wiki/09-hieu-nang-va-trai-nghiem.md`, `wiki/10-quy-uoc-ma-nguon.md`.
- `frontend/projects/fe-task/src/app/pages/my-tasks.page.ts`, `frontend/projects/fe-admin/src/app/pages/tenants.page.ts` (danh sách hiện có).

## 3. Phạm vi công việc

- Phần cần bổ sung thiết kế trước khi làm: TL-02 chưa đặc tả "cột tuỳ chọn" và "bộ lọc đã lưu" (thao tác, giới hạn, có chia sẻ không, MSG); hợp đồng chưa có API. Chỉ làm khi CNH-01 được duyệt và CNH-02 có trên máy chủ hoặc giả lập theo hợp đồng mới; danh sách màn hình áp dụng do người phụ trách chốt.
- Đề xuất mặc định (suy luận): mỗi màn hình danh sách khai báo danh mục cột (mã cột, nhãn, cột bắt buộc không ẩn được) và danh mục điều kiện lọc; thành phần dùng chung "Chọn cột" và "Bộ lọc đã lưu" (lưu, đổi tên, xoá, đặt mặc định) lưu qua API của CNH-02 với `If-Match`.
- Bộ lọc đã lưu chỉ chứa mã và khoảng giá trị theo danh mục điều kiện, không lưu văn bản tìm kiếm tự do; điều kiện không còn hợp lệ (cột bị bỏ, giá trị không còn) được bỏ qua kèm thông báo nhẹ.
- Trạng thái lọc phản ánh lên URL để chia sẻ đường dẫn trong nội bộ, nhưng quyền xem dữ liệu vẫn do máy chủ quyết định.
- Gắn cơ chế vào ít nhất ba danh sách: "Việc của tôi" (fe-task), danh sách minh chứng (fe-evidence), danh sách tenant (fe-admin); danh sách khác ghi trong báo cáo để làm tiếp.
- Đặt thành phần dùng chung ở thư viện phù hợp và dùng ở shell ít nhất một lần nếu remote cần (tránh NG0203).

## 4. Ngoài phạm vi

- Backend (CNH-02); chia sẻ bộ lọc cho đơn vị (chỉ làm nếu CNH-01 chốt cho phép).

## 5. Tệp được phép sửa

- `frontend/projects/api-client/src/**` (model, client, giả lập tuỳ chọn và bộ lọc)
- `frontend/projects/design-system/src/lib/**` (thành phần dùng chung "Chọn cột", "Bộ lọc đã lưu")
- `frontend/projects/fe-task/src/app/pages/my-tasks*`, `frontend/projects/fe-evidence/src/app/pages/evidence-list*`, `frontend/projects/fe-admin/src/app/pages/tenants*`
- `wiki/10-quy-uoc-ma-nguon.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; áp một bộ lọc đã lưu ≤ 3 chạm; axe-core không có lỗi mức serious.
- Không có dữ liệu C2 trong giá trị gửi lên API tuỳ chọn (kiểm thử đơn vị trên bộ chuyển đổi bộ lọc).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
