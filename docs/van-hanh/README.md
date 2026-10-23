# Vận hành: đưa tenant vào sử dụng

> Nguồn: task VH-08; `ke-hoach-mo-rong.md`.

Thư mục này chứa kế hoạch đưa tenant thí điểm vào sử dụng Mission và mở rộng theo nhóm tenant. Mission là hệ mới, độc lập, nên kế hoạch không có bước chuyển dữ liệu hay tắt hệ cũ. Kế hoạch chia làm hai phần tách biệt: phần quy trình giữ cố định, phần dữ liệu thay được khi có danh sách tenant thật.

| Tệp | Nội dung |
|---|---|
| `ke-hoach-mo-rong.md` | Quy trình: nguyên tắc, cách chia nhóm, mốc, tiêu chí vào V-01..V-06, các bước B-01..B-08, tiêu chí ra R-01..R-05, ba mức xử lý sự cố D-1..D-3, chuyển tenant giữa cell, truyền thông và hỗ trợ, theo dõi tỉ lệ sử dụng |
| `nhom-tenant.yaml` | Dữ liệu: nhóm, tenant hư cấu, cell đích, mô hình, kỳ đầu, số tài khoản |
| `dua_vao_su_dung.py` | Công cụ kiểm bảng nhóm và lịch, đối soát dữ liệu khởi tạo, diễn tập đưa nhóm vào sử dụng, tắt cờ và tạm ngừng tenant |
| `dien-tap/` | Dữ liệu nguồn hư cấu của hai tenant trong nhóm thử |
| `theo-doi-ti-le.csv` | Mẫu bảng theo dõi tỉ lệ sử dụng theo nhóm (đầu vào VH-09), đang chứa số liệu giả lập của buổi diễn tập |

## Lệnh thường dùng

```bash
python3 docs/van-hanh/dua_vao_su_dung.py tu-kiem        # tự kiểm công cụ, phải đạt 9/9
python3 docs/van-hanh/dua_vao_su_dung.py kiem-tra       # kiểm bảng nhóm, in lịch các mốc
python3 docs/van-hanh/dua_vao_su_dung.py dien-tap --nhom N-THU \
    --tat-co huyen-thu-nghiem-mot --tam-ngung tinh-thu-nghiem-hai --ghi docs/van-hanh/theo-doi-ti-le.csv
```

Biên bản diễn tập: `ops/dien-tap/2026-10-23-mo-rong-nhom.md`.
