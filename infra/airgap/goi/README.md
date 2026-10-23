# Gói cài cách ly Mission (bản LTS)

Gói này dùng trong mạng không có Internet. Trước khi dùng bất kỳ tệp nào, kiểm tra gói bằng khoá công khai phát hành
`vt-release.pub` nhận qua kênh riêng (không nằm trong gói):

```bash
tar --zstd -xf okr-lts-<phiên bản>.tar.zst && cd okr-lts-<phiên bản>
tools/kiem-goi.sh --khoa /duong-dan/vt-release.pub        # dừng nếu chữ ký hoặc một tệp bất kỳ sai
tools/load-images.sh --kho harbor.qp.local/okr             # nạp ảnh và chữ ký vào Harbor nội bộ
tools/cai-dat.sh --kho harbor.qp.local/okr --khoa /duong-dan/vt-release.pub --values khach-hang.yaml
tools/kiem-tra-sau-cai.sh --ra bien-ban                    # biên bản kiểm tra sau cài đặt
```

Hướng dẫn đầy đủ: `docs/cach-ly.md`.
