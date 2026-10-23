# Các bước cài đặt chung cho mọi mô hình

> Nguồn: TL-04 mục 2.1, 3.5 đến 3.8, 8.4, 9.4 đến 9.6, 9.9; `deploy/charts/vt-platform` (INF-02), `deploy/charts/cell-base` và `deploy/clusters/cell-cluster` (INF-08), `cell-mesh`, `cell-storage`, `cell-egress` (INF-09), `ha-tang/*` (INF-10, INF-11, INF-13), `deploy/charts/rgw-cell` (INF-12), `deploy/charts/backup-cell` (INF-17, INF-18), `deploy/policies` (INF-07), `deploy/argocd` (INF-04); `ops/runbooks/openbao-van-hanh.md`, `openbao-hsm.md`, `openbao-pki.md`, `ceph-multisite.md`. Task: VH-06.

Một bản cài là một cell. Dù cell chạy ở trung tâm dữ liệu của Viettel hay trên hạ tầng của khách hàng, các lớp được dựng theo cùng một thứ tự, vì lớp sau dựa vào lớp trước: chứng thư nội bộ cần cert-manager, bí mật của mọi dịch vụ cần OpenBao, OpenBao cần HSM và CA khởi đầu, còn dịch vụ ứng dụng cần CSDL đã có vai trò. Trang này mô tả thứ tự đó. Các tài liệu theo mô hình chỉ ghi phần khác biệt (ai làm, lấy gói từ đâu, đồng bộ bằng gì).

## Tổng quan thứ tự

| Bước | Lớp | Thành phần | Ai làm (xem ma trận trách nhiệm) |
|---|---|---|---|
| 1 | Cụm | Kubernetes, nhãn node, Cilium | Đội hạ tầng |
| 2 | Namespace, mesh, lưu trữ | `cell-base`, Istio ambient, `cell-mesh`, `cell-storage`, `cell-egress` | Đội hạ tầng |
| 3 | Operator | cert-manager, ESO, CNPG, Strimzi, OpenSearch, Altinity, KEDA, Argo Rollouts, Rook-Ceph (khi RGW trong cụm) | Đội hạ tầng |
| 4 | Chính sách | Kyverno và `deploy/policies` | Đội bảo mật |
| 5 | Gốc tin cậy | CA khởi đầu `vt-noi-bo-goc`, PIN HSM | Đội quản trị khoá (bốn mắt) |
| 6 | Bí mật | OpenBao, `deploy/openbao/cau-hinh.sh`, CA trung gian của cell | Đội quản trị khoá |
| 7 | Kho đối tượng | Ceph RGW, `rgw-cell` | Đội hạ tầng |
| 8 | Cell | `vt-platform` (cell-base, dữ liệu, quan sát, dịch vụ) | Đội triển khai, qua Argo CD hoặc công cụ gói cách ly |
| 9 | Vận hành | `backup-cell`, kho sao lưu, `autoscaling-cell`, `degrade-controller` | Đội vận hành |
| 10 | Kiểm tra | Bảng kiểm sau cài | Đội triển khai cùng khách hàng |

Ở SaaS, mọi bước từ 2 trở đi là Application của Argo CD trong cụm quản trị, đồng bộ theo sóng. Ở đám mây riêng, Argo CD đặt tại chỗ làm việc tương tự. Ở bản cách ly, bước 8 dùng `tools/cai-dat.sh` của gói (render rồi `kubectl apply --server-side`), các bước còn lại làm từ chart trong gói.

## Bước 1: cụm Kubernetes

Cụm phải đáp ứng [yeu-cau-ha-tang.md](yeu-cau-ha-tang.md): nhãn `vt.io/pool`, `topology.kubernetes.io/zone`, taint theo bảng TL-04 mục 3.3. Ở SaaS, cụm được dựng bằng Cluster API từ khuôn `deploy/clusters/cell-cluster` với values của cell (ví dụ `deploy/clusters/cell-g4-hl/values.yaml`); khuôn dừng render nếu thiếu thông số của nhà cung cấp hạ tầng. Ở hạ tầng khách hàng, cụm có thể do khách hàng dựng bằng công cụ của họ, miễn là đúng nhãn và taint.

