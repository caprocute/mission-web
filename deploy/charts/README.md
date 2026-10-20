# Biểu đồ Helm và bộ giá trị triển khai theo dịch vụ (`deploy/charts/`)

Thư mục này chứa cấu hình triển khai Helm của từng dịch vụ trong hệ thống Mission (TL-04 WI-23, `docs/quy-dinh/HA-TANG.md`):

- Khi tạo microservice mới bằng `./scripts/new-service.sh <ten-dich-vu>`, tệp `backend/services/service-template/helm/values.yaml` được chép sang `deploy/charts/<ten-dich-vu>/values.yaml` và điền sẵn mã `SVC-xx`, tên dịch vụ, schema và tài khoản CSDL `svc_<schema>`.
- Chart thư viện dùng chung `vt-service` và các chart hoàn chỉnh do `INF-01` và `INF-02` xây dựng.
- Tuyệt đối không đặt mật khẩu, token hay khoá rõ trong `values.yaml`; mọi bí mật đều tham chiếu đường dẫn OpenBao (`secret/data/mission/...`).
