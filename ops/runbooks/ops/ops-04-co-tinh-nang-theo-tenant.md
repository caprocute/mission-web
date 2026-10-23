# OPS-04 · Cờ tính năng theo tenant

> **Truy vết:** TL-04 mục 11.2 (OpenFeature, flagd, dọn cờ quá 90 ngày) · TL-03 `config.feature_flag` · QT-18, SCR-1801 · MOD-08 · API `listFeatureFlags`, `setFeatureFlag`, `getEffectiveFeatureFlags` (`contracts/openapi/modules/config.yaml`).
> **Chu kỳ:** khi bật tính năng · **Chịu trách nhiệm:** chủ sản phẩm · **Tự động hoá:** OpenFeature, flagd (TL-04); hiện thực trong repo xem dưới.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Tính năng mới đã có ở mọi cell sau OPS-02 và đang tắt; chủ sản phẩm quyết định bật cho tenant thí điểm, rồi cho nhóm tenant. Hoặc rà soát định kỳ thấy cờ đã bật cho mọi tenant quá 90 ngày.

## Tác động

Cờ tách việc triển khai mã khỏi việc phát hành tính năng tới người dùng. Repo hiện có hai tầng cờ:

- **Theo cell:** khoá `global.featureFlags` trong `deploy/cells/<cell>/values.yaml` (ví dụ `aiAssistant: false` ở CELL-NB); thư viện `vt-service` chuyển mỗi khoá thành biến môi trường `VT_FF_<TÊN>` của mọi dịch vụ. Đổi giá trị cần đồng bộ lại qua Argo CD, pod khởi động lại theo Rollout.
- **Theo tenant:** cờ trong `config.feature_flag` của MOD-08, quản trị tenant bật tắt qua màn hình cấu hình hoặc API `setFeatureFlag` (cần `If-Match`, `Idempotency-Key`); máy khách đọc `getEffectiveFeatureFlags`.

Nhà cung cấp flagd chạy trong cell như TL-04 mô tả chưa được dựng trong repo (suy luận: đang dùng hai tầng trên thay cho flagd); khi INF dựng flagd, sổ tay này bổ sung lệnh tương ứng.

## Kiểm tra nhanh

```bash
grep -n -A5 featureFlags deploy/cells/*/values.yaml           # cờ theo cell đang khai
curl -sS -H "Authorization: Bearer $TOKEN" "$CELL_API/api/v1/config/feature-flags?limit=200"   # cờ theo tenant
```

`$TOKEN` là token của quản trị tenant lấy qua đăng nhập có MFA, không ghi vào tệp; `$CELL_API` lấy theo `apiBaseUrl` của cell trong registry (`vtctl -e <env> cell list`).

## Các bước

**Nếu bật dần cho tenant:**

1. Bật cho tenant thí điểm: quản trị tenant mở màn hình cờ tính năng của tenant và bật cờ, hoặc gọi `PUT /api/v1/config/feature-flags/{key}` với ETag vừa đọc.
2. Theo dõi 24 giờ: lỗi của tính năng trên dashboard dịch vụ, phản hồi người dùng.
3. Mở rộng theo nhóm tenant theo kế hoạch của chủ sản phẩm; ghi từng lần bật vào phiếu thay đổi.

**Nếu đổi cờ theo cell:** sửa `global.featureFlags` trong `deploy/cells/<cell>/values.yaml` qua merge request có chữ ký, đồng bộ theo OPS-02 (thay đổi cấu hình chuẩn, không cần CAB).

**Nếu dọn cờ quá 90 ngày:** mở phiếu nợ kỹ thuật cho đội sở hữu tính năng: xoá nhánh mã tắt, xoá khai báo cờ ở bản phát hành sau, rồi xoá khoá ở `deploy/cells/*/values.yaml`.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Đọc cờ của tenant | (chưa có) | `GET /api/v1/config/feature-flags` |
| Bật tắt cờ của tenant | (chưa có) | `PUT /api/v1/config/feature-flags/{key}` với `If-Match`, `Idempotency-Key` |
| Cờ theo cell | (chưa có) | merge request vào `deploy/cells/<cell>/values.yaml` |

## Kiểm sau khi làm

`getEffectiveFeatureFlags` của một người dùng thuộc tenant trả đúng giá trị; tính năng hiện hoặc ẩn đúng trên giao diện; không có lỗi mới trên dashboard dịch vụ.

## Quay lui

Tắt lại cờ (cùng API hoặc revert merge request). Tắt cờ không hoàn tác dữ liệu người dùng đã tạo bằng tính năng; đội sở hữu tính năng phải nêu cách xử lý dữ liệu đó trong phiếu bật cờ.

## Leo thang

Tính năng gây lỗi ở nhiều tenant sau khi bật: tắt cờ trước, rồi mới điều tra; gọi cấp 2 khi tắt cờ không làm hết lỗi.

## Liên quan

[OPS-02](ops-02-phat-hanh-theo-dot-cell.md), `wiki/03-giao-dien-theo-tenant.md`.
