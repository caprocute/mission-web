# OPS-19 · Hội đồng thay đổi (CAB)

> **Truy vết:** TL-04 mục 11.3 (ba loại thay đổi, CAB tuần, CAB khẩn, ngân sách lỗi là đầu vào) · cảnh báo `ErrorBudgetHalfSpent`. Quy trình đầy đủ (thành phần, chương trình họp, quy tắc ngân sách lỗi, mẫu phiếu yêu cầu thay đổi) nằm ở `ops/van-hanh/hoi-dong-thay-doi.md` (VH-03); sổ tay này chỉ giữ phần người trực cần tra nhanh để khỏi chép trùng.
> **Chu kỳ:** tuần, khẩn · **Chịu trách nhiệm:** quản lý thay đổi · **Tự động hoá:** quy trình.
> **Chạy thử:** không áp dụng chạy lệnh.

## Điều kiện kích hoạt

Có thay đổi thường cần duyệt trong tuần, hoặc cần thay đổi khẩn (kể cả trong cửa sổ đóng băng của OPS-06). Cách xếp loại thay đổi: `ops/van-hanh/hoi-dong-thay-doi.md` mục 1.

## Tác động

Thay đổi chuẩn không qua CAB. Cell đã tiêu quá 50% ngân sách lỗi tháng trước ngày 15 tạm dừng thay đổi thường (`ops/van-hanh/hoi-dong-thay-doi.md` mục 2, cảnh báo [release-freeze](../canh-bao/release-freeze.md)).

## Kiểm tra nhanh

Dashboard ngân sách lỗi theo cell; cảnh báo `ErrorBudgetHalfSpent` đang bật ở cell nào; lịch đóng băng sắp tới (`ops/van-hanh/dong-bang.md` mục 1).

## Các bước

1. **CAB tuần:** theo `ops/van-hanh/hoi-dong-thay-doi.md` mục 2; phiếu dùng mẫu ở mục 5 của tệp đó.
2. **CAB khẩn:** theo `ops/van-hanh/hoi-dong-thay-doi.md` mục 3; trong cửa sổ đóng băng, `release-manager` đồng bộ tay theo `ops/van-hanh/dong-bang.md` mục 3.

## Lệnh vtctl và lệnh tương đương

Không có lệnh vận hành.

## Kiểm sau khi làm

Mỗi thay đổi thường đã thực hiện có phiếu CAB đã duyệt; mỗi lần đồng bộ trong cửa sổ đóng băng có biên bản CAB khẩn.

## Quay lui

Theo kế hoạch quay lui ghi trong phiếu (`ops/van-hanh/hoi-dong-thay-doi.md` mục 4).

## Leo thang

Không đủ thành phần CAB khẩn trong 1 giờ khi đang có SEV-1: IC quyết định thay đổi cần để khôi phục dịch vụ và ghi biên bản, CAB khẩn rà lại ngay khi đủ người.

## Liên quan

[OPS-06](ops-06-dong-bang-thay-doi.md), [release-freeze](../canh-bao/release-freeze.md), `ops/van-hanh/hoi-dong-thay-doi.md`, `ops/van-hanh/dong-bang.md`.
