# CNH-01 · ADR-05 Đề xuất bổ sung thiết kế cá nhân hoá theo người dùng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CNH-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | Không |
| Truy vết | ADR-05, NFR-11 → SCR-0002, SCR-1602, các màn hình danh sách của TL-02 → API đề xuất dưới `/api/v1/config` (chưa có trong hợp đồng) → MOD-08 → bảng đề xuất `config.user_preference`; tham chiếu có sẵn `config.dashboard_def`, `notify.channel_pref` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết một đề xuất bổ sung thiết kế đủ để người phụ trách duyệt và cấp mã, làm nền cho nhóm task cá nhân hoá: mỗi cán bộ lưu ở máy chủ bố cục trang chủ và dashboard của mình, cột và bộ lọc đã lưu trên các danh sách, và chế độ Đảng viên cao tuổi, để dùng lại trên mọi thiết bị. Người dùng đã đồng ý làm nhóm tính năng cá nhân hoá (quyết định ngày 20/10/2026, `docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 1a). Bộ TL hiện gần như chưa thiết kế phần này, nên task này là bước bổ sung thiết kế (màn hình, API, bảng `config.user_preference`) làm trước; không có đề xuất được người phụ trách duyệt thì CNH-02 đến CNH-05 không được bắt đầu.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` G1 mục "Pipeline token sang CSS variables và preset PrimeNG" (dòng 1648: chế độ cao tuổi "chọn theo tuỳ chọn người dùng lưu ở `config.tenant_setting` theo người hoặc theo nút chuyển nhanh"), đoạn kết luận lớp 2 (dòng 1502: màn hình nặng nhận "ẩn hiện cột" như cấu hình nhẹ).
- `TL-03-thiet-ke-csdl.md` "Bảng config.tenant_setting" (dòng 1481), "Bảng config.dashboard_def (bổ sung)" (dòng 1661), "Bảng notify.channel_pref" (dòng 3838, tiền lệ tuỳ chọn theo người dùng).
- `TL-02a-quy-trinh.md` "SCR-0002. Trang chủ" (dòng 426); `TL-02b-quy-trinh.md` "SCR-1602. Trình dựng dashboard" (dòng 1728), "SCR-1803" (dòng 2154), QT-18 bảng bước (bố cục trang chủ do AC-02 cấu hình cho tenant).
- `contracts/openapi/modules/config.yaml` (`/ui/layouts/home`), `platform.yaml` (`/analytics/dashboards`).
- `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md` làm mẫu cấu trúc đề xuất; `frontend/projects/design-system/src/lib/theme.service.ts` (hiện lưu chế độ cao tuổi ở `localStorage`).

## 3. Phạm vi công việc

- Phần cần bổ sung thiết kế trước khi làm (nêu rõ trong đề xuất): (a) bảng lưu tuỳ chọn theo người dùng, vì `config.tenant_setting` theo TL-03 chỉ có khoá `(tenant_id, setting_key, scope_unit_id)`, không có chiều người dùng, mâu thuẫn với câu "theo người" của TL-01; (b) API đọc ghi tuỳ chọn của chính mình và API bộ lọc đã lưu; (c) cách ghép bố cục cá nhân với bố cục tenant trong `getHomeLayout`; (d) mã SCR cho khung "Tuỳ chọn cá nhân" nếu là màn hình riêng, quy tắc BR và thông báo MSG; (e) dòng ma trận phân quyền phạm vi "Bản thân" trong `docs/phan-quyen/ma-tran.json`.
- Đề xuất mặc định (suy luận) cho bảng: `config.user_preference` với bảy cột chung, `user_id`, `pref_key` (ví dụ `ui.mode`, `home.layout`, `list.<màn hình>.columns`, `dashboard.default`), `value jsonb`, `value_schema_version`, UQ `(tenant_id, user_id, pref_key)`, RLS theo tenant và chủ bản ghi, phân loại C1, giới hạn 16 KB mỗi giá trị; bảng `config.saved_filter` (tên, màn hình, điều kiện lọc chỉ chứa mã và khoảng giá trị, không chứa văn bản tự do C2, tối đa 20 bộ mỗi màn hình, chỉ riêng tư ở giai đoạn đầu).
- Đề xuất mặc định cho API (suy luận): `GET /api/v1/config/me/preferences?prefix=`, `PUT` và `DELETE /api/v1/config/me/preferences/{key}` có `If-Match`; `GET`, `POST /api/v1/config/me/saved-filters?screen=`, `PUT`, `DELETE /api/v1/config/me/saved-filters/{id}`; không phát EVT, không ghi sổ cái (không phải dữ liệu pháp lý), có nhật ký truy cập thông thường.
- Đề xuất mặc định cho trang chủ: người dùng chỉ sắp xếp và ẩn widget trong tập widget tenant đã cấu hình cho vai trò của mình (lớp phủ thứ tự và ẩn, không phải cây SDUI riêng), máy chủ áp lớp phủ trong `getHomeLayout`; tenant đổi UI Pack thì widget mới hiện ở cuối, widget bị gỡ thì bỏ khỏi lớp phủ.
- Đề xuất mặc định cho dashboard: dùng lại `config.dashboard_def` với `share_scope = RIENG` và API `/analytics/dashboards` của DV-15-04; chỉ thêm tuỳ chọn `dashboard.default`.
- Đề xuất mặc định cho chế độ cao tuổi: thứ tự ưu tiên tuỳ chọn máy chủ của người dùng, rồi nút chuyển nhanh trong phiên, rồi mặc định của UI Pack; web và di động (SCR-1803) đọc chung khoá `ui.mode` và `ui.reducedSteps`.
- Liệt kê điểm cần người phụ trách quyết định (có cho chia sẻ bộ lọc trong đơn vị không, giới hạn số lượng, có cần SCR riêng không) và các mã cần cấp, không tự đặt mã.
- Sau khi được duyệt: ghi quyết định vào `wiki/14` và mở task sửa hợp đồng `config.yaml` (không sửa hợp đồng trong task này).

## 4. Ngoài phạm vi

- Viết mã máy chủ, mã web, migration (CNH-02 đến CNH-05).

## 5. Tệp được phép sửa

- `docs/de-xuat/de-xuat-ca-nhan-hoa.md` (tệp mới; vị trí suy luận theo mẫu `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (thêm câu hỏi mở về cá nhân hoá)

## 6. Tiêu chí hoàn thành

- Đề xuất có đủ: lý do, phần thiếu trong TL, phương án và đề xuất mặc định cho bảng, API, ghép bố cục, chế độ cao tuổi, phân quyền, bảo mật dữ liệu, giới hạn, điểm cần quyết định; mỗi nội dung suy luận được đánh dấu "(suy luận)", mỗi căn cứ trích đúng tệp và dòng TL.
- Không có mã SCR, BR, MSG, EVT mới nào được đặt; chỗ cần mã ghi "chờ cấp mã".
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
