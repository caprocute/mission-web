# Yêu cầu hạ tầng trước khi cài

> Nguồn: TL-05 mục 3 "Máy chủ chuẩn", mục 8 "Tổng hợp theo hồ sơ cell", chương 9; TL-04 mục 2.1, 3.3 đến 3.8, 9.9; `deploy/charts/vt-platform/sizing/` (INF-03), `deploy/charts/cell-mesh`, `cell-storage`, `cell-egress` (INF-09), `deploy/charts/vt-platform/ha-tang/` (INF-10, INF-11, INF-13), `deploy/policies/` (INF-07). Task: VH-06.

Trang này liệt kê những gì phải có trước khi bắt đầu cài: máy chủ, mạng, thiết bị HSM, phần mềm nền của cụm Kubernetes và kho ảnh. Bản cài chỉ nên bắt đầu khi mọi mục ở cuối trang đã được đánh dấu đạt.

## Máy chủ chuẩn

TL-05 quy mọi node về một danh mục máy chủ chuẩn. Bảng dưới chỉ giữ các mã dùng cho bản cài đơn cell; danh mục đầy đủ ở TL-05 mục 3. vCPU của máy vật lý là luồng phần cứng, của máy ảo là vCPU danh định. Control plane của mỗi cụm (3 master VM-8-16) không tính vào số node của các bảng.

| Mã máy chủ | Loại | Cấu hình | Dùng cho |
|---|---|---|---|
| VM-8-16 | Máy ảo | 8 vCPU, 16 GiB | Control plane; pool ingress hồ sơ S |
| VM-8-32 | Máy ảo | 8 vCPU, 32 GiB | Pool system mọi hồ sơ |
| VM-16-64 | Máy ảo | 16 vCPU, 64 GiB | Pool app-general, app-worker hồ sơ S, M; cụm quản trị compact |
| VM-32-128 | Máy ảo | 32 vCPU, 128 GiB | Node hội tụ hồ sơ compact; cụm quản trị hồ sơ S, M |
| PHY-DB-S | Máy chủ vật lý | 32 vCPU, 128 GiB, 2 × 3,84 TB NVMe | PostgreSQL hồ sơ S; node dữ liệu compact (thêm 2 × 8 TB HDD) |
| PHY-DB-L | Máy chủ vật lý | 48 vCPU, 384 GiB, 4 × 3,84 TB NVMe | Toàn bộ PostgreSQL hồ sơ M |
| PHY-DATA, PHY-DATA-S | Máy chủ vật lý | 16 vCPU, 64 GiB hoặc 8 vCPU, 32 GiB, NVMe | Kafka, Valkey, OpenSearch, ClickHouse hồ sơ S, M |
| PHY-SEC-S | Máy chủ vật lý có NIC riêng vào mạng HSM | 16 vCPU, 64 GiB, 2 × 1,92 TB NVMe | Pool security-zone hồ sơ S, M |
| PHY-GPU4 | Máy chủ GPU | 32 vCPU, 512 GiB, 4 GPU 80 GB | Pool gpu hồ sơ S, M (khi bật trợ lý AI) |
| PHY-CEPH | Máy chủ lưu trữ | 12 khe HDD 20 TB, 2 × NVMe cho BlueStore, 2 × 25 Gbps | Ceph RGW và kho sao lưu |

Tổng tài nguyên của một cell theo hồ sơ (TL-05 mục 8.1), ở trung tâm dữ liệu chính:

| Hồ sơ | Node thường / đỉnh | vCPU thường / đỉnh | RAM đỉnh | Lưu trữ thô | GPU |
|---|---|---|---|---|---|
| compact | 6 / 6 | 192 / 192 | 0,75 TiB | 23 TB NVMe và 48 TB HDD | 0 (tuỳ chọn 2) |
| S | 25 / 30 | 392 / 472 | 2,2 TiB | 69 TB NVMe | 4 |
| M | 30 / 46 | 568 / 824 | 4,0 TiB | 119 TB NVMe | 4 |

