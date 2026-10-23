# OPS-06 · Đóng băng thay đổi trước mốc hạn

> **Truy vết:** TL-04 mục 11.3 · INF-04 · `syncWindows`, `syncWindowsBoSung` và `releaseManagers` trong `deploy/argocd/values.yaml` · bộ sinh `deploy/argocd/lich-dong-bang/sinh_lich.py`.
> **Chu kỳ:** theo lịch · **Chịu trách nhiệm:** quản lý phát hành · **Tự động hoá:** Argo CD sync window.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). `deploy/environments/kind/kiem-thu-gitops.sh` kiểm đồng bộ tự động bị chặn trong cửa sổ; `deploy/argocd/lich-dong-bang/tests` kiểm bộ sinh lịch.

## Điều kiện kích hoạt

Hai cửa sổ cố định: từ 00:00 ngày 24 tháng cuối quý tới hết ngày 10 tháng đầu quý sau (432 giờ cho cửa sổ bắt đầu 24/3 và 24/12, 408 giờ cho 24/6 và 24/9, vì tháng bắt đầu dài 31 hoặc 30 ngày), và từ 20/1 tới hết 04/2 (384 giờ), giờ Việt Nam. Cửa sổ bổ sung theo mốc hạn của từng tenant do `sinh_lich.py` sinh. Sổ tay cũng dùng khi CAB khẩn duyệt một bản sửa lỗi nghiêm trọng hoặc vá an ninh CVSS ≥ 9 trong cửa sổ.

## Tác động

Trong cửa sổ, Argo CD không đồng bộ tự động Application của `okr-apps`; chỉ vai trò `release-manager` (nhóm `ops-release-managers` của Keycloak vận hành, đăng nhập có MFA) đồng bộ tay được.

## Kiểm tra nhanh

```bash
argocd proj windows list okr-apps          # cửa sổ đang áp, lịch, manualSync
argocd app get <service>-<cell>-<site>     # dòng "Sync Window" cho biết đang bị chặn hay không
```

## Các bước

**Trước mỗi năm (hoặc khi tenant đổi mốc hạn):**

1. Chạy `deploy/argocd/lich-dong-bang/sinh_lich.py --nam <năm> --api <cổng cell>/api/v1 [--api ...] --mo-yeu-cau` với `VT_API_TOKEN` lấy từ OpenBao (token dịch vụ chỉ đọc kỳ), không ghi token vào tệp.
2. Duyệt merge request sinh ra (khoá `syncWindowsBoSung` trong `deploy/argocd/values-dong-bang.yaml`), đối chiếu với lịch tăng trước KEDA của cùng kỳ (`deploy/tools/prescale/sinh_cua_so.py`), rồi gộp.

**Khi CAB khẩn duyệt một thay đổi trong cửa sổ:**

1. Ghi số biên bản CAB khẩn vào phiếu; người đồng bộ phải thuộc `ops-release-managers`.
2. Đăng nhập Argo CD bằng SSO có MFA, đồng bộ tay từng Application theo thứ tự đợt của OPS-02: `argocd app sync <service>-<cell>-<site>`.
3. Theo dõi canary theo OPS-03 như phát hành thường.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem cửa sổ | (chưa có) | `argocd proj windows list okr-apps` |
| Đồng bộ tay khi CAB khẩn duyệt | (chưa có) | `argocd app sync <service>-<cell>-<site>` |
| Sinh lịch bổ sung | (chưa có) | `deploy/argocd/lich-dong-bang/sinh_lich.py` |

## Kiểm sau khi làm

`argocd proj windows list okr-apps` hiện đủ cửa sổ của năm; Application đồng bộ tay có digest đúng bản đã duyệt; biên bản CAB ghi thời điểm đồng bộ.

## Quay lui

Thay đổi khẩn lỗi thì quay lui theo OPS-05 (cũng đồng bộ tay). Cửa sổ sinh sai thì revert merge request lịch; không xoá cửa sổ cố định.

## Leo thang

Không có người `release-manager` trực khi cần đồng bộ khẩn: gọi quản lý thay đổi theo sơ đồ gọi trực của OPS-13.

## Liên quan

[OPS-17](ops-17-chuan-bi-truoc-moc-han.md), [OPS-19](ops-19-hoi-dong-thay-doi.md), [release-freeze](../canh-bao/release-freeze.md); quy trình con người của cửa sổ đóng băng (việc được phép, đồng bộ thủ công có kiểm soát, mở lại sau cửa sổ): `ops/van-hanh/dong-bang.md`.
