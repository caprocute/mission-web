# Mô hình 2: cài trên đám mây riêng của tỉnh hoặc bộ

> Nguồn: TL-04 mục 2.1, 2.3 (mô hình 2), 9.6 (kho GitOps, chữ ký commit), chương 11 (OPS-01, OPS-02); TL-05 mục 9.1, 9.2; `deploy/charts/vt-platform/values-private.yaml` (INF-02), `deploy/argocd` (INF-04), `deploy/cells/nb` (mẫu cell compact), `deploy/clusters/mgmt/harbor` (INF-07). Task: VH-06.

Ở mô hình này, nền tảng được cài thành một cell đơn trên hạ tầng do khách hàng quản lý: tỉnh có trung tâm dữ liệu riêng, bộ ngành, bệnh viện lớn. Cell giữ nguyên toàn bộ biện pháp an toàn thông tin của bản SaaS. Khác biệt chính về vận hành là quyền kiểm soát phát hành: Argo CD đặt tại chỗ, đồng bộ từ một bản nhân bản của kho GitOps, và đội vận hành của khách hàng duyệt từng lần cập nhật phiên bản. Dữ liệu nghiệp vụ không rời hạ tầng của khách hàng; chỉ số liệu tổng hợp ẩn danh được gửi lên kho phân tích quốc gia khi khách hàng đồng ý bằng văn bản.

Trang này đi từng bước cho trường hợp phổ biến nhất ở giai đoạn đầu là hồ sơ `compact` (đến 50.000 tài khoản). Với hồ sơ `S` hoặc `M`, các bước giống hệt, chỉ khác tệp hồ sơ và số máy.

## Khác biệt của values-private

Tệp `deploy/charts/vt-platform/values-private.yaml` chỉ chứa các khoá của bảng TL-04 mục 2.1. Những khoá khách hàng thường đổi trong values của cell:

| Khoá | Mặc định ở values-private | Khi nào đổi |
|---|---|---|
| `global.objectStore.kind`, `inCluster` | `rook-ceph`, `true` | Đổi sang `s3` khi khách hàng có kho S3 tương thích hỗ trợ Object Lock COMPLIANCE |
| `global.egress.allowInternet` | `true` (qua egress gateway như SaaS) | Đặt `false` khi chính sách của khách hàng cấm hẳn luồng ra |
| `global.identity.providers` | VNeID, MySign, Viettel-CA, CA Chính phủ, IdP của khách hàng | Bỏ bớt nhà cung cấp không dùng |
| `integration-gateway.config.VT_KET_NOI_*` | Bật VNeID, MySign, LGSP | Tắt bộ kết nối khách hàng không ký hợp đồng |
| `ai-gateway.thanhPhan.vllm.enabled` | `false` | Bật khi khách hàng cấp GPU |
| `global.sizing` | `M` | Luôn đặt theo hồ sơ đã chọn, qua `cell.yaml` |

## Bước 0: chuẩn bị kho GitOps nhân bản

Đội triển khai của Viettel tạo kho GitOps riêng cho khách hàng từ thẻ phát hành đã chọn. Kho nhân bản chỉ chứa mã chart và thư mục cell của chính khách hàng: không chép thư mục `deploy/cells/` của các cell SaaS khác, vì cấu hình đó không thuộc quyền biết của khách hàng, và vì ApplicationSet sinh Application cho mọi tệp `deploy/cells/*/cell.yaml` mà nó thấy (suy luận từ cách chart `deploy/argocd` lọc cell bằng `cellsExclude`).

Tạo ba tệp cho cell mới trong kho nhân bản. Ba mẫu dưới đây đã có sẵn trong [vi-du/](vi-du/):

| Tệp trong kho nhân bản | Mẫu | Nội dung |
|---|---|---|
| `deploy/cells/<mã>/cell.yaml` | [vi-du/cell.yaml](vi-du/cell.yaml) | Mã cell, hồ sơ, mô hình thuê bao, địa điểm, `argocd: tai-cho` |
| `deploy/cells/<mã>/values.yaml` | [vi-du/cell-rieng-compact.yaml](vi-du/cell-rieng-compact.yaml) | Values riêng của cell: mã cell, vùng sự cố, cờ tính năng, CSDL tenant, số node OpenBao |
| `deploy/argocd/values-tai-cho.yaml` | [vi-du/argocd-tai-cho.yaml](vi-du/argocd-tai-cho.yaml) | Kho nhân bản, instance, mô hình `private`, một địa điểm, khoá GPG được phép ký commit |

Tệp digest `deploy/environments/rieng-<mã>/digests.yaml` có cùng khuôn với `deploy/environments/production/digests.yaml`, chép từ môi trường production tại thẻ phát hành. Mỗi digest phải là digest thật của ảnh đã ký; chart từ chối render khi digest rỗng hoặc không có dạng `sha256:<64 ký tự hex>`.