Cilium được cài với các tham số bắt buộc khi chạy cùng Istio ambient: `cni.exclusive: false`, `socketLB.hostNamespaceOnly: true`, không bật `bpf.masquerade`, bật Hubble. Tệp `deploy/environments/kind/cilium-values.yaml` là ví dụ đã chạy trên kind.

## Bước 2: namespace, mesh, lưu trữ, cổng ra

Trước hết cài phần `cell-base` của `vt-platform` (13 namespace của TL-04 mục 3.5 với nhãn vùng bảo mật, Pod Security `restricted`, NetworkPolicy mặc định từ chối), vì ba chart dưới đây ghi tài nguyên vào các namespace đó (`edge`, `egress`, `secure`, `identity`). Ở SaaS và đám mây riêng đây là Application `cell-base-<mã>-<địa điểm>` của Argo CD; khi cài bằng tay, render `vt-platform` như ở bước 8 với mọi thành phần tắt trừ `cell-base`.

Istio ambient cài bốn chart chính thức theo thứ tự base, istiod, cni, ztunnel, mỗi chart với tệp values cùng tên trong `deploy/charts/cell-mesh/istio/` (bản ghim ở `chart.yaml`). Sau đó cài ba chart của cell:

```bash
# Chạy từ gốc kho GitOps; <mã> là mã cell, ví dụ kh01.
helm upgrade --install cell-mesh    deploy/charts/cell-mesh    -n istio-system
helm upgrade --install cell-storage deploy/charts/cell-storage -n kube-system \
  --set cephRbd.clusterID=<mã cụm Ceph> --set cephRbd.pool=k8s-rbd-<mã>
helm upgrade --install cell-egress  deploy/charts/cell-egress  -n egress
```

`cell-storage` từ chối render khi tắt mã hoá phía client của `ceph-rbd` hoặc khi tên pool không có dạng `k8s-rbd-<mã cell>`. Danh sách đích của `cell-egress` trong `values.yaml` chỉ là minh hoạ: danh sách thật lấy từ hồ sơ tích hợp đã duyệt của tenant. Ở bản cách ly, không cài `cell-egress` vì `global.egress.allowInternet` là `false` và chính sách `chan-ra-ngoai-cum` chặn mọi luồng ra ngoài.

## Bước 3: operator

Cài các operator ở bảng "Phần mềm nền của cụm" trong [yeu-cau-ha-tang.md](yeu-cau-ha-tang.md), mỗi operator trên pool `system`. Kho đối tượng chạy trong cụm (đám mây riêng không có S3 riêng, bản cách ly) cần thêm Rook-Ceph; chart `rgw-cell` khi `global.objectStore.inCluster` là `true` trỏ tới RGW của Rook ở namespace `rook-ceph`.

## Bước 4: chính sách Kyverno

Áp bộ chính sách `deploy/policies/vt-*.yaml` và các ngoại lệ còn hạn trong `deploy/policies/exceptions/`. Chính sách `vt-require-signed-images` chỉ cho chạy ảnh đã ký bằng khoá `cosign-release` và có chứng thực SLSA từ kho Git và máy dựng của Viettel. Ở bản cách ly, `tools/cai-dat.sh` của gói tự sinh bản chính sách của khách hàng (khoá công khai phát hành thay cho khoá trong OpenBao Transit, registry đổi sang Harbor nội bộ).

Từ bước này trở đi, một ảnh chưa ký hoặc tham chiếu theo thẻ sẽ bị từ chối. Khi gặp lỗi "no signatures found", kiểm tra trước tiên ảnh đã được nạp kèm chữ ký chưa (`cosign load` ở bản cách ly) và Harbor có giữ thẻ `.sig`, `.att` không.

