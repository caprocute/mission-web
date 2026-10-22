# Dữ liệu khởi tạo (seed)

Thư mục này chứa dữ liệu khởi tạo dùng ở môi trường dev, kiểm thử và khi mở tenant mới. Mọi dữ liệu là **giả lập** hoặc mẫu để tenant sửa, không lấy từ dữ liệu thật của cơ quan nào, và không chứa thông tin truy cập. Mỗi thư mục con có README hoặc chú thích riêng; trang này chỉ tóm tắt và hướng dẫn hai bộ nạp cho tenant mới của DL-06.

| Thư mục | Nội dung |
|---|---|
| `org/` | Danh mục tổ chức dùng chung: nhóm đối tượng K1..K9, chức danh, mẫu nhập cây tổ chức |
| `task/` | Thư viện danh mục công việc mẫu cho bảng `task.work_catalog`, `task.catalog_item` (CORE-02-01) |
| `catalog/` | Danh mục công việc mẫu theo khối K1..K9 dạng Excel để nhập qua API (DL-06) |
| `ui-pack/` | UI Pack mặc định của tenant mới, bộ kiểm tra tĩnh và bộ nạp qua API (DL-06) |
| `component-registry/`, `config/`, `feature-flags/`, `plugins/`, `docgen/` | Dữ liệu khởi tạo của các mô-đun cấu hình tương ứng |

## Nạp cho tenant mới

Hai bộ nạp dưới đây gọi API công khai của mission-core như một người quản trị, không ghi thẳng CSDL, nên mọi kiểm tra nghiệp vụ, phân quyền, kiểm toán và sự kiện chạy như khi người dùng thao tác trên màn hình. Cả hai chỉ dùng thư viện chuẩn của Python 3 và đọc thông tin truy cập từ biến môi trường; không ghi token vào tệp hay vào lịch sử lệnh dùng chung.

| Biến | Ý nghĩa |
|---|---|
| `MISSION_API` | Gốc API, ví dụ `http://localhost:4200/api/v1` khi chạy qua shell dev |
| `MISSION_TOKEN` | Access token của người thực hiện (lấy từ Keycloak realm của tenant) |
| `MISSION_TOKEN_DUYET` | Tuỳ chọn, token của quản trị thứ hai để phát hành UI Pack (bốn mắt) |

### UI Pack mặc định

```bash
cd data/seed/ui-pack
python3 kiem_ui_pack.py mac-dinh-1.0.0.json      # kiểm tĩnh: lược đồ, registry, tương phản, cỡ chữ, gzip
python3 nap_ui_pack.py                           # tạo bản nháp, ghi token và bố cục, gửi duyệt
```

`nap_ui_pack.py` cần token của quản trị tenant (`tenant_admin`). Trình tự là `listUiPacks`, `createUiPackVersion` (nhân bản bộ mặc định), `updateUiPackVersion` kèm `If-Match`, rồi `submitUiPackVersion`. Bước phát hành (`publishUiPackVersion`) chỉ chạy khi có `MISSION_TOKEN_DUYET` của một quản trị khác người gửi, vì máy chủ từ chối người gửi tự duyệt. Lịch sử phiên bản và kích thước đo được ghi ở `ui-pack/CHANGELOG.md`.

### Danh mục công việc mẫu theo khối

```bash
cd data/seed/catalog
python3 sinh_danh_muc_mau.py                     # kiểm danh-muc-mau.csv rồi sinh danh-muc-mau-K1..K9.xlsx
python3 nap_danh_muc.py K3                       # nhập khối K3 vào phiên bản danh mục nháp đầu tiên
python3 nap_danh_muc.py K3 --danh-muc <id>       # hoặc chỉ định phiên bản danh mục đích
```

Nguồn duy nhất là `danh-muc-mau.csv` (6 mục cho mỗi khối, mã `TC-K<n>-nn`, có cả nhóm Thường xuyên và Then chốt). Bộ sinh kiểm từng dòng theo cùng quy tắc máy chủ dùng khi nhập: mã đúng dạng, đủ trường bắt buộc, mức khó 1–5, điểm chuẩn 0–100, nhóm hợp lệ và tên không chứa từ mơ hồ của Policy Pack mặc định. Tệp Excel có đúng tiêu đề cột mà `importCatalogExcel` nhận và một cột "Ghi chú" (máy chủ bỏ qua) đánh dấu mọi dòng là mục mẫu để tenant rà soát trước khi áp dụng. Sau khi sửa CSV, chạy lại bộ sinh và commit cả tệp Excel.

`nap_danh_muc.py` cần token của người có quyền nhập danh mục (`manager` hoặc `policy_author`). Bộ nạp gửi tệp, theo `Location` của phản hồi 202 tới khi tác vụ nền xong, in số dòng hợp lệ và các nhóm lỗi; mã thoát 0 khi tệp lỗi rỗng.

## Kiểm thử

```bash
python3 -I -m unittest discover -s data/seed/ui-pack -p 'test_*.py'
python3 -I -m unittest discover -s data/seed/catalog -p 'test_*.py'
cd backend && mvn -o test -pl mission-core -Dtest='UiPackSeedTest,CatalogSeedTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Hai kiểm thử Java chạy tệp seed qua đúng mã của máy chủ: `UiPackSeedTest` dùng lược đồ UI Pack 1.0 và `UiPackStaticChecks`; `CatalogSeedTest` đọc từng tệp Excel bằng `FastExcelSpreadsheets` rồi chạy `CatalogSheet` và `CatalogRules` như tác vụ nhập.
