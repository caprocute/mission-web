# Mô hình 3: cài cách ly cho lực lượng vũ trang, Công an

> Nguồn: TL-04 mục 2.1, 2.4 (mô hình 3), 9.9 (cài đặt cách ly, SEC-45, SEC-46), 10.1 (giấy phép công cụ), 11.6 và OPS-12 (bản LTS); TL-05 mục 9.3; `deploy/charts/vt-platform/values-airgap.yaml` (INF-02), `infra/airgap/` và [cach-ly.md](cach-ly.md) (INF-23), `deploy/Jenkinsfile` (SBOM CycloneDX, INF-05). Task: VH-06.

Bản cách ly phục vụ đơn vị có dữ liệu thuộc bí mật quân sự hoặc bí mật nhà nước. Bản này chạy trên mạng nội bộ không kết nối Internet, nhiều khi không có cả kết nối tới hạ tầng của Viettel, và dùng mật mã của Ban Cơ yếu Chính phủ. Về kỹ thuật, đây vẫn là một cell đơn dựng từ cùng chart `vt-platform`, với hồ sơ cứng hoá: tắt mọi kết nối ra ngoài, tắt các bộ kết nối VNeID, MySign, LGSP, bật `crypto.provider: bcy`, chạy mô hình AI tại chỗ hoặc tắt hẳn, và tắt ứng dụng di động công khai bằng cờ tính năng.

Quy trình đóng gói, kiểm gói, nạp ảnh và cài bằng công cụ trong gói đã được viết đầy đủ ở [cach-ly.md](cach-ly.md) (INF-23). Trang này không lặp lại các bước đó mà bổ sung ba phần còn thiếu cho người quản trị phía khách hàng: kiểm SBOM và chứng thực của ảnh ngay trong mạng cách ly, khác biệt khi chuẩn bị hạ tầng, và quy trình nhận bản vá LTS.

## Khác biệt khi chuẩn bị hạ tầng

So với [cai-dat-chung.md](cai-dat-chung.md), bản cách ly khác ở các điểm sau:

| Bước | Khác biệt |
|---|---|
| Hồ sơ | `compact` hoặc `S` tuỳ quy mô đơn vị; `values-airgap.yaml` đặt sẵn `sizing: compact` |
| Kho ảnh | Harbor nội bộ của khách hàng, cộng khoảng 2 TB cho các bản LTS giữ lại (tối thiểu 3 bản cộng bản vá) |
| Bước 2 | Không cài `cell-egress`; `global.egress.allowInternet: false` làm `vt-service` bỏ mọi luật ra Internet |
| Bước 4 | `tools/cai-dat.sh` sinh bản chính sách Kyverno dùng khoá công khai phát hành `vt-release.pub` và Harbor nội bộ |
| Bước 5, 6 | HSM và CA gốc là của khách hàng; mật mã qua thiết bị do Ban Cơ yếu cấp (SEC-46) |
| Bước 8 | Không có Argo CD từ kho Viettel: `tools/cai-dat.sh` render chart trong gói, đổi tên ảnh sang Harbor nội bộ bằng `doi-registry.py` rồi áp bằng `kubectl apply --server-side` |
| Quan sát | Chỉ thành phần Apache-2.0 (Prometheus, Thanos sidecar, Fluent Bit); gói không mang Grafana, Loki, Tempo, k6 khi pháp chế chưa duyệt phân phối thành phần AGPL |
| Giám sát an ninh | SOC của khách hàng; Viettel hỗ trợ từ xa qua kênh được phép hoặc tại chỗ |

Bản cách ly mặc định dùng hồ sơ compact, nên cụm phải gắn nhãn node `hoi-tu` và `du-lieu` theo bảng compact ở [yeu-cau-ha-tang.md](yeu-cau-ha-tang.md). Bản cách ly đổi tên ảnh bằng `doi-registry.py` trên bản render, nên không cần `global.doiKhoAnh` của mô hình đám mây riêng.

## Kiểm SBOM và chứng thực trong mạng cách ly

`tools/kiem-goi.sh` chứng minh gói nguyên vẹn: chữ ký bản kê đúng khoá phát hành, mọi tệp khớp băm, không có tệp lạ. `tools/kiem-tra-sau-cai.sh` chứng minh mọi ảnh đang chạy có chữ ký của Viettel. Hai bước này chưa trả lời câu hỏi mà cơ quan kiểm định của khách hàng thường đặt ra: ảnh gồm những thành phần nào, và các thành phần đó có lỗ hổng đã biết hay không tại thời điểm nhận gói. Câu trả lời nằm trong SBOM CycloneDX mà pipeline gắn vào từng ảnh bằng `cosign attest --type cyclonedx`, và trong cơ sở dữ liệu Trivy đóng kèm gói.