## Bước 5 và 6: gốc tin cậy và OpenBao

Đây là bước duy nhất cần nghi thức bốn mắt, và chỉ làm một lần cho mỗi cell. Làm theo `ops/runbooks/openbao-van-hanh.md` mục "Khởi tạo một cụm mới", tóm tắt như sau:

1. Đội quản trị khoá chuẩn bị HSM theo `ops/runbooks/openbao-hsm.md`: khe riêng cho OpenBao của cell, khoá mở không trích xuất được, nhãn khớp `openbao.seal.pkcs11.tokenLabel` và `keyLabel` trong values của cell.
2. Tạo hai Secret ngoài Git qua kênh quản trị (PAM): `secure-data/openbao-hsm-pin` (khoá `pin`) và `cert-manager/vt-noi-bo-goc-ca` (CA trung gian khởi đầu, ký từ CA gốc ngoại tuyến theo `ops/runbooks/openbao-pki.md`).
3. Đồng bộ phần `openbao` của `vt-platform`. Pod OpenBao chạy nhưng chưa sẵn sàng cho tới khi khởi tạo.
4. Khởi tạo trên `openbao-0`: `bao operator init -recovery-shares=5 -recovery-threshold=3`. Năm khoá phục hồi chia cho năm người giữ theo biên bản; root token chỉ dùng cho bước 5 rồi thu hồi.
5. Ghi mật khẩu khởi tạo của `bao_admin` cho từng cụm PostgreSQL, rồi chạy `CELL=<mã> deploy/openbao/cau-hinh.sh`. Lần chạy đầu sinh CSR cho CA trung gian của cell; ký theo `openbao-pki.md` rồi chạy lại với `PKI_SIGNED_CERT=<tệp>`.

Sau bước này, ClusterIssuer `openbao-pki-cell` cấp chứng thư cho mọi dịch vụ, ClusterSecretStore `openbao-cell` đồng bộ bí mật tĩnh, và secret engine database cấp người dùng PostgreSQL động cho mission-core.

## Bước 7: kho đối tượng

Kho đối tượng của cell gồm phần làm một lần trên máy quản trị Ceph và phần khai báo trong Git. Phần làm một lần là `deploy/charts/rgw-cell/scripts/khoi-tao-multisite.sh`: tạo realm, zone, lớp lưu trữ và người dùng S3 của service, khoá ghi thẳng vào OpenBao `kv/ceph/cell-<mã>/<uid>`. Phần khai báo là chart `rgw-cell`: Job tạo sáu bucket (`ev`, `ds`, `tpl`, `la`, `tmp`, `tr`) với versioning, Object Lock, vòng đời và chính sách bucket, cùng ExternalSecret khoá S3 cho từng service. Ở bản cài một địa điểm, không có zone phía Nam: chỉ dựng zone master và bỏ bước kéo realm ở `ops/runbooks/ceph-multisite.md` (suy luận, chưa chạy thử).

Kho sao lưu là một bản phát hành thứ hai của `rgw-cell` với `values-kho-sao-luu.yaml`, trỏ tới kho Ceph ở địa điểm khác. Bản này tạo bucket `bk-cell-<mã>-*` với Object Lock COMPLIANCE; khoá S3 của cell chỉ đọc, ghi, liệt kê, không xoá được.

## Bước 8: cài cell bằng `vt-platform`

Values của cell được chồng theo thứ tự cố định:

```bash
cd deploy/charts/vt-platform
ci/cap-nhat-phu-thuoc.sh        # cập nhật chart con từ dưới lên (bẫy helm dependency update, wiki 12)
helm template vt . -n observability \
  -f values-<mô hình>.yaml \
  -f sizing/values-sizing-<hồ sơ>.yaml \
  -f ../../cells/<mã>/values.yaml \
  --set global.site=<địa điểm> --set global.siteRole=primary > /tmp/cell-<mã>.yaml
```

