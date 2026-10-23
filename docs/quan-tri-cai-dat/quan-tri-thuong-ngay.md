# Quản trị thường ngày

> Nguồn: TL-04 chương 8 (sao lưu, bảng lịch sao lưu), mục 9.4 (khoá), chương 10 (quan sát), chương 11 (OPS-06, OPS-07, OPS-14, OPS-18, OPS-20); TL-05 mục 9.2; TL-02 QT-01 (SCR-0102); `ops/runbooks/` (đặc biệt `cap-phat-kich-hoat-tenant.md`, `sao-luu-*.md`, `xoay-vong-khoa-du-lieu.md`, `openbao-van-hanh.md`, `openbao-pki.md`), `services-go/vtctl` (INF-26), `deploy/charts/observability-cell`, `observability-mgmt` (INF-14, INF-15). Task: VH-06.

Trang này gom các việc người quản trị làm sau khi bản cài đã vào vận hành. Mỗi mục giải thích việc đó là gì, hệ thống đã tự động hoá phần nào, người quản trị phải làm phần nào, và sổ tay chi tiết nằm ở đâu. Việc giống nhau ở cả ba mô hình; khác biệt duy nhất là ai làm, ghi ở [ma-tran-trach-nhiem.md](ma-tran-trach-nhiem.md).

## Tạo tenant

Tenant là một tổ chức dùng nền tảng (một tỉnh, một bộ, một đơn vị). Tạo tenant là việc của quản trị nền tảng trên màn hình "Khởi tạo tenant" của phân hệ quản trị: một trình hướng dẫn sáu bước nhập hợp đồng, quy mô, mô hình thuê bao, cell, tên miền, khoá mã hoá, bộ chính sách, bộ giao diện và nhà cung cấp định danh. Khi người quản trị bấm "Tiếp tục" ở cuối bước 3, dịch vụ danh bạ tenant (SVC-03) chạy một saga cấp phát qua chín bước: tạo CSDL `td_<mã>` và chạy migration, tạo khoá mã hoá của tenant trong OpenBao, tạo Organization trong Keycloak, trỏ tên miền, cấp chứng thư TLS, gắn Policy Pack và UI Pack, tạo lịch nghỉ, đặt hạn mức, rồi kiểm thử khói. Bước nào lỗi quá số lần thử thì saga hoàn tác mọi bước đã làm và tenant trở về trạng thái khởi tạo.

Người quản trị cần biết ba điều:

1. **Theo dõi và gỡ kẹt** một lần cấp phát theo `ops/runbooks/cap-phat-kich-hoat-tenant.md` (truy vấn job, saga kẹt, chứng chỉ chưa cấp).
2. **Kích hoạt và tạm ngừng** bằng giao diện, hoặc bằng `vtctl tenant status <id>`, `vtctl tenant activate <id> --reason "<lý do>" --confirm`. Mọi lệnh ghi của `vtctl` mặc định chạy thử, chỉ thực hiện khi có `--confirm`, và ghi một bản ghi kiểm toán.
3. **Tenant silo ở bản cài đơn cell** cần thêm tên CSDL `td_<mã>` vào `data-pg.tenantDatabases` trong values của cell (mẫu ở [vi-du/cell-rieng-compact.yaml](vi-du/cell-rieng-compact.yaml)) trước khi cấp phát. Diễn tập đưa một tenant mới vào vận hành trong một ngày (OPS-07) là nội dung của task VH-04.

## Sao lưu và kiểm tra khôi phục

Mọi dòng của bảng lịch sao lưu TL-04 đã có cấu hình dạng mã và một cách kiểm tra tự động. Người quản trị không chạy sao lưu bằng tay; việc của họ là đọc cảnh báo, xử lý theo sổ tay, và chứng kiến các lần khôi phục thử định kỳ.