Bản cài đơn cell còn cần thêm cụm quản trị như bảng chọn hồ sơ ở [README.md](README.md), vì cell phải tự chứa Argo CD, Harbor, ngăn xếp quan sát tập trung và vùng điều phối rút gọn. Bản cách ly cần thêm khoảng 2 TB lưu trữ cho Harbor nội bộ, kho Git cấu hình và trạm nhận gói, vì mỗi bản LTS chiếm khoảng 40 đến 60 GB ảnh và chart, và phải giữ tối thiểu 3 bản cộng các bản vá (TL-05 mục 9.3). Khi bản cách ly bật trợ lý AI, ở quy mô compact một máy hai GPU 80 GB là đủ.

## Nhãn và taint của node

Mọi pod của nền tảng chọn node qua nhãn `vt.io/pool`, và hầu hết pool có taint riêng. Người dựng cụm phải gắn nhãn khi đăng ký node vào cụm (tham số `--node-labels` và `nodeRegistration.taints` của kubelet), không gắn tay sau đó, vì node được thay sẽ mất nhãn. Với cụm dựng bằng Cluster API theo khuôn `deploy/clusters/cell-cluster`, khuôn đã tự đặt các nhãn này theo bảng TL-04 mục 3.3 (ví dụ `deploy/clusters/cell-g4-hl/values.yaml`). Bảng dưới là bảng TL-04 mục 3.3, nguồn sự thật cho người dựng cụm.

| Pool | Nhãn | Taint | Thành phần chạy trên pool |
|---|---|---|---|
| system | `vt.io/pool=system` | `CriticalAddonsOnly=true:NoSchedule` | CoreDNS, Cilium operator, Kyverno, ESO, cert-manager, các operator, KEDA, Prometheus |
| ingress | `vt.io/pool=ingress` | `vt.io/pool=ingress:NoSchedule` | APISIX và Coraza, etcd của APISIX |
| app-general | `vt.io/pool=app-general` | không | mission-core, iam, org, evidence, notification, integration, analytics, web |
| app-worker | `vt.io/pool=worker` | `vt.io/pool=worker:NoSchedule` | scoring, document, Gotenberg, ClamAV, workflow executor, search indexer, Kafka Connect |
| data-postgres | `vt.io/pool=postgres` | `vt.io/pool=postgres:NoSchedule` | pg-core, pg-flowable, pg-platform, pg-iam, PgBouncer |
| data-kafka | `vt.io/pool=kafka` | `vt.io/pool=kafka:NoSchedule` | Kafka, Apicurio, Valkey |
| data-search | `vt.io/pool=search` | `vt.io/pool=search:NoSchedule` | OpenSearch, ClickHouse, ClickHouse Keeper |
| security-zone | `vt.io/pool=secure`, `vt.io/zone=secure` | `vt.io/zone=secure:NoSchedule` | ledger-service, esign-service, pg-ledger, pg-secure, OpenBao |
| gpu | `vt.io/pool=gpu` | `nvidia.com/gpu=present:NoSchedule` | vLLM, OCR |

Mỗi node còn phải có nhãn `topology.kubernetes.io/zone` khớp với danh sách `global.topology.zones` trong values của cell. Hồ sơ S và M cần ba vùng sự cố; hồ sơ compact chỉ có một vùng. Node của pool data-search cần `vm.max_map_count=262144` cho OpenSearch, đặt trong cấu hình kubeadm của pool.

Hồ sơ compact không dùng bảng trên mà gộp pool lên hai loại node theo TL-05 mục 9.2. Tệp `sizing/values-sizing-compact.yaml` ánh xạ mọi pool logic sang hai nhãn này qua `global.poolMap` (chart dựng trên `vt-service`) và qua khối `placement` của chart hạ tầng; khuôn cụm mẫu ở `deploy/clusters/cell-mau-compact/values.yaml`.

