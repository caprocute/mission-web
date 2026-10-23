# OPS-26 · Vá thư viện và CVE

> **Truy vết:** TL-04 mục 11.9 (Renovate hằng tuần, Dependency-Track trên SBOM, hạn vá Critical 7 ngày, High 30 ngày, Medium theo chu kỳ phát hành, VEX) · SEC-23, SEC-49, SEC-55 · `ci/vt-pipeline` (giai đoạn "Ảnh, SBOM, quét ảnh": Syft, Trivy, tải SBOM lên Dependency-Track).
> **Chu kỳ:** liên tục · **Chịu trách nhiệm:** đội phát triển, SOC · **Tự động hoá:** Dependency-Track, Renovate.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Repo hiện chưa có cấu hình Renovate (`renovate.json`); bước quét Trivy và tải SBOM đã có trong `ci/vt-pipeline`.

## Điều kiện kích hoạt

Dependency-Track báo CVE mới trên SBOM của một ảnh đang chạy; Trivy chặn pipeline (HIGH, CRITICAL đã có bản vá); merge request cập nhật phụ thuộc hằng tuần.

## Tác động

CVE Critical có thể là sự cố an ninh (SEV-1 nếu đang bị khai thác). Vá đi qua phát hành thường (OPS-02); trong cửa sổ đóng băng chỉ CVSS ≥ 9 qua CAB khẩn.

## Kiểm tra nhanh

```bash
trivy image --ignore-unfixed --severity HIGH,CRITICAL <ảnh>@<digest>      # xác nhận lỗ hổng trên ảnh đang chạy
grep -n <thư viện> deploy/environments/production/digests.yaml           # dịch vụ nào dùng ảnh đó (theo digest)
```

Trên Dependency-Track: dự án theo dịch vụ, phiên bản theo digest đang chạy, danh sách thành phần bị ảnh hưởng.

## Các bước

1. Xếp hạn: Critical có bản vá trong 7 ngày, High 30 ngày, Medium theo chu kỳ phát hành.
2. Xác định đường mã có dùng thành phần bị ảnh hưởng không. Không dùng: ghi VEX có giải trình (trạng thái `not_affected` kèm lý do) vào Dependency-Track, không bỏ qua im lặng.
3. Có dùng: cập nhật phụ thuộc (merge request tay khi chưa có Renovate), pipeline dựng ảnh mới, quét lại, phát hành qua OPS-02.
4. Bản LTS đang hỗ trợ có cùng thành phần: vá theo OPS-12.
5. Đóng phiếu khi Dependency-Track không còn cảnh báo trên phiên bản đang chạy.

## Lệnh vtctl và lệnh tương đương

Không có lệnh `vtctl`; công cụ là pipeline `ci/vt-pipeline`, Trivy và Dependency-Track.

## Kiểm sau khi làm

Ảnh đang chạy ở mọi cell (theo `digests.yaml`) không còn CVE quá hạn; VEX có giải trình cho phần còn lại.

## Quay lui

Bản vá gây lỗi: quay lui theo OPS-05, ghi ngoại lệ có thời hạn và biện pháp giảm thiểu, SOC duyệt.

## Leo thang

CVE đang bị khai thác, hoặc Critical không vá kịp 7 ngày: SEV-1 an ninh, gọi SOC.

## Liên quan

[OPS-02](ops-02-phat-hanh-theo-dot-cell.md), [OPS-12](ops-12-ban-lts-cach-ly.md), [supply-chain](../canh-bao/supply-chain.md), `ci/vt-pipeline/README.md`.
