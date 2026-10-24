# CORE-08-07 · MOD-08 Cấu hình tenant và cờ tính năng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-08-01 |
| Truy vết | QT-18 (SCR-1801), QT-14 (SCR-1401) → `GET /api/v1/config/feature-flags` (`listFeatureFlags`), `PUT /api/v1/config/feature-flags/{key}` (`setFeatureFlag`), `GET/PUT /api/v1/config/anomaly-thresholds` (`getAnomalyThresholds`, `updateAnomalyThresholds`) → MOD-08 → `config.tenant_setting`, `config.feature_flag` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp kho cấu hình theo tenant và cờ tính năng của MOD-08 cùng cổng nội bộ để các module hỏi "cờ này có bật cho tenant, đơn vị này không" và "giá trị cấu hình này là gì". Khi xong, quản trị tenant bật tắt tính năng theo tenant đúng hợp đồng, việc phát hành mã tách khỏi việc bật tính năng theo OPS-04, và các module đọc được cấu hình theo phạm vi tenant hoặc đơn vị.

## 2. Đầu vào bắt buộc đọc

- `TL-03-thiet-ke-csdl.md` "Bảng config.tenant_setting" (dòng 1481) và "Bảng config.feature_flag" (dòng 1586).
- `TL-01-thiet-ke-kien-truc.md` "Chuyên đề G1", mục "Bảo mật: chống XSS và CSP" (dòng 1759: `frame-ancestors` lấy từ `config.tenant_setting`).
- `TL-04-trien-khai-van-hanh.md` mục "GitOps và phát hành theo đợt cell" (dòng 3616, OPS-04 bật tính năng theo tenant bằng cờ).
- `contracts/openapi/modules/config.yaml` đường dẫn `/config/feature-flags`, `/config/feature-flags/{key}`, `/config/anomaly-thresholds`.
- Bản trích `extract/tl01-thanh-phan.md` mục B8.

## 3. Phạm vi công việc

- Hiện thực `listFeatureFlags` (phân trang con trỏ) và `setFeatureFlag` (bắt buộc `If-Match`, mã cờ lạ trả 404 vì cờ do nền tảng khai báo sẵn), có ghi sổ cái qua outbox.
- Định nghĩa danh mục cờ nền tảng bằng tệp seed (khoá, mô tả, mặc định) và migration seed lặp lại được cho tenant mới; cấu trúc `rollout` jsonb gồm danh sách đơn vị và tỷ lệ phần trăm theo băm người dùng (suy luận định dạng, ghi vào wiki).
- Công bố `config.api.FeatureFlagApi.isEnabled(tenant, flagKey, context)` xét `enabled`, `valid_from/to`, `rollout`; có đệm ngắn hạn.
- Công bố `config.api.TenantSettingApi.get(tenant, key, unit)` ưu tiên giá trị theo đơn vị rồi toàn tenant, kiểm `value` theo `value_schema`; hiện thực `getAnomalyThresholds`, `updateAnomalyThresholds` lưu phiên bản ngưỡng mới có lý do (BR-14-09) trên cùng bảng.
- Điểm lệch cần lưu ý: hợp đồng chỉ cho `tenant_admin` đọc cờ, trong khi giao diện mọi người dùng cần biết cờ để ẩn chức năng (WEB-076). Không tự thêm endpoint; ghi đề xuất vào `wiki/14` và báo cáo để mở task sửa hợp đồng (ví dụ cờ hiệu lực trả kèm `GET /ui/packs/current` hoặc một đường dẫn đọc riêng).
- Điểm lệch cần lưu ý: hợp đồng chưa có API đọc ghi `tenant_setting` tổng quát; task chỉ làm cổng nội bộ và API ngưỡng bất thường đã có.

## 4. Ngoài phạm vi

- Tuỳ chọn cá nhân của người dùng (nhóm CNH).
- Màn hình quản trị cờ (WEB-072).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/main/resources/db/migration/config/**` (migration seed mới)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `data/seed/feature-flags/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers cho `If-Match` 409, cờ lạ 404, `rollout` theo đơn vị và theo tỷ lệ cho kết quả ổn định với cùng người dùng.
- Kiểm thử hợp đồng bốn thao tác khớp `config.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6, nêu rõ đề xuất sửa hợp đồng cho WEB-076.

## 7. Báo cáo

Theo AGENTS.md mục 6.