## Bước 1: kiểm tra trước khi đồng bộ

Trước khi đưa cấu hình lên cụm, render toàn bộ cell trên máy quản trị và kiểm schema. Lệnh dưới đây là lệnh đã chạy với mẫu `vi-du/cell-rieng-compact.yaml`; khi chạy với một cell thật, thay tệp `ci/values-ci.yaml` (digest giả của CI) bằng các tham số digest thật của môi trường.

```bash
cd deploy/charts/vt-platform
ci/cap-nhat-phu-thuoc.sh
helm template vt . -n observability \
  -f values-private.yaml \
  -f sizing/values-sizing-compact.yaml \
  -f ../../cells/<mã>/values.yaml \
  -f ci/values-ci.yaml \
  --set global.site=chinh --set global.siteRole=primary > /tmp/cell-<mã>.yaml
kubeconform -strict -summary \
  -schema-location default \
  -schema-location 'ci/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
  /tmp/cell-<mã>.yaml
```

Kết quả khi viết tài liệu: 873 đối tượng, `Valid: 873, Invalid: 0`. Ngoài schema, kiểm thêm hai điều bằng mắt trong bản render: mọi `nodeSelector` khớp nhãn node thật của cụm (xem hai khoảng trống ở [yeu-cau-ha-tang.md](yeu-cau-ha-tang.md)), và không còn ảnh nào trỏ tới registry mà cụm không kéo được.

Chart cấu hình Argo CD được kiểm tương tự:

```bash
cd deploy/argocd
helm template vt-gitops . -n argocd -f values.yaml -f values-tai-cho.yaml > /tmp/argocd-tai-cho.yaml
grep -c "kind: ApplicationSet" /tmp/argocd-tai-cho.yaml     # 24, mỗi thành phần một ApplicationSet
```

## Bước 2: kho ảnh của khách hàng

Cell không kéo ảnh thẳng từ Internet. Có hai cách cấp ảnh, chọn theo chính sách mạng của khách hàng:

1. **Kéo qua egress gateway từ Harbor của Viettel.** Đây là cách đơn giản nhất: thêm Harbor của Viettel vào danh sách đích của `cell-egress`, cấp một robot account chỉ kéo cho cell. Không cần đổi tên ảnh, Kyverno kiểm chữ ký như SaaS.
2. **Harbor nội bộ của khách hàng nhân bản từ Harbor của Viettel.** Dựng Harbor theo `deploy/clusters/mgmt/harbor` (chart chính thức, `existingSecret` từ OpenBao, project và quy tắc nhân bản áp bằng `ap-dung-cau-hinh.sh`), rồi nhân bản theo sự kiện đẩy ảnh như cách INF-18 nhân bản sang Harbor phía Nam.

Với cách thứ hai có một khoảng trống: chart chưa có khoá đổi registry chung, và ảnh bên thứ ba trong values của chart hạ tầng được ghim theo tên Harbor của Viettel. Bản cách ly giải quyết việc này bằng `doi-registry.py` và `doi-khoa-kyverno.py` của gói (render rồi đổi tên ảnh trước khi áp), nhưng đường Argo CD của mô hình 2 không đi qua bước render đó. Cho tới khi chart có khoá đổi registry, dùng cách thứ nhất, hoặc cấu hình gương registry của containerd trên node cho tên Harbor của Viettel (suy luận, chưa chạy thử; Kyverno vẫn phải kéo được chữ ký theo tên gốc).

## Bước 3: cụm, nền và gốc tin cậy

Làm các bước 1 đến 7 của [cai-dat-chung.md](cai-dat-chung.md). Với hồ sơ compact:

- Cụm có 3 node hội tụ VM-32-128, 3 node dữ liệu PHY-DB-S có HDD, và 3 VM-16-64 cho cụm quản trị (Argo CD, Harbor, quan sát tập trung, `tenant-registry`). Ở mức tối thiểu đến 10.000 tài khoản, cả ba vai trò chạy hội tụ trên 3 máy, và Argo CD quản lý chính cụm nó đang chạy.
- Chỉ có một vùng sự cố: mọi node mang cùng nhãn `topology.kubernetes.io/zone`, trùng `global.topology.zones` của cell (mẫu dùng `kh01-a`).
- OpenBao chạy 3 node Raft (mẫu đặt `openbao.replicas: 3`), mỗi node trên một máy dữ liệu, vì vùng bảo mật chạy chung node dữ liệu (TL-05 mục 9.2, ngoại lệ thứ hai). Chỉ node dữ liệu có đường mạng tới HSM.
- Kho đối tượng là RGW của Rook-Ceph trong cụm trên ổ HDD của node dữ liệu, trừ khi khách hàng có kho S3 đạt yêu cầu Object Lock.

