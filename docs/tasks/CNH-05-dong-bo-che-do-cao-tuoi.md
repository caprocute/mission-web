# CNH-05 · NFR-11 Đồng bộ chế độ Đảng viên cao tuổi qua máy chủ trên web và di động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CNH-05` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (phần web); phần di động theo đợt 3 cùng ứng dụng SVC-18 |
| Luồng đề xuất | W |
| Cỡ việc | S |
| Phụ thuộc | CNH-02 |
| Truy vết | NFR-11, BR-18-09 → SCR-0002, SCR-1803 → API tuỳ chọn cá nhân (CNH-02), `GET /api/v1/ui/packs/current` (`modes.elderly`) → MOD-08 → `config.user_preference` (đề xuất) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Một cán bộ bật chế độ Đảng viên cao tuổi ở một thiết bị thì mọi thiết bị khác (trình duyệt khác, ứng dụng di động) cũng mở ở chế độ đó, thay cho việc hiện nay mỗi trình duyệt tự lưu trong `localStorage`. Khi xong, yêu cầu của TL-01 (chế độ chọn theo tuỳ chọn người dùng lưu phía máy chủ hoặc theo nút chuyển nhanh) được đáp ứng, và chế độ hiện ngay khi tải trang mà không nháy giao diện.

## 2. Đầu vào bắt buộc đọc

- Đề xuất đã duyệt của CNH-01 và hợp đồng `config.yaml` đã bổ sung.
- `TL-01-thiet-ke-kien-truc.md` G1 (dòng 1648: `data-mode="elderly"`, cờ `reducedSteps`, nút chuyển nhanh); mục "Flutter dùng chung schema" (chế độ cao tuổi trên di động).
- `TL-02b-quy-trinh.md` "SCR-1803. Chế độ Đảng viên cao tuổi (di động)" (dòng 2154) và BR-18-09.
- `frontend/projects/design-system/src/lib/theme.service.ts`, `frontend/projects/sdui-renderer/src/lib/ui-pack-loader.ts`.

## 3. Phạm vi công việc

- Phần cần bổ sung thiết kế trước khi làm: khoá tuỳ chọn, thứ tự ưu tiên giữa tuỳ chọn máy chủ, nút chuyển nhanh và mặc định UI Pack, và API chưa có trong TL; chỉ làm khi CNH-01 được duyệt. Điểm lệch cần lưu ý: TL-01 nói lưu ở `config.tenant_setting` "theo người", nhưng TL-03 không có chiều người dùng trong bảng đó; theo quyết định của CNH-01.
- Đề xuất mặc định (suy luận): `ThemeService` đọc `ui.mode`, `ui.reducedSteps` từ máy chủ sau đăng nhập; trước khi có kết quả dùng giá trị đệm cục bộ để không nháy (giá trị C1, cho phép lưu cục bộ); nút chuyển nhanh trên thanh trên ghi ngay vào máy chủ có `If-Match`, lỗi mạng thì giữ trong phiên và thử lại.
- Thứ tự ưu tiên đề xuất: tuỳ chọn máy chủ của người dùng, rồi lựa chọn trong phiên, rồi mặc định UI Pack của tenant.
- Di động: ứng dụng Flutter đọc cùng khoá, đổi `textScaler` và bảng màu theo `modes.elderly`; nếu ứng dụng di động chưa có trong repo thì chỉ ghi yêu cầu cho task MOB tương ứng.
- Kiểm thử: bật ở trình duyệt A, mở trình duyệt B thấy chế độ cao tuổi sau khi đăng nhập; tắt tuỳ chọn máy chủ thì về mặc định UI Pack.

## 4. Ngoài phạm vi

- Backend (CNH-02); thiết kế bộ token cao tuổi (UI Pack, CORE-08-10).

## 5. Tệp được phép sửa

- `frontend/projects/design-system/src/lib/theme.service.ts` và kiểm thử đi kèm
- `frontend/projects/fe-shell/src/app/**` (nút chuyển nhanh, nạp tuỳ chọn khi khởi động)
- `frontend/projects/api-client/src/**` (client, giả lập tuỳ chọn)

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console; không có nháy giao diện khi tải lại ở chế độ cao tuổi.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; chữ ≥ 17 px, vùng chạm ≥ 48 px, tương phản ≥ 7 : 1 (BR-18-09); axe-core không có lỗi mức serious.
- Nếu đụng di động: `flutter test` xanh.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
