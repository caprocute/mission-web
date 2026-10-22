# Bộ ca dùng chung của bộ kiểm tra quy tắc OKR (CORE-01-04)

> Nguồn: TL-02a QT-02 "Quy tắc nghiệp vụ" BR-02-01 đến BR-02-07, MSG-02-03 đến MSG-02-07; hợp đồng `contracts/openapi/modules/okr.yaml` (`OkrRule`, `OkrCheckResult`, `OkrPolicy`).

Tệp `okr-rules.json` giữ một bộ quy tắc CEL và một bộ ca để máy chủ (cel-java, `OkrRuleChecker` trong `backend/mission-core`) và trình duyệt (cel-js, `frontend/projects/fe-okr/src/app/okr-rules.ts`) cho cùng một kết quả. Khoá `rules` phải trùng bộ quy tắc mặc định của máy chủ (`OkrRuleChecker.DEFAULT_RULES`) và bộ quy tắc giả lập của web; khi Policy Pack đặt tham số `okr_rules`, máy chủ trả bộ đó trong `getOkrComposeContext` để trình duyệt chạy cùng.

Ca phạm vi mục tiêu cho sẵn biến `o.*` vì các biến này cần dữ liệu máy chủ (số mục tiêu trong kỳ, mục tiêu cấp trên đã duyệt chưa). Ca phạm vi KR cho dữ liệu KR thô; mỗi bên tự tính biến `kr.*` (từ mơ hồ, động từ nhiệm vụ, trường còn thiếu) rồi phải ra đúng `expectedVars` và đúng danh sách `violations`. Ca có `serverOnly: true` chỉ máy chủ chạy, trình duyệt bỏ qua.

Chạy phía máy chủ: `mvn -pl mission-core test -Dtest=OkrRuleCheckerTest` (trong `backend`). Phía web chưa có bộ chạy đọc tệp này; khi thêm, đặt cạnh `cel-golden-yaml.spec.ts` của `@vt/form-engine`.
