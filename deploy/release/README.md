# Tệp kê ảnh của bản phát hành cách ly (INF-23)

Mỗi bản LTS có thư mục `deploy/release/<phiên bản>/` chứa `images.yaml` do `infra/airgap/ke-anh.py --phien-ban <phiên bản>`
sinh từ bản render của `vt-platform` với `values-airgap.yaml`: mọi ảnh theo digest, không ảnh theo thẻ, không digest
giả. Tệp được commit cùng thẻ phát hành và là đầu vào của `infra/airgap/dong-goi.sh`. Chưa có bản LTS thật nên thư mục
chưa có phiên bản nào: digest của ảnh dịch vụ trong chart vẫn là giá trị giả, `ke-anh.py` sẽ từ chối cho tới khi pipeline
INF-05 ghi digest thật. Quy trình đầy đủ: `docs/quan-tri-cai-dat/cach-ly.md`.
