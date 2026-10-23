> Nguồn: [anh-xa-yeu-cau.md](anh-xa-yeu-cau.md), `TIEN-DO-HE-THONG.md` tại ngày đánh giá. Ngày đánh giá: 23/10/2026.

# Danh sách thiếu hụt

Trang này liệt kê các yêu cầu chưa có bằng chứng đầy đủ. Danh sách chia theo nguyên nhân, vì cách xử lý của mỗi nhóm khác nhau. Nhóm thứ nhất do đội dự án tự làm được. Nhóm thứ hai cần môi trường thật mà hiện chưa có. Nhóm thứ ba cần mua sắm hoặc dịch vụ bên ngoài, thuộc trách nhiệm của đơn vị chủ quản. Mốc trong bảng là mốc dự kiến theo kế hoạch tổng thể, chưa phải cam kết; khi một task xong, người làm task cập nhật dòng tương ứng.

## 1. Việc của đội dự án

| Yêu cầu | Phần thiếu | Task chịu trách nhiệm | Mốc dự kiến |
|---|---|---|---|
| SEC-30 | Nhật ký kiểm toán API Kubernetes và chính sách kiểm toán | INF-19 | Đợt 3 |
| SEC-50 | Triển khai Falco (và cân nhắc Tetragon) trong cụm; bộ cảnh báo đã có | INF-19 | Đợt 3 |
| SEC-51 | Vá hệ điều hành node tự động (kured) | INF-19 | Đợt 3 |
| SEC-22, SEC-43, SEC-54 | Dịch vụ `siem-forwarder` chuẩn hoá ECS, đẩy `sys.siem.v1` ra SIEM qua mTLS một chiều; quy tắc phát hiện tối thiểu của TL-04 | INF-19 | Đợt 3 |
| SEC-42 | PAM có ghi phiên, bản giả lập trước khi mua sắm | INF-27 | Đợt 3 |
| SEC-44, OPS-18 | Phần còn lại của sao lưu: MirrorMaker 2, snapshot OpenSearch, ClickHouse, Valkey, lịch sao lưu | INF-17, INF-18 | Đợt 3 |
| SEC-44, DR-14, OPS-18 | Biên bản diễn tập khôi phục DR-01..DR-16, chuyển vùng giữa hai trung tâm dữ liệu | QA-14, QA-15 | Đợt 3 |
| SEC-40 | Chạy ZAP hằng đêm trên staging thay vì cục bộ | QA-13, INF-21 | Khi có staging |
| SEC-27 | Chạy kube-bench định kỳ (CronJob) ngoài gói cài cách ly | INF-19 | Đợt 3 |
| SEC-45 | Hoàn tất gói cài cách ly có chữ ký | INF-23 | Đợt 3 |
| PERF-16, PERF-17 | Đo thông lượng sổ cái và ký lô | QA-10 | Hoãn theo YC-PT-19 |
| ASVS | 44 mục ASVS chưa đạt hoặc chờ kiểm (197/241 đạt) | QA-13 | Trước SEC-41 |

## 2. Việc cần môi trường thật

| Yêu cầu | Phần thiếu | Phụ thuộc | Ghi chú |
|---|---|---|---|
| SEC-13 | Báo cáo tỷ lệ người dùng có MFA = 100% | INF-21 (staging), vận hành chính thức | Script thu đã có trong `thu-bang-chung.sh` |
| SEC-21, SEC-57 | Biên bản bắt gói trên cụm thật | INF-21 | Đã kiểm trên kind |
| SEC-31, SEC-56 | Mã hoá Secret trong etcd bằng KMS v2; bảo vệ etcd bằng mạng riêng, mTLS, mã hoá đĩa | Cụm Kubernetes thật của nhà cung cấp hạ tầng | Cấu hình máy chủ API nằm ngoài repo |
| SEC-23, SEC-37, SEC-38 | Danh sách SBOM, nhật ký từ chối Kyverno và kiểm chữ ký trên cụm đang chạy | Jenkins, Harbor, Dependency-Track thật | Đã kiểm trên kind |
| SEC-52 | Biên bản chạy `anonymize` trên bản sao thật | Môi trường sản xuất | Tác vụ và kiểm thử đã có |
| Neo ngoài | Đích neo ngoài thật cho bản niêm phong sổ cái | Quyết định của đơn vị chủ quản | Cơ chế niêm phong đã có |

## 3. Việc mua sắm hoặc dịch vụ bên ngoài (đơn vị chủ quản)

| Yêu cầu | Hạng mục | Ghi chú |
|---|---|---|
| SEC-11 | HSM thật và biên bản giao thẻ M trong N | Hiện kiểm bằng SoftHSM |
| SEC-42 | Sản phẩm PAM và lưu bản ghi phiên 1 năm | INF-27 làm phần tích hợp |
| SEC-43 | Hợp đồng dịch vụ SOC 24/7, thời gian phản hồi Critical ≤ 15 phút | Cần thống nhất bộ quy tắc phát hiện trước vận hành |
| SEC-35 | Dịch vụ chống DDoS lớp 3, lớp 4 ở biên mạng | Lớp 7 đã có ở APISIX |
| SEC-41 | Kiểm thử xâm nhập độc lập trước vận hành, sau thay đổi lớn về xác thực hoặc phân quyền, và ít nhất hằng năm | Đầu vào: `docs/attt/asvs-4.0.3-tu-danh-gia.md` |
| SEC-46 | Mật mã do Ban Cơ yếu Chính phủ cấp | Chỉ áp dụng khi triển khai cho khối quốc phòng; phía ứng dụng có DV-10-05 |
| Ký số, dấu thời gian | Kết nối dịch vụ chữ ký số từ xa và dấu thời gian thật | Hiện qua bản giả lập (YC-PT-13) |
