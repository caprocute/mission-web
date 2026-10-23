# MirrorMaker 2 luồng phân tích quốc gia (DV-15-11)

Thư mục này sinh manifest Strimzi cho luồng thứ hai trong ba luồng dữ liệu được phép giữa các cell (TL-03 "Đồng bộ giữa các cell", baseline mục 8 cơ chế 7): MirrorMaker 2 của mỗi cell chỉ sao chép họ topic `agg.*`, tức số liệu tổng hợp C0 cấp tỉnh đã che nhóm nhỏ, sang Kafka của kho phân tích quốc gia ở vùng toàn cục (TL-01). Không topic nghiệp vụ chi tiết nào, không topic `cdc.*` nào rời cell.

Dữ liệu đi theo đường: bảng `fact_*` của ClickHouse cell → job làm mới `agg_*` mỗi giờ và khung xuất `v_agg_xuat` (`analytics/clickhouse/migrations/V015__tong_hop_quoc_gia.sql`) → bộ xuất `analytics/clickhouse/agg_export.py` phát Avro lên `agg.province.metric.v1` của cell → MirrorMaker 2 ở đây → topic `<cell>.agg.province.metric.v1` ở Kafka quốc gia.

## Thành phần

| Tệp | Nội dung |
|---|---|
| `values.yaml` | Tham số chung và danh sách cell: phiên bản Kafka, địa chỉ cụm nguồn và đích, tên Secret chứng thư, pattern topic |
| `sinh_mm2.py` | Bộ sinh `generated/<cell>.yaml`; `--check` báo lỗi khi tệp sinh lệch `values.yaml` |
| `generated/<cell>.yaml` | `KafkaUser mm2-agg-quoc-gia` (cụm cell, chỉ đọc tiền tố `agg.`) và `KafkaMirrorMaker2 mm2-agg-<cell>-quoc-gia` |
| `tests/test_mm2.py` | Kiểm tra tĩnh: tệp sinh mới nhất, `kubeconform -strict`, pattern chỉ nhận `agg.*` của danh mục topic, không đồng bộ offset nhóm, mTLS hai chiều, tách luồng DR, ACL tối thiểu |
| `tests/test_mm2_tich_hop.py` | Kiểm thử tích hợp: hai cụm Kafka 3.9 và MirrorMaker 2 dedicated dựng từ đúng manifest; bản ghi `agg.*` sang đích, `cdc.*`, topic nghiệp vụ, dlq, offset nhóm không sang |
| `tests/crd-schemas/` | Lược đồ JSON của CRD Strimzi 1.2.0 (`KafkaMirrorMaker2`, `KafkaUser` bản `v1`) sinh bằng `deploy/charts/vt-platform/ci/tao-schema-crd.py` |

## Khác luồng DR của TL-04

Luồng DR `mm2-hl-to-sn` (TL-04 "Kafka: MirrorMaker 2") sao chép gần như mọi topic nghiệp vụ giữa hai trung tâm dữ liệu của cùng một cell, giữ nguyên tên topic bằng `IdentityReplicationPolicy` và đồng bộ offset nhóm tiêu thụ để chuyển vùng. Luồng phân tích làm ngược lại ở mọi điểm: tên riêng, tài khoản riêng, chỉ `agg\..*`, `DefaultReplicationPolicy` để topic đích mang tiền tố alias cell (kho quốc gia nhận từ nhiều cell mà không đè nhau), không `checkpointConnector`, không `groupsPattern`, `emit.offset-syncs.enabled = false` nên không ghi gì vào cụm của cell. Tài khoản nguồn chỉ có quyền đọc trên tiền tố `agg.`, nên kể cả khi pattern bị sửa sai, MirrorMaker 2 cũng không đọc được topic khác.

## Chạy

```bash
python3 analytics/mirrormaker/sinh_mm2.py            # sinh lại generated/
cd analytics/mirrormaker
uv venv .venv && uv pip install --python .venv/bin/python pytest pyyaml 'testcontainers>=4.9' 'confluent-kafka>=2.6' ruff
.venv/bin/python -m pytest -q                       # phần tích hợp cần Docker, không có thì bị bỏ qua
```

`kubeconform` cài bằng `go install github.com/yannh/kubeconform/cmd/kubeconform@v0.7.0`; máy không có thì kiểm thử đó bị bỏ qua.

## Việc chờ hạ tầng

Cụm Kafka quốc gia, tài khoản đích và chứng thư (`mm2-agg-quoc-gia-dich`, `kafka-quoc-gia-ca-cert`) do bên vận hành quốc gia cấp; địa chỉ `target.bootstrapServers` trong `values.yaml` là chỗ giữ chỗ. Đưa manifest vào ApplicationSet của cell, NetworkPolicy cho đường ra tới Kafka quốc gia và chốt danh sách cell là việc của INF-10 và INF-18. Chi tiết: `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục DV-15-11.