Sau khi `tools/load-images.sh` đã nạp ảnh và chữ ký vào Harbor nội bộ, với mỗi dòng của `anh-da-nap.tsv` (cột thứ hai là ảnh trong Harbor nội bộ theo digest):

```bash
# Kiểm chứng thực SBOM bằng khoá công khai phát hành, rồi tách SBOM ra tệp (chưa chạy thử trên cụm).
ANH=<Harbor nội bộ>/okr/mission-core@sha256:<digest>
cosign verify-attestation --key vt-release.pub --type cyclonedx --insecure-ignore-tlog=true "$ANH" \
  | jq -r '.payload' | base64 -d | jq '.predicate' > sbom-mission-core.cdx.json
# Quét SBOM bằng cơ sở dữ liệu lỗ hổng đóng trong gói, không cần mạng.
trivy sbom --cache-dir okr-lts-<phiên bản>/trivy-db --skip-db-update --offline-scan sbom-mission-core.cdx.json
```

Chứng thực SLSA (`https://slsa.dev/provenance/v1`) được Kyverno kiểm ở mỗi lần tạo pod: ảnh chỉ chạy khi chứng thực ghi đúng kho mã và máy dựng của Viettel. Người kiểm định có thể xem chứng thực này bằng cùng lệnh `cosign verify-attestation` với `--type slsaprovenance1`.

Kết quả quét lưu cùng biên bản kiểm tra sau cài. Lỗ hổng mức Critical hoặc High chưa có giải trình trong ghi chú phát hành là lý do để từ chối nhận gói, không phải để cài rồi xử lý sau.

## Nhận bản vá LTS

Bản LTS được chọn mỗi 6 tháng từ phiên bản đã chạy ổn định ở SaaS ít nhất một quý, và được hỗ trợ bản vá an ninh 18 tháng (OPS-12). Bản vá có số hiệu `okr-lts-<năm>.<số>.<vá>`, chỉ chứa ảnh và chart thay đổi cùng bản kê mới, ký bằng cùng khoá phát hành. Quy trình nhận một bản vá phía khách hàng:

1. **Nhận gói** qua phương tiện vật lý được kiểm soát, đi qua trạm kiểm định một chiều của khách hàng.
2. **Kiểm gói** bằng `tools/kiem-goi.sh --khoa vt-release.pub`. Sai một byte là dừng; không chạy bước sau.
3. **Kiểm SBOM** của ảnh thay đổi theo mục trên, với cơ sở dữ liệu Trivy mới nhất mà khách hàng đã nhận qua gói cập nhật hằng tuần.
4. **Nạp ảnh** bằng `tools/load-images.sh`. Ảnh cũ vẫn giữ trong Harbor nội bộ để quay lui.
5. **Xem trước** bằng `tools/cai-dat.sh ... --chi-render <tệp>` và so với bản đang chạy (`kubectl diff --server-side -f <tệp>`).
6. **Áp** ngoài cửa sổ đóng băng của đơn vị, rồi chạy lại `tools/kiem-tra-sau-cai.sh` và lưu biên bản.
7. **Quay lui** khi kiểm tra không đạt: áp lại bản render của phiên bản trước từ gói cũ. Migration CSDL chỉ thêm mới và thay đổi phá vỡ đi theo expand and contract qua ba bản phát hành (`docs/quy-dinh/BACKEND.md`, wiki 17), nên quay lui ứng dụng về bản trước không cần khôi phục CSDL (suy luận).

Ba tháng trước khi bản LTS hết 18 tháng hỗ trợ, Viettel thông báo lộ trình lên bản LTS kế tiếp. Lên bản LTS mới là một lần cài nâng cấp theo đúng các bước trên với gói đầy đủ, không phải gói vá.

## Cơ sở dữ liệu quét hằng tuần

Cơ sở dữ liệu lỗ hổng Trivy và chữ ký mã độc ClamAV được cập nhật hằng tuần bằng một gói riêng có bản kê ký số, chuyển qua phương tiện vật lý. Khách hàng chạy `tools/kiem-goi.sh` trên gói này rồi chép `trivy-db/` và `clamav/` vào máy quét nội bộ và vào bộ quét ClamAV của `evidence-service` (suy luận: cách nạp chữ ký vào bộ quét trong cụm chưa được viết thành công cụ). Gói cập nhật quá hai tuần chưa được nạp là một mục cần báo trong giao ban an ninh của đơn vị.

## Liên quan

- [cach-ly.md](cach-ly.md): đóng gói, kiểm gói, nạp ảnh, cài và kiểm tra sau cài bằng công cụ trong gói (INF-23)
- [cai-dat-chung.md](cai-dat-chung.md), [quan-tri-thuong-ngay.md](quan-tri-thuong-ngay.md), [ma-tran-trach-nhiem.md](ma-tran-trach-nhiem.md)