## Bước 4: Argo CD tại chỗ

1. Cài Argo CD (bản đã kiểm 3.1.9) và Argo Rollouts (1.8.4) vào cụm quản trị. Mô hình 2 tắt RollingSync, nên không cần cờ `applicationsetcontroller.enable.progressive.syncs`.
2. Đăng ký cụm cell vào Argo CD với tên `cell-<mã>-<địa điểm>` (ví dụ `cell-kh01-chinh`). Khi Argo CD chạy cùng cụm với cell, đó là một Secret cụm trỏ `https://kubernetes.default.svc` mang tên này.
3. Khai kho nhân bản cho Argo CD, kèm khoá GPG công khai của người được phép ký commit. AppProject `okr-apps` và `okr-nen-tang` chỉ chấp nhận commit có chữ ký của khoá trong `signatureKeys`.
4. Áp ứng dụng gốc: chép `deploy/argocd/ung-dung-goc.yaml`, đổi `repoURL` sang kho nhân bản và `valueFiles` thành `[values.yaml, values-dong-bang.yaml, values-tai-cho.yaml]`, rồi `kubectl apply -n argocd -f <tệp>`. Từ đây, mọi thay đổi của AppProject, ApplicationSet và cửa sổ đóng băng đều đi qua Git.

Argo CD sinh 24 Application cho cell, mỗi Application bật đúng một thành phần của `vt-platform`. Thứ tự đồng bộ theo bước 8 của [cai-dat-chung.md](cai-dat-chung.md). Cửa sổ đóng băng OPS-06 (cuối quý, đầu năm) có sẵn trong AppProject; khách hàng có thể thêm cửa sổ riêng qua `syncWindowsBoSung`.

Một rủi ro mở cần biết: với Argo CD 3.1.9, bộ sinh git files của ApplicationSet lỗi "permission denied" khi AppProject có `signatureKeys` (đã tái hiện trên kind, wiki 12). Rủi ro này áp cho cả SaaS. Không bỏ kiểm chữ ký để chữa cháy; thử bản Argo CD mới hơn hoặc cơ chế `sourceIntegrity` trước khi cài thật.

## Bước 5: thành phần vận hành và tenant đầu tiên

Cài các chart của bước 9 trong [cai-dat-chung.md](cai-dat-chung.md). Với bản cài một địa điểm, kho sao lưu ngoài địa điểm là biện pháp bù duy nhất cho việc không có bản sao ấm, nên `backup-cell` và kho sao lưu phải chạy trước khi nhận dữ liệu thật. Sau đó tạo tenant đầu tiên theo [quan-tri-thuong-ngay.md](quan-tri-thuong-ngay.md), mục "Tạo tenant".

## Bước 6: kiểm tra sau cài và bàn giao

Chạy bảng kiểm ở bước 10 của [cai-dat-chung.md](cai-dat-chung.md). Ở mô hình 2, mục "Luồng ra ngoài" đạt khi mọi luồng tới identity `world` đều xuất phát từ pod của egress gateway (hoặc không có luồng nào khi khách hàng đặt `allowInternet: false`). Biên bản bàn giao gồm: biên bản kiểm tra sau cài, danh sách người giữ khoá phục hồi OpenBao và thẻ HSM, RTO và RPO đã cam kết (compact: RTO 48 giờ khi mất địa điểm, RPO 15 phút khi kênh sao lưu hoạt động), và ma trận trách nhiệm đã ký.

## Cập nhật phiên bản

Mô hình 2 nhận cập nhật theo quý (`global.updateChannel: theo-quy`). Mỗi lần cập nhật đi theo bốn bước:

1. Viettel đẩy thẻ phát hành mới cùng tệp digest mới lên một nhánh của kho nhân bản, kèm ghi chú phát hành và danh sách thay đổi cấu hình.
2. Đội vận hành của khách hàng xem khác biệt, render thử theo bước 1, rồi gộp nhánh vào `main` bằng commit có chữ ký của người duyệt.
3. Argo CD đồng bộ theo thứ tự sóng. Dịch vụ dùng Argo Rollouts (mission-core) đi canary 5%, 25%, 50%, 100% với phân tích SLO tự động; bản lỗi bị huỷ và lưu lượng ở lại bản ổn định.
4. Ghi biên bản cập nhật. Không cập nhật trong cửa sổ đóng băng.

## Liên quan

- [cai-dat-chung.md](cai-dat-chung.md), [yeu-cau-ha-tang.md](yeu-cau-ha-tang.md)
- [quan-tri-thuong-ngay.md](quan-tri-thuong-ngay.md), [ma-tran-trach-nhiem.md](ma-tran-trach-nhiem.md)
- wiki 12, mục "Kho GitOps, phát hành theo đợt cell và canary (INF-04)"
