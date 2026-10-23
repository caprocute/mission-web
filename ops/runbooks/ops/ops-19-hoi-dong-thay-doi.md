# OPS-19 · Hội đồng thay đổi (CAB)

> **Truy vết:** TL-04 mục 11.3 (ba loại thay đổi, CAB tuần, CAB khẩn, ngân sách lỗi là đầu vào) · cảnh báo `ErrorBudgetHalfSpent`. Quy trình họp chi tiết thuộc VH-03.
> **Chu kỳ:** tuần, khẩn · **Chịu trách nhiệm:** quản lý thay đổi · **Tự động hoá:** quy trình.
> **Chạy thử:** không áp dụng chạy lệnh.

## Điều kiện kích hoạt

Có thay đổi thường (nâng cấp Kubernetes, thay đổi lược đồ CSDL lớn, thêm cell, chuyển tenant) cần duyệt trong tuần; hoặc cần thay đổi khẩn (kể cả trong cửa sổ đóng băng của OPS-06).

## Tác động

Thay đổi chuẩn (phát hành qua pipeline, cấu hình trong ngưỡng đã duyệt) không qua CAB. Cell đã tiêu quá 50% ngân sách lỗi tháng trước ngày 15 tạm dừng thay đổi thường.

## Kiểm tra nhanh

Dashboard ngân sách lỗi theo cell; cảnh báo `ErrorBudgetHalfSpent` đang bật ở cell nào.

## Các bước

1. **CAB tuần:** quản lý thay đổi gom phiếu thay đổi thường, kèm kế hoạch, sổ tay OPS áp dụng, kế hoạch quay lui; hội đồng duyệt hoặc trả lại.
2. **CAB khẩn:** họp trực tuyến với ít nhất quản lý thay đổi, trưởng ca SRE và chủ sản phẩm; ghi số biên bản để `release-manager` đồng bộ tay theo OPS-06.
3. Phiếu đã duyệt ghi rõ khung giờ thực hiện ngoài cửa sổ đóng băng (trừ CAB khẩn).

## Lệnh vtctl và lệnh tương đương

Không có lệnh vận hành.

## Kiểm sau khi làm

Mỗi thay đổi thường đã thực hiện có phiếu CAB đã duyệt; mỗi lần đồng bộ trong cửa sổ đóng băng có biên bản CAB khẩn.

## Quay lui

Theo kế hoạch quay lui ghi trong phiếu.

## Leo thang

Không đủ thành phần CAB khẩn trong 1 giờ với SEV-1: IC quyết định và ghi biên bản sau.

## Liên quan

[OPS-06](ops-06-dong-bang-thay-doi.md), [release-freeze](../canh-bao/release-freeze.md).