| Dữ liệu | Cơ chế | Kiểm tra tự động | Sổ tay |
|---|---|---|---|
| PostgreSQL | Barman Cloud: bản nền hằng ngày, WAL liên tục, giữ 35 ngày, bản tháng 12 tháng, bản năm sổ cái 10 năm | `restore-check` mỗi ngày dựng cụm tạm và chạy truy vấn kiểm | `sao-luu-postgresql.md` |
| etcd, tài nguyên Kubernetes | Snapshot etcd mã hoá 6 giờ một lần; Velero 6 giờ và hằng ngày cho volume | Cảnh báo `EtcdSnapshotMissing` | `sao-luu-kubernetes.md` |
| Kafka | MirrorMaker 2 sang địa điểm khác; outbox giữ 72 giờ để phát lại | Chưa có đối soát offset hằng ngày | `sao-luu-kafka.md`, `phat-lai-outbox.md` |
| Minh chứng, biểu mẫu ký | Đồng bộ sang kho bất biến lúc 00:30 | Kiểm mẫu 1.000 đối tượng lúc 06:00 | `sao-luu-minh-chung.md` |
| OpenSearch, ClickHouse | Snapshot hằng ngày; ClickHouse đầy đủ chủ nhật, gia tăng ngày khác | Khôi phục thử thứ bảy (OpenSearch), ngày 1 hằng tháng (ClickHouse) | `sao-luu-opensearch-clickhouse.md` |
| OpenBao | Snapshot Raft hằng giờ | Cảnh báo quá 2 giờ; khôi phục thử hằng quý làm tay vì cần HSM | `sao-luu-openbao.md` |
| Kho Git, Harbor | Gương 15 phút, bundle hằng ngày; nhân bản ảnh | Clone thử chủ nhật, kéo thử theo digest hằng ngày | `sao-luu-git-harbor.md` |
| Valkey | Không sao lưu: chỉ là bộ nhớ đệm, khoá chống trùng, bộ đếm | Không áp dụng | `sao-luu-opensearch-clickhouse.md` mục 5 |

Diễn tập khôi phục đầy đủ mỗi quý (OPS-18) là bắt buộc với hồ sơ compact, vì đây là một trong hai biện pháp bù cho việc chỉ có một vùng sự cố. Biên bản diễn tập ghi thời gian khôi phục thực tế để đối chiếu với RTO đã cam kết.

## Xoay vòng khoá

Khoá được xoay tự động theo lịch; người quản trị chỉ can thiệp khi cảnh báo báo lịch không chạy, hoặc khi nghi một khoá bị lộ.

| Khoá | Tự động | Can thiệp tay | Sổ tay |
|---|---|---|---|
| DEK dữ liệu theo tenant và tháng | Sinh DEK tháng mới ngày 1, bọc lại hằng ngày sau khi KEK xoay | Mã hoá lại khi nghi DEK lộ, cần hai người duyệt | `xoay-vong-khoa-du-lieu.md` |
| KEK của tenant, khoá ký `cosign-release` | CronJob `openbao-xoay-khoa` xoay khoá quá 365 ngày, cảnh báo `VtKhoaQuaHan` | Chạy tay: `kubectl -n secure-data create job xoay-tay --from=cronjob/openbao-xoay-khoa` | `openbao-van-hanh.md` |
| Chứng thư dịch vụ nội bộ | cert-manager cấp từ `openbao-pki-cell`, hạn 90 ngày, tự gia hạn | Không | `openbao-pki.md` |
| CA trung gian của cell | Không | Ký lại từ CA gốc ngoại tuyến trước khi hết hạn, nghi thức bốn mắt | `openbao-pki.md` |
| Mật khẩu người dùng CSDL của ứng dụng | Người dùng động 24 giờ, tối đa 72 giờ, do OpenBao cấp | Không | `openbao-van-hanh.md` |
| PIN HSM | Không | Đổi trên HSM, cập nhật Secret, xoá lần lượt từng pod OpenBao | `openbao-hsm.md` |
| Mật khẩu robot Harbor | Không | `ap-dung-cau-hinh.sh --xoay` | wiki 12, INF-07 |

## Giám sát: dashboard và cảnh báo

Mỗi cell có Prometheus, OTel Collector và Fluent Bit (`observability-cell`); luật cảnh báo nằm trong `deploy/charts/observability-cell/rules/` (SLO API, năng lực ghi, nhóm cảnh báo tối thiểu) và trong chart của từng thành phần (sao lưu, OpenBao, kho đối tượng). Mỗi cảnh báo có chú thích `runbook_url` trỏ tới một tệp trong `ops/runbooks/`; `ops/runbooks/README.md` là danh mục ánh xạ cảnh báo sang sổ tay. Khi nhận một cảnh báo, người trực mở đúng sổ tay đó trước khi làm bất cứ điều gì.

