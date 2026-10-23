# Mô hình 1: SaaS trên Viettel Cloud

> Nguồn: TL-04 mục 2.1, 2.2 (mô hình 1), 2.5 (hai trung tâm dữ liệu), 3.1 đến 3.8, chương 11 (OPS-01, OPS-02, OPS-07); TL-05 chương 3, 8; `deploy/cells/`, `deploy/clusters/cell-cluster` (INF-08), `deploy/argocd` (INF-04), `deploy/charts/rgw-cell` (INF-12); `ops/runbooks/`. Task: VH-06.

Mô hình SaaS phục vụ phần lớn khách hàng. Khách hàng được chia vào các cell theo bảng ở baseline mục 6, mỗi cell là một bộ triển khai đầy đủ trên một cụm Kubernetes riêng, và có bản sao ấm ở trung tâm dữ liệu phía Nam. Viettel vận hành toàn bộ hạ tầng và chịu SLO 99,9% mỗi tháng cho mỗi cell, 99,95% cho vùng điều phối toàn cục (NFR-04). Khách hàng của mô hình SaaS không cài đặt gì; người quản trị phía khách hàng chỉ dùng giao diện quản trị tenant. Vì vậy trang này dành cho đội vận hành của Viettel, và mô tả việc thường gặp nhất là dựng thêm một cell.

## Hạ tầng của một cell SaaS

Lớp ứng dụng chạy trên máy ảo của Viettel Cloud, co giãn bằng Cluster Autoscaler. Lớp dữ liệu chạy trên máy chủ vật lý có NVMe cục bộ gia nhập cùng cụm, vì PostgreSQL, Kafka, OpenSearch và ClickHouse cần độ trễ đĩa thấp và ổn định. Kho đối tượng Ceph được vận hành tách khỏi cụm Kubernetes bằng cephadm, theo nhóm cell (`ceph-hl-1` cho CELL-HCM và CELL-HN, `ceph-hl-2` cho CELL-G1 đến G4 và CELL-SH; CELL-DTW có cụm riêng). Ở trung tâm dữ liệu phía Nam, tầng dữ liệu chạy đủ kích thước và nhận dữ liệu liên tục (CNPG replica cluster, MirrorMaker 2, Ceph multisite), còn lớp ứng dụng chạy ở mức tối thiểu để sẵn sàng tăng khi chuyển vùng.

## Dựng thêm một cell

Việc dựng cell mới đi qua Git từ đầu đến cuối (OPS-01). Mỗi bước dưới đây là một merge request có duyệt.

1. **Khai cell.** Tạo `deploy/cells/<mã>/cell.yaml` theo khuôn của `deploy/cells/nb/cell.yaml`: hồ sơ (`L`, `M`, `S`), mô hình thuê bao (`silo`, `bridge`, `pool`), TTDL chính `hl`, dự phòng `sn`, đợt phát hành `releaseWave`, Argo CD quản lý (`mgmt`, hoặc `mgmt-dtw` cho khối Đảng). Thêm `deploy/cells/<mã>/values.yaml` chỉ chứa khoá thật sự riêng của cell; số replica và kích thước nằm ở tệp hồ sơ.
2. **Kiểm định cỡ.** Chạy `deploy/capacity/chay-kiem-thu.sh` để kiểm ngân sách kết nối và bảng định cỡ của cell mới; script trả mã 1 khi có vi phạm.
3. **Dựng cụm.** Tạo `deploy/clusters/cell-<mã>-hl/values.yaml` và `cell-<mã>-sn/values.yaml` theo khuôn `deploy/clusters/cell-cluster` (ví dụ `deploy/clusters/cell-g4-hl/values.yaml`). Cluster API dựng ba master trên ba vùng sự cố và các node pool với đúng nhãn, taint của TL-04 mục 3.3.
4. **Kho đối tượng.** Trên máy quản trị Ceph, chạy `deploy/charts/rgw-cell/scripts/khoi-tao-multisite.sh` để tạo realm `cell-<mã>`, zone `cell-<mã>-hl` và `cell-<mã>-sn`, lớp lưu trữ và người dùng S3. Phần bucket do chart `rgw-cell` khai trong Git (`ops/runbooks/ceph-multisite.md`).
5. **Gốc tin cậy và OpenBao.** Làm bước 5 và 6 của [cai-dat-chung.md](cai-dat-chung.md), với HSM của trung tâm dữ liệu.
6. **Đồng bộ.** ApplicationSet của Argo CD trong cụm quản trị tự sinh 24 Application cho mỗi TTDL của cell khi thấy `cell.yaml` mới, và có digest trong `deploy/environments/production/digests.yaml`. Application tên `<service>-<mã>-<hl|sn>`, đích là cụm `cell-<mã>-<hl|sn>`.
7. **Sao lưu và vận hành.** Cài `backup-cell`, bản phát hành kho sao lưu của `rgw-cell`, `autoscaling-cell`, `degrade-controller` cho cell.
8. **Đăng ký cell.** Thêm cell vào danh bạ `tenant-registry` (SVC-03) ở vùng điều phối toàn cục, rồi đưa tenant đầu tiên vào cell theo OPS-07.
9. **Kiểm tra.** Bảng kiểm ở bước 10 của [cai-dat-chung.md](cai-dat-chung.md).

## Phát hành theo đợt cell

Mô hình SaaS cập nhật liên tục theo đợt cell (OPS-02), bằng chiến lược `RollingSync` theo nhãn `wave`: đợt 0 CELL-NB, 1 CELL-G4, 2 CELL-G1 đến G3 và CELL-SH (mỗi lần tối đa một nửa), 3 CELL-HN và CELL-DTW, 4 CELL-HCM. Giữa hai đợt có thời gian ổn định tối thiểu 24 giờ ngày làm việc; khoảng chờ này là quy trình của quản lý phát hành, chưa được tự động hoá. Cell mới đặt `releaseWave` theo hạng và mức rủi ro: cell nhỏ đứng ở đợt sớm để lỗi chỉ lộ ra ở tải lớn được phát hiện trước.

Digest ảnh trong `deploy/environments/production/digests.yaml` chỉ được ghi bởi bước `vtGitopsBump` của pipeline, bằng commit có chữ ký, sau khi ảnh đã được ký cosign và có chứng thực SLSA. Không sửa tệp này bằng tay.

## Liên quan

- [cai-dat-chung.md](cai-dat-chung.md), [quan-tri-thuong-ngay.md](quan-tri-thuong-ngay.md), [ma-tran-trach-nhiem.md](ma-tran-trach-nhiem.md)
- wiki 12, các mục INF-04, INF-08, INF-12; wiki 19 "Ba mô hình phân phối, một bộ chart"
- `ops/runbooks/chuyen-tenant-giua-cell.md`: chuyển tenant sang cell khác khi tenant lớn lên
