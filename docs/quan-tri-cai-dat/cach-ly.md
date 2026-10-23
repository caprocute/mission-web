# Cài đặt và nâng cấp bản cách ly Mission (bản LTS)

Nguồn: TL-04 mục 2.4 "Mô hình 3", mục 9.9 "Cài đặt cách ly cho lực lượng vũ trang và Công an", mục 11 "Bản LTS"
(OPS-12); SEC-45 (gói ngoại tuyến), SEC-46 (mật mã Ban Cơ yếu), SEC-38 và SEC-55 (ảnh ký, theo digest); TL-05 "Bản cài
cách ly". Công cụ nằm ở `infra/airgap/` (INF-23).

## 1. Bản cách ly khác SaaS ở đâu

Bản cách ly là một cell đơn dựng từ cùng chart `vt-platform` với `values-airgap.yaml`: hồ sơ compact (3 node, một vùng sự
cố), tắt mọi kết nối ra ngoài (`egress.allowInternet: false`), tắt bộ kết nối VNeID, MySign, LGSP, tắt ứng dụng di động
công khai, mật mã qua lớp nhà cung cấp của `vt-crypto` với `crypto.provider: bcy`, định danh qua hạ tầng chuyên dùng của
khách hàng. Mô hình AI chạy tại chỗ trên GPU của khách hàng; đơn vị không có GPU tắt `ai-gateway.thanhPhan.vllm` trong
values của mình. Ngăn xếp quan sát trong cell chỉ gồm thành phần Apache-2.0; gói không mang Grafana, Loki, Tempo, k6
(AGPL) khi pháp chế chưa duyệt.

## 2. Phía Viettel: đóng gói một bản

1. Sinh tệp kê ảnh từ bản render của chart, mọi ảnh phải theo digest:
   `infra/airgap/ke-anh.py --phien-ban okr-lts-2027.1.0` → `deploy/release/okr-lts-2027.1.0/images.yaml`.
   Script dừng nếu còn ảnh theo thẻ hoặc digest giả. Tệp kê được commit cùng thẻ phát hành.
2. Đóng gói trên máy đóng gói có HSM:

   ```bash
   infra/airgap/dong-goi.sh --phien-ban okr-lts-2027.1.0 --ref okr-lts-2027.1.0 \
     --clamav /srv/clamav-mirror --ra /srv/phat-hanh            # khoá mặc định hsm://release-offline
   ```

   Gói gồm `images/` (một thư mục OCI, bảng `images/anh.tsv`), `signatures/` (cosign save: chữ ký, SBOM, provenance),
   `charts/` (chỉ `vt-platform`, đã gồm chart con), `gitops.bundle`, `trivy-db/`, `clamav/`, `tools/`, `docs/`,
   `MANIFEST.sha256` và `MANIFEST.sha256.sig`, nén thành `okr-lts-2027.1.0.tar.zst`.
3. Chuyển gói bằng phương tiện vật lý được kiểm soát qua trạm kiểm định của khách hàng. Khoá công khai `vt-release.pub`
   giao một lần qua kênh riêng, không đi cùng gói.

## 3. Phía khách hàng: kiểm tra, nạp, cài

```bash
tar --zstd -xf okr-lts-2027.1.0.tar.zst && cd okr-lts-2027.1.0
tools/kiem-goi.sh --khoa vt-release.pub
tools/load-images.sh --kho harbor.qp.local/okr --bo-tien-to okr/
tools/cai-dat.sh --kho harbor.qp.local/okr --khoa vt-release.pub --values khach-hang.yaml
tools/kiem-tra-sau-cai.sh --ra ../bien-ban --khoa vt-release.pub --url-khoi https://okr.qp.local --anh-kube-bench <ảnh>
```

- `kiem-goi.sh` kiểm chữ ký bản kê bằng khoá công khai, rồi `sha256sum -c --strict`, rồi kiểm không có tệp nào ngoài bản
  kê. Sai một byte là dừng; không chạy bước sau khi bước này chưa đạt.