| Loại node (compact) | Nhãn | Taint | Thành phần chạy trên node |
|---|---|---|---|
| hội tụ (3 node) | `vt.io/pool=hoi-tu` | không | Pool system, ingress, app-general, app-worker gộp lại: dịch vụ ứng dụng, APISIX, Prometheus và các operator |
| dữ liệu (3 node) | `vt.io/pool=du-lieu`, `vt.io/zone=secure` | `vt.io/zone=secure:NoSchedule` | PostgreSQL, Kafka, Valkey, OpenSearch, ClickHouse, và vùng bảo mật (ledger, esign, pg-ledger, pg-secure, OpenBao 3 replica) |

Hai điểm lệch phát hiện khi viết tài liệu này (tên pool của tầng dữ liệu trong chart khác TL-04 mục 3.3, và hồ sơ compact chưa xếp được lên sáu node) đã được sửa ở chart: chart `opensearch`, `clickhouse` chọn `vt.io/pool=search`, chart `data-pg` (phần secure) và `openbao` chọn `vt.io/zone=secure` cùng toleration tương ứng, chart `kafka` chọn `vt.io/pool=kafka`, và hồ sơ compact đặt `openbao.replicas: 3` (Raft 3 node vẫn chịu được mất một node). Valkey tự xử lý trường hợp một vùng: hai pod cùng shard chỉ bắt buộc khác node. Một số pod chưa khai bộ chọn node (Apicurio, kube-state-metrics, org-service, workflow-service) xếp lên node không có taint, tức pool app-general ở hồ sơ S trở lên và node hội tụ ở compact.

## Mạng

| Yêu cầu | Giá trị | Nguồn |
|---|---|---|
| CNI | Cilium, có Hubble; `cni.exclusive: false`, `socketLB.hostNamespaceOnly: true`, không bật `bpf.masquerade` | INF-09, wiki 12 |
| Mesh | Istio ambient (bản ghim ở `deploy/charts/cell-mesh/istio/chart.yaml`), mTLS STRICT | INF-09 |
| Cổng HBONE | NetworkPolicy giữa các namespace trong mesh mở thêm cổng 15008 (chart tự thêm) | wiki 12 |
| Mạng tới HSM | Chỉ node pool `security-zone` có đường vật lý tới HSM | TL-05 mục 9.2 |
| Egress | SaaS và đám mây riêng: chỉ qua egress gateway tới danh sách đích; cách ly: tắt hoàn toàn | TL-04 mục 2.1 |
| Tên miền | Một tên miền điểm vào cho cell, và tên miền cho từng tenant (CNAME tới điểm vào) | DV-03-05 |

## Lưu trữ

Cụm phải có hai StorageClass do chart `cell-storage` sinh: `nvme-local` (TopoLVM trên NVMe cục bộ, dùng cho PostgreSQL, Kafka, OpenSearch, ClickHouse) và `ceph-rbd` (Ceph CSI, mã hoá phía client qua OpenBao). Kho đối tượng là Ceph RGW: ở SaaS do đội hạ tầng vận hành bằng cephadm ngoài cụm; ở đám mây riêng là RGW trong cụm hoặc kho S3 tương thích của khách hàng; ở bản cách ly là RGW trong cụm. Kho đối tượng phải hỗ trợ versioning và Object Lock chế độ COMPLIANCE, vì minh chứng và hồ sơ lưu trữ dựa vào khoá bất biến. Kho S3 của khách hàng không có Object Lock COMPLIANCE thì không dùng được cho bucket `ev`, `ds`.

Kho sao lưu phải nằm ngoài địa điểm của cell. Với hồ sơ compact chỉ có một địa điểm, đây là biện pháp bù duy nhất cho việc không có bản sao ấm, nên kênh tới địa điểm sao lưu là yêu cầu bắt buộc chứ không phải tuỳ chọn.

## Phần mềm nền của cụm

Chart `vt-platform` chỉ chứa dịch vụ và hạ tầng dữ liệu của cell; nó cần các operator và CRD sau đã có trên cụm. Danh sách này được rút ra từ nhóm API trong bản render của `vt-platform` (mô hình private, hồ sơ compact). Phiên bản ghi ở cột cuối là bản đã ghim và kiểm trong repo.

