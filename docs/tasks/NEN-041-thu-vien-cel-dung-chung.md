# NEN-041 · ADR-19 Thư viện CEL dùng chung `vt-cel` (cel-java): môi trường, hàm mở rộng, bộ ca kiểm thử chung với cel-js

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-041` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a) môi trường CEL, kiểm kiểu, đệm `Program`, hàm `sum`; b) `ngay_lam_viec`, `min_muc` + bộ ca chung hai engine) |
| Phụ thuộc | NEN-005 |
| Truy vết | ADR-19 → TL-01 "Ngôn ngữ biểu thức CEL và bảng quyết định DMN", "Biểu mẫu động: Formly, JSON Schema và CEL" → BR-09-06 (`min_muc`), BR-15-03 (`ngay_lam_viec`, `config.holiday_calendar`) → MOD-05, MOD-08, SVC-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo thư viện CEL phía máy chủ dùng chung cho MOD-05 (kiểm kiểu quy tắc Policy Pack), MOD-08 (kiểm biểu mẫu động `x-cel` khi nhận dữ liệu) và SVC-07 (tính điểm), để mọi nơi biên dịch và đánh giá biểu thức theo cùng một môi trường, cùng bộ hàm mở rộng, cùng quy tắc tất định. Cùng lúc lập bộ ca kiểm thử chung mà cả cel-java (máy chủ, nguồn sự thật) và cel-js (trình duyệt, phản hồi nhanh) đều chạy, để hai engine không bao giờ cho kết quả khác nhau trên cùng dữ liệu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Policy Pack": "Cấu trúc gói" (L2386–2446, `definitions` CEL, `rounding`), "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (L2448–2480: bộ mở rộng `math`, `lists`, hàm `sum()`, biên dịch một lần, cấm hàm phụ thuộc thời gian/ngẫu nhiên), "Bộ ca mẫu vàng" (L2482–2509); mục "Biểu mẫu động: Formly, JSON Schema và CEL" (L1729–1751); ADR-19 (L3572).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` BR-09-06 (L96, `min_muc`), BR-15-03 (L1359, `ngay_lam_viec`), mục QT-15 đặc tả SCR-1502, SCR-1503.
- `frontend/projects/form-engine/src/lib/cel-rule-evaluator.ts`, `golden.ts`, `golden.spec.ts`, `src/samples/mau-1b.golden.json` (định dạng bộ test vàng phía web, cel-js 0.8); `wiki/03-giao-dien-theo-tenant.md` (định dạng test vàng).

## 3. Phạm vi công việc

- Tạo mô-đun `backend/libs/vt-cel/` dùng cel-java: `CelEnvironmentFactory` khai báo biến theo kiểu (từ `inputs` của Policy Pack hoặc `x-cel` của biểu mẫu), bật bộ mở rộng `math`, `lists`, `strings` cần thiết; không cung cấp hàm thời gian hiện tại hay ngẫu nhiên.
- `CelCompiler` biên dịch và kiểm kiểu một lần, trả lỗi có vị trí (dòng, cột, tên biến chưa khai báo, kiểu sai) để API soạn quy tắc trả 422 (`POLICY-422-CEL-TYPE` trong hợp đồng); `ProgramCache` đệm `Program` theo `(pack_id, version, rule_key)` hoặc `(form_code, version)`.
- Hàm mở rộng: `sum(list)` (cộng `double`/`decimal`, danh sách rỗng = 0); `ngay_lam_viec(tu, den)` đếm ngày làm việc qua giao diện `WorkingCalendar` do ứng dụng cung cấp (MOD-08 hiện thực trên `config.holiday_calendar` ở CORE-08-04, thư viện chỉ định nghĩa giao diện và bản giả cho kiểm thử); `min_muc(a, b)` trả mức thấp hơn theo thứ tự `HTXSNV > HTTNV > HTNV > KHTNV`.
- Quy tắc số: làm tròn cuối theo `rounding` của gói (`scale`, `HALF_UP`) bằng `BigDecimal` ở lớp gọi, thư viện cung cấp tiện ích; ghi rõ khác biệt số thực giữa cel-java và cel-js (JavaScript chỉ có `double`) và cách bộ ca chung xử lý (so sánh sau làm tròn).
- Bộ ca chung `data/golden/cel/*.yaml`: mỗi ca gồm biểu thức, khai báo biến, dữ liệu vào, kết quả mong đợi hoặc lỗi mong đợi; tối thiểu phủ toán tử cơ bản, macro danh sách, `sum`, `min_muc`, `ngay_lam_viec` (với lịch giả cố định), biên 59,995 và 89,995 của TL-01 "Bộ ca mẫu vàng", thiếu biến.
- Bộ chạy hai phía: kiểm thử JUnit trong `vt-cel` đọc toàn bộ `data/golden/cel`; một kiểm thử Vitest mới trong `frontend/projects/form-engine` đọc cùng thư mục và chạy bằng cel-js với cùng hàm mở rộng (bổ sung hàm vào `CelFunctions` cho kiểm thử); ca nào một engine không hỗ trợ phải đánh dấu `serverOnly` có lý do.
- Kiểm thử thuộc tính jqwik cho `sum` và `min_muc` (giao hoán, phần tử trung hoà, `min_muc(x, x) = x`).
- Điểm lệch cần lưu ý: TL-02b BR-09-06 đặt `min_muc` trong bảng DMN (FEEL) của SCR-1502, còn khung danh mục đặt vào môi trường CEL; task hiện thực trong CEL và ghi vào báo cáo rằng phía DMN cần hàm FEEL tương đương ở task scoring.

## 4. Ngoài phạm vi

- API quy tắc Policy Pack, test run 202, đối chiếu cel-js của gói: nhóm CORE-05; bộ kiểm JSON Schema có `x-cel`: CORE-08-14; DMN và tính điểm: nhóm DV-07.
- Hiện thực `WorkingCalendar` trên CSDL: CORE-08-04.

## 5. Tệp được phép sửa

- `backend/libs/vt-cel/**` (mới), `backend/libs/pom.xml`, `backend/pom.xml` (chỉ thêm mô-đun), `backend/platform-bom/pom.xml` (chỉ thêm cel-java, jqwik nếu chưa có)
- `data/golden/cel/**` (mới)
- `frontend/projects/form-engine/src/lib/*.spec.ts` (chỉ thêm kiểm thử đọc bộ ca chung; không sửa mã chạy)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-cel -am verify` chạy xanh, độ phủ dòng ≥ 80%.
- `cd frontend && npx ng test form-engine --watch=false` (hoặc lệnh kiểm thử đơn vị hiện hành của thư viện) chạy xanh với bộ ca chung; `npx ng build fe-task && npx ng build fe-shell` vẫn xanh theo AGENTS.md quy tắc 11.
- Hai engine cho cùng kết quả trên 100% ca không đánh dấu `serverOnly`; báo cáo liệt kê số ca và các ca `serverOnly` kèm lý do.
- Đo: đánh giá một `Program` đã đệm trên một hồ sơ mẫu dưới 100 µs (TL-01: vài chục µs/hồ sơ), báo số đo thật.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật `wiki/03-giao-dien-theo-tenant.md` mục test vàng nếu định dạng bộ ca chung khác định dạng hiện có.

## 7. Báo cáo

Theo AGENTS.md mục 6.
