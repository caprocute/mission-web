# OPS-12 · Bản LTS cho mô hình cách ly

> **Truy vết:** TL-04 mục 11.6, 9.9 · INF-23 `infra/airgap/` (`ke-anh.py`, `dong-goi.sh`, `kiem-goi.sh`) · `deploy/release/README.md` · `docs/quan-tri-cai-dat/cach-ly.md`.
> **Chu kỳ:** 6 tháng · **Chịu trách nhiệm:** quản lý phát hành · **Tự động hoá:** pipeline đóng gói.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Kiểm thử tự động của công cụ đóng gói: `infra/airgap/tests/chay-kiem-thu.sh` (registry cục bộ, khoá thử sinh lúc chạy).

## Điều kiện kích hoạt

Đến kỳ chọn LTS (mỗi 6 tháng), hoặc có CVE cần vá cho một nhánh LTS đang hỗ trợ (theo hạn của OPS-26).

## Tác động

Khách hàng cách ly chỉ nhận bản qua phương tiện vật lý. Bản LTS được hỗ trợ vá an ninh 18 tháng; ba tháng trước khi hết hạn phải thông báo lộ trình lên LTS kế tiếp.

## Kiểm tra nhanh

Phiên bản ứng viên đã chạy ổn định ở SaaS ít nhất một quý, không có sự cố SEV-1, SEV-2 do mã của bản đó (đối chiếu sổ sự cố). `deploy/release/<phiên bản>/images.yaml` chưa tồn tại cho phiên bản mới.

## Các bước

**Chọn và đóng gói bản LTS:**

1. Đặt thẻ `okr-lts-<năm>.<số>.0` và tạo nhánh `lts/<năm>.<số>` từ thẻ.
2. Dựng bằng cùng pipeline với SaaS (INF-05): ảnh, cosign, SBOM, provenance; ảnh bản cách ly với `crypto.provider: bcy`.
3. Sinh bản kê: `infra/airgap/ke-anh.py --phien-ban <phiên bản>` (từ chối khi còn digest giả), commit `deploy/release/<phiên bản>/images.yaml` cùng thẻ.
4. Đóng gói và ký bản kê bằng khoá phát hành ngoại tuyến bằng `infra/airgap/dong-goi.sh` theo `docs/quan-tri-cai-dat/cach-ly.md` mục 2.
5. Bàn giao cho khách hàng kèm hướng dẫn mục 3 (kiểm `kiem-goi.sh`, nạp, cài).

**Vá an ninh cho LTS:** cherry-pick có duyệt vào `lts/<năm>.<số>`, cùng pipeline, cùng khoá; số hiệu `okr-lts-<năm>.<số>.<vá>`; gói vá chỉ chứa ảnh, chart đổi và bản kê mới.

**Cơ sở dữ liệu quét hằng tuần:** gói `trivy-db/`, `clamav/` kèm bản kê có ký, chuyển qua phương tiện vật lý.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Sinh bản kê ảnh | (không thuộc vtctl) | `infra/airgap/ke-anh.py --phien-ban <phiên bản>` |
| Đóng gói | (không thuộc vtctl) | `infra/airgap/dong-goi.sh` (tham số theo `cach-ly.md`) |

## Kiểm sau khi làm

`kiem-goi.sh` phía khách hàng xác nhận chữ ký bản kê và digest của mọi ảnh; bản cài trên môi trường thử cách ly chạy kiểm thử khói đạt.

## Quay lui

Khách hàng giữ bản LTS trước tới khi bản mới được nghiệm thu; không xoá ảnh của bản trước trong Harbor nội bộ cho tới khi hết hạn hỗ trợ.

## Leo thang

Không dựng lại được bản vá trong hạn của OPS-26 (Critical 7 ngày): báo quản lý phát hành và SOC, thông báo khách hàng biện pháp giảm thiểu tạm.

## Liên quan

`docs/quan-tri-cai-dat/cach-ly.md`, `deploy/release/README.md`, [OPS-26](ops-26-va-thu-vien-cve.md).