| Thành phần | Nhóm API dùng | Bản đã kiểm |
|---|---|---|
| Kubernetes | | 1.36 ở thiết kế; kind 1.33 ở kiểm thử |
| Cilium | `cilium.io/v2` | Theo `deploy/environments/kind/cilium-values.yaml` |
| Istio ambient | `security.istio.io/v1` | 1.27.9 |
| cert-manager | `cert-manager.io/v1` | Theo chart của INF-13 |
| External Secrets Operator | `external-secrets.io/v1` | Theo chart của INF-13 |
| CloudNativePG và plugin Barman Cloud | `postgresql.cnpg.io/v1`, `barmancloud.cnpg.io/v1` | CNPG 1.30, plugin 0.15.1 |
| Strimzi | `kafka.strimzi.io/v1` | 1.2.0 (Kafka 4.3.1) |
| OpenSearch Kubernetes Operator | `opensearch.org/v1` | 3.0 |
| Altinity ClickHouse Operator | `clickhouse.altinity.com/v1`, `clickhouse-keeper.altinity.com/v1` | 0.27 |
| KEDA | `keda.sh/v1alpha1` | 2.21 |
| Prometheus Operator CRD | `monitoring.coreos.com/v1` | v0.94.1 (đi cùng `observability-cell`) |
| APISIX CRD | `apisix.apache.org/v2` | Theo chart `api-gateway` |
| Argo CD, Argo Rollouts | `argoproj.io/v1alpha1` | 3.1.9, 1.8.4 |
| Kyverno | (chính sách `deploy/policies`) | 1.15 |
| TopoLVM, Ceph CSI | StorageClass | Theo chart `cell-storage` |
| Velero | `velero.io/v1` | 1.18.4 (sao lưu, chart `backup-cell`) |

## Kho ảnh và khoá

Mọi ảnh được tham chiếu theo digest, và Kyverno chỉ cho chạy ảnh có chữ ký cosign của Viettel (SEC-38, SEC-55). Vì vậy trước khi cài phải có Harbor (SaaS và đám mây riêng dùng Harbor của cụm quản trị, bản cách ly dùng Harbor nội bộ của khách hàng) và khoá công khai kiểm chữ ký. Ở SaaS, khoá ký nằm trong OpenBao Transit (`cosign-release`); ở bản cách ly, khách hàng nhận khoá công khai phát hành `vt-release.pub` một lần qua kênh riêng, không đi cùng gói.

Thiết bị HSM phải có khe hoặc phân vùng riêng cho OpenBao của cell, với khoá AES-256 không trích xuất được (hoặc RSA 3072 với `rsa-pkcs-oaep` khi HSM chỉ hỗ trợ RSA cho bọc khoá). CA gốc ngoại tuyến của bản cài được sinh trên máy ngoại tuyến có HSM, và bản sao lưu miền bảo mật HSM theo cơ chế 3 trong 5 thẻ (TL-04 mục 8.7; `ops/runbooks/openbao-pki.md`). Ai giữ thẻ ở từng mô hình ghi ở [ma-tran-trach-nhiem.md](ma-tran-trach-nhiem.md).

## Bảng kiểm trước khi cài

- [ ] Đã chọn mô hình và hồ sơ, đã ghi RTO, RPO cam kết vào hợp đồng (compact: RTO 48 giờ khi mất địa điểm).
- [ ] Đủ máy theo bảng hồ sơ, đã gắn nhãn `vt.io/pool`, `topology.kubernetes.io/zone` và taint theo bảng trên.
- [ ] Cụm Kubernetes có Cilium, Istio ambient, hai StorageClass `nvme-local` và `ceph-rbd`.
- [ ] Đã cài các operator ở bảng "Phần mềm nền".
- [ ] Có Harbor đã nạp ảnh theo digest, có khoá công khai kiểm chữ ký.
- [ ] HSM đã có khe và khoá cho OpenBao; CA gốc đã sinh, CA trung gian khởi đầu đã ký, biên bản đã ký.
- [ ] Kho đối tượng có versioning và Object Lock COMPLIANCE; kho sao lưu ở địa điểm khác.
- [ ] Đã có tên miền điểm vào của cell và chứng thư cho tên miền đó.
