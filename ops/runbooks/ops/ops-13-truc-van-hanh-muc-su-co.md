# OPS-13 · Trực vận hành và mức sự cố

> **Truy vết:** TL-04 mục 11.7 (bảng SEV-1 đến SEV-4, ca trực hai cấp, phân tích sau sự cố trong 5 ngày làm việc). Nội dung ca trực chi tiết (lịch, bàn giao ca, CAB) thuộc VH-03.
> **Chu kỳ:** liên tục · **Chịu trách nhiệm:** SRE · **Tự động hoá:** hệ thống gọi trực.
> **Chạy thử:** không áp dụng chạy lệnh; diễn tập gọi trực đi cùng diễn tập khôi phục của OPS-18.

## Điều kiện kích hoạt

Mọi cảnh báo gọi trực (mức `critical` và `warning` của luật Prometheus có `runbook_url`), phiếu hỗ trợ của khách hàng, hoặc người trực tự phát hiện bất thường. Cảnh báo "kế hoạch năng lực" (NG-01 đến NG-14) không gọi trực, chỉ gửi kênh đội nền tảng.

## Tác động

Mức sự cố quyết định thời gian phản hồi, khắc phục và nhịp thông báo:

| Mức | Định nghĩa | Phản hồi | Khắc phục mục tiêu | Thông báo |
|---|---|---|---|---|
| SEV-1 | Một cell trở lên không dùng được, mất dữ liệu, sự cố an ninh nghiêm trọng, hoặc DR | ≤ 15 phút | ≤ 4 giờ | Lãnh đạo, khách hàng mỗi 30 phút |
| SEV-2 | Chức năng chính suy giảm (mức suy giảm 2 kéo dài), vi phạm SLO nhanh | ≤ 30 phút | ≤ 8 giờ | Khách hàng bị ảnh hưởng mỗi 2 giờ |
| SEV-3 | Chức năng phụ lỗi, một tenant bị ảnh hưởng, có cách làm tạm | ≤ 4 giờ làm việc | ≤ 3 ngày làm việc | Qua phiếu hỗ trợ |
| SEV-4 | Lỗi nhỏ, câu hỏi | ≤ 1 ngày làm việc | Theo kế hoạch phát hành | Qua phiếu hỗ trợ |

## Kiểm tra nhanh

Mở cảnh báo, đọc nhãn `cell`, `severity`, đi theo `runbook_url` tới sổ tay trong `ops/runbooks/` hoặc `ops/runbooks/canh-bao/`. Dashboard "SLO theo cell" cho biết phạm vi ảnh hưởng (một tenant, một cell, nhiều cell).

## Các bước

1. **Cấp 1 nhận cảnh báo:** xác nhận trên hệ thống gọi trực trong thời gian phản hồi của mức, xếp mức sơ bộ theo bảng trên.
2. Làm theo sổ tay của cảnh báo. Thao tác thường xuyên dùng `vtctl` (suy giảm, khởi động lại consumer, phát lại DLQ).
3. **Gọi cấp 2** theo lĩnh vực (nền tảng, CSDL, lưu trữ, an ninh) khi sổ tay ghi leo thang, khi hết thời gian khắc phục mục tiêu một nửa mà chưa ổn, hoặc khi không có sổ tay.
4. **SEV-1, SEV-2:** chỉ định chỉ huy sự cố (IC); IC giữ nhịp thông báo theo bảng, quyết định suy giảm, quay lui, chuyển DR.
5. Đóng sự cố khi chỉ số về ngưỡng và cảnh báo tự đóng; ghi dòng thời gian vào phiếu.
6. **SEV-1, SEV-2:** phân tích sau sự cố không quy lỗi cá nhân trong 5 ngày làm việc, hạng mục khắc phục có người và hạn, theo dõi tới khi đóng.

Sơ đồ gọi trực hai cấp:

```text
Cảnh báo critical/warning ──► Cấp 1 SRE (24/7) ──► sổ tay ──► xong
                                   │ leo thang
                                   ▼
             Cấp 2 theo lĩnh vực: nền tảng │ CSDL │ lưu trữ │ an ninh (SOC)
                                   │ SEV-1, SEV-2
                                   ▼
                     IC ──► lãnh đạo, khách hàng (nhịp theo bảng SEV)
```

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem mức suy giảm của cell | `vtctl -e <env> -c <cell> degrade status` | `../degradation.md` |
| Xem lag consumer | `vtctl -e <env> -c <cell> consumer lag` | `kafka-consumer-groups --describe` |

## Kiểm sau khi làm

Phiếu sự cố có mức, dòng thời gian, người xử lý, sổ tay đã dùng; với SEV-1, SEV-2 có lịch phân tích sau sự cố.

## Quay lui

Không áp dụng.

## Leo thang

Như mục "Các bước"; không liên lạc được cấp 2 trong 15 phút với SEV-1 thì gọi trưởng ca SRE.

## Liên quan

[Danh mục sổ tay](../README.md), [OPS-14](ops-14-runbook.md), [OPS-18](ops-18-dien-tap-khoi-phuc.md).
