# MOB-03 · QT-05/SCR-0504 Việc của tôi và cập nhật tiến độ trên di động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | MOB-02, CORE-02-19 (bổ sung hiển nhiên: CORE-02-18 cho danh sách "Việc của tôi") |
| Truy vết | QT-05, QT-06 → SCR-0504 (BR-06-02, MSG-05-13, MSG-05-14) → API `GET /api/v1/tasks/mine` (`listMyTasks`), `GET /api/v1/tasks/{id}` (`getTask`), `POST /api/v1/tasks/{id}/accept` (`acceptTask`), `POST /api/v1/tasks/{id}/progress` (`updateTaskProgress`, Idempotency-Key), `POST /api/v1/tasks/{id}/blockers` (`reportTaskBlocker`) → MOD-02 → bảng `task.task`, `task.task_progress` (`channel` = `MOBILE`), `task.task_blocker` → EVT-05, EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cán bộ (AC-06) xem việc của mình và cập nhật tiến độ trên điện thoại trong tối đa ba chạm, kể cả khi mất sóng: thẻ nhiệm vụ có hạn, tiến độ, tình trạng; màn hình cập nhật có thanh trượt bước 5%, ghi chú, báo điểm nghẽn và nút sang nộp minh chứng. Cập nhật từ di động hiện trong nhật ký tiến độ trên máy tính với nhãn "(di động)".

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: QT-05 đặc tả SCR-0504 "Việc của tôi trên di động" (dòng 1626: bảng trường, bảng chức năng, MSG-05-13, MSG-05-14) và SCR-0503 (ràng buộc thẻ nhiệm vụ); QT-06 "Quy tắc nghiệp vụ" (BR-06-02 không giảm tiến độ trừ khi có ghi chú) và đặc tả SCR-0601; mục "Thiết bị di động".
- Hợp đồng `contracts/openapi/modules/tasks.yaml` (`listMyTasks`, `getTask`, `acceptTask`, `updateTaskProgress`, `reportTaskBlocker`, lược đồ `ProgressChannel`).
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0504, vai trò `member`).

## 3. Phạm vi công việc

- Dựng tab "Việc": danh sách con trỏ từ `listMyTasks` với nút lọc nhanh, thẻ nhiệm vụ (tên rút gọn, mã ngắn, hạn, tiến độ, tình trạng màu kèm nhãn chữ, không dựa riêng vào màu), sắp theo hạn; lưu bản sao ngoại tuyến qua MOB-02.
- Dựng màn hình cập nhật tiến độ: thanh trượt bước 5% từ giá trị hiện tại, ô ghi chú ≤ 1.000 ký tự (có nút nói để nhập nếu thiết bị hỗ trợ), lựa chọn báo điểm nghẽn với nội dung ≤ 500 ký tự bắt buộc khi chọn Có.
- Kiểm nhanh ở máy BR-06-02 (giảm tiến độ phải có ghi chú) chỉ để phản hồi sớm; máy chủ vẫn là nơi quyết định và lỗi máy chủ được hiển thị nguyên nghĩa.
- Gửi `updateTaskProgress` với `channel = MOBILE` và `Idempotency-Key`, gửi `reportTaskBlocker` cùng lần lưu; khi mất sóng thì xếp hàng qua MOB-02 và báo đã lưu trên máy; thành công hiện MSG-05-13.
- Cho nhận việc (`acceptTask`) với nhiệm vụ `DA_GIAO`; nút "Nộp minh chứng" mở SCR-0702 (MOB-05) khi nhiệm vụ đang thực hiện.
- Dựng thẻ tóm tắt trên tab "Hôm nay" (việc đến hạn trong tuần) dẫn vào màn hình cập nhật, bảo đảm ≤ 3 chạm từ trang chủ (NFR-11).
- Các màn hình chỉ có trên máy tính (lập kế hoạch, nghiệm thu…) mở ở chế độ chỉ đọc theo TL-02a; ẩn hiện thao tác theo ma trận phân quyền.

## 4. Ngoài phạm vi

- Đề nghị gia hạn, điều chỉnh (chỉ trên máy tính theo TL-02a); nộp minh chứng (MOB-05); chế độ cao tuổi (MOB-08).

## 5. Tệp được phép sửa

- `mobile/lib/features/tasks/**`, `mobile/lib/features/home/**`, `mobile/test/**`, `mobile/integration_test/**`, tệp ARB tiếng Việt trong `mobile/`

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh (widget test cho danh sách, thanh trượt, ràng buộc ghi chú khi giảm tiến độ, điểm nghẽn bắt buộc nội dung).
- Kiểm thử tích hợp với máy chủ giả lập theo hợp đồng: lưu tiến độ gửi đúng thân và header; mất mạng rồi có mạng gửi đúng một lần.
- Kiểm tra khả năng truy cập bằng `meetsGuideline` (tương phản chữ, vùng chạm, nhãn) cho hai màn hình; đếm chạm từ trang chủ tới lưu tiến độ ≤ 3.
- Chạy trên trình giả lập Android và iOS (ghi rõ nền tảng chưa chạy được); báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