Lệnh render này là cách xem trước bản cài trước khi đồng bộ. Ở SaaS và đám mây riêng, Argo CD không dùng một release Helm duy nhất mà dựng 24 Application, mỗi Application bật đúng một thành phần của chart cha với cùng thứ tự values cộng digest ảnh từ `deploy/environments/<môi trường>/digests.yaml`. Release luôn đặt ở namespace `observability`, vì chart Fluent Bit chỉ dùng namespace của release; mọi chart khác ghi namespace tường minh.

Thứ tự đồng bộ trong cell: `cell-base` đã có từ bước 2, tiếp theo là `observability-cell`, rồi tầng dữ liệu (`data-pg`, `kafka`, `valkey`, `opensearch`, `clickhouse`), cuối cùng là dịch vụ ứng dụng. Job hậu cài đặt của `data-pg` cấp quyền cho bốn vai trò mỗi schema bằng chính `mig_<schema>`, nên không cần siêu người dùng.

## Bước 9: thành phần vận hành ngoài `vt-platform`

| Chart | Việc làm | Sổ tay |
|---|---|---|
| `backup-cell` | Khôi phục thử PostgreSQL hằng ngày, snapshot etcd, Velero, sao lưu minh chứng, ClickHouse, OpenSearch, Git, kiểm kéo Harbor, cảnh báo sao lưu | `ops/runbooks/sao-luu-*.md` |
| `autoscaling-cell` | Cluster Autoscaler, khung tăng trước của KEDA theo mốc hạn | wiki 12, INF-16 |
| `degrade-controller` | Bộ điều khiển suy giảm ba mức | `ops/runbooks/suy-giam-ba-muc.md` |
| `tenant-registry` | Danh bạ tenant SVC-03. Ở SaaS là dịch vụ toàn cục; ở bản cài đơn cell chạy trong cụm quản trị tại chỗ (TL-05 mục 9.1) | `ops/runbooks/cap-phat-kich-hoat-tenant.md` |

## Bước 10: kiểm tra sau cài

Biên bản kiểm tra sau cài là một phần hồ sơ bàn giao. Ở bản cách ly, `tools/kiem-tra-sau-cai.sh` của gói chạy tự động và ghi biên bản `bien-ban-sau-cai.md`; ở hai mô hình còn lại, dùng cùng danh sách mục và ghi kết quả bằng tay khi chưa có công cụ.

| Mục | Cách kiểm | Đạt khi |
|---|---|---|
| Pod sẵn sàng | `kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded` | Không còn dòng nào |
| Chữ ký ảnh | `cosign verify` mọi ảnh đang chạy bằng khoá phát hành | Mọi ảnh đạt |
| CIS benchmark | kube-bench | Không có mục FAIL chưa giải trình |
| Luồng ra ngoài | `hubble observe --to-identity world` 10 phút | Bản cách ly: không có luồng nào; mô hình khác: chỉ từ egress gateway |
| OpenBao | `bao status` trên từng pod | `Sealed false`, đủ thành viên Raft |
| PostgreSQL | `kubectl cnpg status <cụm> -n data-pg` | Primary và replica đồng bộ, WAL lưu trữ không lỗi |
| Sao lưu | Lần chạy đầu của `restore-check` và các CronJob sao lưu | Chỉ số `backup_*_success` bằng 1 |
| Kiểm thử khói | Đăng nhập, mở trang chủ, tạo và xoá một bản ghi thử của tenant thử | Không có lỗi |
| Diễn tập khôi phục | Khôi phục một cụm PostgreSQL vào namespace cách ly (`restore-check` chạy tay) | Dữ liệu khớp |

Diễn tập khôi phục và đo tải rút gọn (PERF-01) là yêu cầu của TL-04 mục 9.9 đối với biên bản cách ly. Trong giai đoạn phát triển, hai mục này chưa chạy (YC-PT-19).
