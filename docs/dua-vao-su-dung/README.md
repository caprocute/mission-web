# Đưa tenant vào sử dụng và mở rộng theo nhóm (VH-08)

> Nguồn: task VH-08; TL-01 chương "Lộ trình chuyển đổi" (đợt 2, đợt 3, bảng "Chỉ số theo dõi chuyển đổi"); TL-04 mục 11.2, 11.3, 11.4 (OPS-04, OPS-06, OPS-07, OPS-08), mục 11 "Quy trình định kỳ khác" (OPS-23), 12.6, 12.7; quyết định ngày 20/10/2026 về hệ mới độc lập (`docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 1a); quyết định đưa ứng dụng di động ra khỏi phạm vi (commit 60d18e8a).

Task VH-08 đề xuất đặt kế hoạch ở thư mục này. Khi làm, kế hoạch đã được đặt cạnh các tài liệu vận hành khác ở `docs/van-hanh/`, và các luồng khác đã trỏ tới vị trí đó. Để không làm gãy liên kết, thư mục này chỉ giữ trang dẫn và bảng đối chiếu với thiết kế. Nội dung chính nằm ở các tệp sau:

| Tệp | Vai trò |
|---|---|
| [`docs/van-hanh/ke-hoach-mo-rong.md`](../van-hanh/ke-hoach-mo-rong.md) | Phần quy trình: nguyên tắc, cách chia nhóm, mốc, tiêu chí vào, các bước, tiêu chí ra, ba mức quay lui, chuyển tenant giữa cell, truyền thông, theo dõi tỉ lệ sử dụng |
| [`docs/van-hanh/nhom-tenant.yaml`](../van-hanh/nhom-tenant.yaml) | Phần dữ liệu: danh sách tenant giả lập theo nhóm, cell đích, kỳ đầu, số tài khoản. Khi có dữ liệu thật thì chỉ thay tệp này |
| [`docs/van-hanh/dua_vao_su_dung.py`](../van-hanh/dua_vao_su_dung.py) | Công cụ kiểm bảng nhóm, tự kiểm và diễn tập trên bản giả lập |
| [`docs/van-hanh/theo-doi-ti-le.csv`](../van-hanh/theo-doi-ti-le.csv) | Mẫu bảng theo dõi tỉ lệ sử dụng theo tenant, đầu vào của VH-09 |
| [`docs/van-hanh/dien-tap/`](../van-hanh/dien-tap/) | Dữ liệu khởi tạo giả lập của hai tenant thử |
| [`ops/dien-tap/2026-10-23-mo-rong-nhom.md`](../../ops/dien-tap/2026-10-23-mo-rong-nhom.md) | Biên bản diễn tập lần 1 trên bản giả lập |
| [`ops/dien-tap/2026-10-24-mo-rong-nhom.md`](../../ops/dien-tap/2026-10-24-mo-rong-nhom.md) | Biên bản diễn tập lần 2: chạy lại bản giả lập và kiểm bằng mã thật chuỗi tạm ngừng tenant |

## Đối chiếu kế hoạch với TL-01 và TL-04

Bảng dưới đây giúp người duyệt đối chiếu từng yêu cầu của thiết kế với chỗ kế hoạch đáp ứng. Cột cuối ghi những chỗ kế hoạch cố ý làm khác thiết kế và lý do. Các chỗ khác đó đều xuất phát từ hai quyết định đã chốt: Mission là hệ mới độc lập, và ứng dụng di động không thuộc phạm vi.

| Yêu cầu trong thiết kế | Nguồn | Chỗ đáp ứng trong kế hoạch | Ghi chú |
|---|---|---|---|
| Đưa tenant vào theo nhóm, mỗi nhóm bắt đầu từ đầu một kỳ đánh giá | TL-01 đợt 2 | Nguyên tắc 1; mốc T; tiêu chí V-01..V-06 (mục 1, 3, 4) | Khớp |
| Tenant thí điểm hoàn thành một kỳ đánh giá đầy đủ trên hệ mới | TL-01 đợt 2, điều kiện chuyển bước | Nhóm N0 chỉ xét ra sau khi nghiệm thu UAT trọn kỳ đầu; V-01 cho nhóm N1 (mục 2, 4, 6) | Khớp |
| Dữ liệu lịch sử kỳ cũ được chuyển sang dạng chỉ đọc, kèm niêm phong sổ cái | TL-01 đợt 2 | Không áp dụng (mục 1) | Hệ mới không chuyển dữ liệu từ hệ cũ; kết quả các kỳ trước do tenant tự lưu |
| Quy mô quốc gia từ quý 3/2027 | TL-01 đợt 3 | Nhóm N1..N5 từ Quý IV/2027 (mục 2) | Lệch một quý, có lý do: nhóm thí điểm chỉ nghiệm thu được vào giữa tháng 7, đã quá hạn chuẩn bị cho Quý III |
| Ứng dụng di động trong đợt 3 | TL-01 đợt 3 | Không có bước nào | Ngoài phạm vi theo quyết định ở commit 60d18e8a; kế hoạch chỉ dùng kênh web, thư điện tử công vụ và bản in |
| Hệ thống cũ được tắt | TL-01 đợt 3 | Không áp dụng | Không có hệ cũ để tắt |
| Chỉ số "tỉ lệ người dùng trên nền tảng mới": tenant thí điểm sau đợt 2, 100% sau đợt 3 | TL-01 bảng chỉ số | Đo theo hai tầng: tỉ lệ tenant `DANG_VAN_HANH` và tỉ lệ người dùng hoạt động trong tenant; bảng `theo-doi-ti-le.csv` (mục 10) | Diễn giải lại cho hệ mới, giữ mốc 100% |
| Cờ tính năng theo tenant, tách triển khai mã khỏi mở chức năng | TL-04 11.2 (OPS-04) | Nguyên tắc 3; bước B-07; mức quay lui D-1 (mục 5, 7) | Khớp; danh mục cờ thật còn phải chốt |
| Cửa sổ đóng băng từ ngày 24 tháng cuối quý tới hết ngày 10 tháng đầu quý sau, và 20/1..05/2 | TL-04 11.3 (OPS-06) | Nguyên tắc 2; mốc S và hạn sẵn sàng S − 1; công cụ kiểm báo lỗi khi mốc rơi vào cửa sổ (mục 1, 3) | Khớp |
| Onboarding tám bước, ≤ 1 ngày, trạng thái `KHOI_TAO` → `DANG_CAP_PHAT` → `DANG_CAU_HINH` → `SAN_SANG` → `DANG_VAN_HANH` | TL-04 11.4 (OPS-07) | Bước B-01..B-06 (mục 5) | Khớp |
| `TAM_NGUNG` chặn đăng nhập ở Keycloak, chặn ghi ở APISIX, giữ dữ liệu | TL-04 11.4 | Mức quay lui D-3 (mục 7) | Thiết kế khớp. Biên bản lần 2 ghi phần mã đã có và phần còn thiếu |
| Chuyển tenant giữa cell: chỉ đọc 5–15 phút, đối soát số dòng và băm, đổi danh bạ và DNS, giữ nguồn 30 ngày | TL-04 11.4 (OPS-08) | Mục 8, trỏ sổ tay `ops/runbooks/chuyen-tenant-giua-cell.md` | Khớp |
| Bảng 12.7 ghi OPS-08 là "chuyển tenant từ hệ cũ vào cell theo đợt" | TL-04 12.7 | Mục 8 dùng OPS-08 đúng nghĩa ở 11.4: chuyển giữa các cell của Mission | Dòng 12.7 viết theo giả định có hệ cũ; với hệ mới, việc đưa tenant vào là OPS-07 cộng cờ OPS-04 |
| Mở rà soát thêm cell khi dự báo chạm 80% ngưỡng | TL-04 mục 11, "Quy trình định kỳ khác" (OPS-23) | Công cụ kiểm cảnh báo khi cell vượt 80% sức chứa; tiêu chí V-02 (mục 2, 4) | Khớp |

Không có mâu thuẫn nào chưa được giải thích. Các mục ghi "không áp dụng" hoặc "diễn giải lại" đều đã được ghi trong kế hoạch mục 1 và mục 10.

## Liên quan

- [Kế hoạch mở rộng](../van-hanh/ke-hoach-mo-rong.md)
- [Bộ UAT tenant thí điểm](../uat/so-thu-nghiem/README.md) (QA-16)
- [Sổ tay cấp phát và kích hoạt tenant](../../ops/runbooks/cap-phat-kich-hoat-tenant.md)