- `load-images.sh` nạp từng ảnh vào Harbor nội bộ theo quy ước `<kho>/<đường dẫn gốc bỏ máy chủ và tiền tố>`, kiểm lại
  digest sau khi đẩy, nạp chữ ký bằng `cosign load`, ghi bảng đối chiếu `../anh-da-nap.tsv`.
- `cai-dat.sh` sinh bản chính sách Kyverno của khách hàng (`doi-khoa-kyverno.py`: khoá `kms` của OpenBao Transit đổi
  thành `publicKeys` là khoá phát hành, registry đổi sang Harbor nội bộ; điều kiện chứng thực SLSA giữ nguyên), áp chính
  sách, rồi render chart từ `charts/` bằng `helm template`, lọc qua `doi-registry.py` để trỏ mọi ảnh (cả ảnh bên thứ
  ba và manifest hook) sang Harbor nội bộ theo bảng đối chiếu, và áp bằng `kubectl apply --server-side`. Không dùng
  `--post-renderer` của Helm 3 vì nó bỏ qua manifest hook. Ảnh không ký bằng khoá phát hành bị Kyverno từ chối.
  `--chi-render <tệp>` để xem trước mà không áp.
- `kiem-tra-sau-cai.sh` chạy kube-bench, Hubble (không luồng tới identity `world`), kiểm chữ ký mọi ảnh đang chạy, kiểm
  thử khói, PERF-01 rút gọn (k6 của khách hàng), diễn tập khôi phục một cụm PostgreSQL; ghi `bien-ban-sau-cai.md`, mục
  không chạy được ghi rõ lý do. Biên bản chỉ đạt khi mọi mục đạt.

## 4. Quy trình bản LTS (OPS-12)

- **Chọn bản.** Mỗi 6 tháng chọn phiên bản đã chạy ổn định ở SaaS ít nhất một quý (không sự cố mức 1, 2 do mã của bản
  đó). Đặt thẻ `okr-lts-<năm>.<số>.0` và tạo nhánh `lts/<năm>.<số>` từ thẻ.
- **Dựng.** Cùng pipeline với SaaS (INF-05): dựng ảnh, ký cosign, SBOM, provenance SLSA; ảnh của bản cách ly dựng với
  `crypto.provider: bcy`. Sau đó `ke-anh.py` và `dong-goi.sh` ký bản kê bằng khoá phát hành ngoại tuyến.
- **Hỗ trợ 18 tháng.** Bản vá an ninh dựng lại từ nhánh LTS (cherry-pick có duyệt), cùng pipeline, cùng khoá; số hiệu
  `okr-lts-<năm>.<số>.<vá>`. Gói vá chỉ chứa ảnh, chart đổi và bản kê mới, quy trình kiểm tra phía khách hàng như trên.
- **Cơ sở dữ liệu quét hằng tuần.** Gói cập nhật riêng chứa `trivy-db/` và `clamav/` kèm bản kê có ký, chuyển qua phương
  tiện vật lý; khách hàng chạy `kiem-goi.sh` rồi chép vào máy quét nội bộ.
- **Hết hạn.** Ba tháng trước khi hết 18 tháng, thông báo khách hàng lộ trình lên bản LTS kế tiếp.

## 5. Chưa có trong giai đoạn phát triển

Thuật toán Ban Cơ yếu trong `vt-crypto` (cần thư viện, thiết bị Ban Cơ yếu cấp), ảnh hệ điều hành node, trạm kiểm định
một chiều và Harbor của khách hàng nằm ngoài phạm vi INF-23. Kiểm thử hiện chạy với registry cục bộ và khoá thử sinh lúc
chạy (`infra/airgap/tests/chay-kiem-thu.sh`); bước cài lên kind tắt egress và kiểm Kyverno từ chối ảnh lạ chưa chạy.