Dashboard dạng mã (`deploy/charts/observability-mgmt/dashboards/`) gồm bốn bảng: SLO của cell, dịch vụ, hạ tầng dữ liệu, nghiệp vụ. Ở SaaS, Grafana và Alertmanager đặt ở cụm quản trị của Viettel. Ở đám mây riêng, cụm quản trị tại chỗ chạy cùng bộ dashboard. Ở bản cách ly, gói không mang Grafana (giấy phép AGPL) khi pháp chế chưa duyệt; phương án thay thế Apache-2.0 của TL-04 mục 10.1 là Perses cho dashboard, Jaeger cho trace, OpenSearch cho log. Bộ dashboard Perses tương ứng chưa được dựng trong repo (khoảng trống, suy luận).

Việc theo dõi định kỳ đề xuất cho người quản trị:

| Tần suất | Việc |
|---|---|
| Hằng ngày | Xem cảnh báo đang mở; xác nhận `restore-check` và các job sao lưu đêm qua đạt |
| Hằng tuần | Xem tiêu thụ ngân sách lỗi SLO; nạp gói cơ sở dữ liệu quét (bản cách ly); xem kết quả clone thử kho Git |
| Hằng tháng | Xem kết quả khôi phục thử ClickHouse; rà ngoại lệ chính sách Kyverno sắp hết hạn (`deploy/policies/kiem-ngoai-le.py`) |
| Hằng quý | Diễn tập khôi phục (OPS-18), khôi phục thử OpenBao; cập nhật phiên bản (đám mây riêng) |

## Nâng cấp phiên bản

Cách nâng cấp phụ thuộc mô hình. SaaS cập nhật liên tục theo đợt cell ([mo-hinh-saas.md](mo-hinh-saas.md)). Đám mây riêng cập nhật theo quý, khách hàng duyệt bằng việc gộp nhánh cập nhật vào kho nhân bản ([mo-hinh-dam-may-rieng.md](mo-hinh-dam-may-rieng.md), mục "Cập nhật phiên bản"). Bản cách ly nhận bản LTS và bản vá qua phương tiện vật lý ([mo-hinh-cach-ly.md](mo-hinh-cach-ly.md), mục "Nhận bản vá LTS").

Ba quy tắc áp cho mọi mô hình. Thứ nhất, không nâng cấp trong cửa sổ đóng băng OPS-06 (từ ngày 24 tháng cuối quý trong 17 ngày, từ ngày 20 tháng 1 trong 16 ngày) và các cửa sổ bổ sung sinh từ mốc hạn của tenant. Thứ hai, nâng cấp thành phần hạ tầng có trạng thái theo sổ tay riêng của nó: OpenBao nâng từng pod theo `OnDelete` (`openbao-van-hanh.md`), PostgreSQL theo `primaryUpdateStrategy: supervised` của CNPG. Thứ ba, sau mỗi lần nâng cấp chạy lại bảng kiểm sau cài và lưu biên bản.

## Ngoại lệ của hồ sơ compact

Hồ sơ compact chấp nhận ba ngoại lệ so với thiết kế chuẩn (TL-05 mục 9.2). Người quản trị phải giữ các biện pháp bù luôn hoạt động, vì thiếu một biện pháp bù là bản cài đã ra ngoài điều kiện của hợp đồng dịch vụ.

| Ngoại lệ | Hệ quả | Biện pháp bù cần giữ |
|---|---|---|
| Một vùng sự cố | Mất điện cả phòng máy làm gián đoạn toàn bộ dịch vụ; RTO khi mất địa điểm 48 giờ | Sao lưu ngoài địa điểm hằng ngày với WAL liên tục (RPO 15 phút khi kênh sao lưu hoạt động); diễn tập khôi phục hằng quý |
| Vùng bảo mật chạy chung node dữ liệu | Không có node riêng có card mạng riêng vào HSM | Chỉ node dữ liệu có đường tới HSM; NetworkPolicy mặc định từ chối; RBAC namespace vùng bảo mật tách khỏi namespace dữ liệu; mã hoá đĩa node dữ liệu |
| Gộp các cụm PostgreSQL | Một dịch vụ nặng có thể ảnh hưởng dịch vụ khác | Mỗi dịch vụ một CSDL và tài khoản riêng; `max_db_connections` theo CSDL; `statement_timeout` 30 giây |

## Liên quan

- [ma-tran-trach-nhiem.md](ma-tran-trach-nhiem.md), [cai-dat-chung.md](cai-dat-chung.md)
- `ops/runbooks/README.md`: danh mục sổ tay theo cảnh báo
- wiki 12, các mục INF-13, INF-17, INF-18, INF-26
