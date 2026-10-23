> Nguồn: TL-04 mục 9.6 (SEC-37, SEC-38, SEC-55), mục 9.3 (SEC-28), mục 9.7; task INF-05, INF-07, INF-23. Ngày đánh giá: 23/10/2026.

# Bằng chứng 08: Build có chữ ký (SEC-37, SEC-38)

## Yêu cầu

Ảnh container được ký bằng cosign và có chứng thực nguồn gốc (provenance) theo SLSA mức 3. Kyverno từ chối mọi ảnh không có chữ ký hợp lệ. TL-04 yêu cầu bằng chứng là nhật ký từ chối của Kyverno và kết quả kiểm tra ngẫu nhiên chữ ký.

## Biện pháp đã có

Pipeline ký ảnh và gắn provenance SLSA v1 bằng khoá `cosign-release` trong OpenBao Transit. Khoá này không xuất ra ngoài được, và quyền ký chỉ cấp cho pipeline phát hành (`deploy/openbao/chinh-sach/ky-ban-phat-hanh.hcl`). Provenance được sinh bởi `ci/vt-pipeline/resources/vn/vt/pipeline/vt-provenance.py`. Sau khi ký, `ci/vt-pipeline/vars/vtGitopsBump.groovy` cập nhật digest vào kho GitOps (`deploy/environments/production/digests.yaml`), nên cụm luôn chạy ảnh theo digest (SEC-55). Phía cụm có chính sách `deploy/policies/vt-require-signed-images.yaml`: Kyverno kiểm chữ ký và chứng thực builder của mọi ảnh. Harbor là lớp thứ hai, chặn kéo ảnh chưa ký cosign ở các project chứa ảnh tự build (`deploy/clusters/mgmt/harbor/cau-hinh.yaml`). Gói cài cách ly mang chữ ký và được kiểm lại khi cài (`infra/airgap/dong-goi.sh`, `infra/airgap/goi/tools/kiem-goi.sh`).

Về kiểm thử, `deploy/environments/kind/kiem-thu-chinh-sach.sh` khẳng định trên cụm kind ba điều. Ảnh đã ký chạy được. Ảnh chưa ký, ảnh dùng thẻ `latest` và ảnh có chứng thực sai builder đều bị từ chối, kèm thông báo rõ ràng. Pod chạy root hoặc thiếu probe cũng bị từ chối.

## Cách thu bằng chứng

Script `thu-bang-chung.sh` thu ba nhóm bằng chứng. Nhóm thứ nhất là PolicyReport và sự kiện từ chối của Kyverno, ghi vào `kyverno-tu-choi.txt`. Nhóm thứ hai là danh sách ảnh đang chạy theo digest. Nhóm thứ ba là kết quả `cosign verify` cho một mẫu ngẫu nhiên các ảnh đó, ghi vào `cosign-kiem-ngau-nhien.txt`.

## Trạng thái và điểm còn thiếu

Trạng thái: **Đáp ứng (giả lập)**. Cơ chế đã làm xong và đã kiểm trên kind, với khoá ký tạm. Còn thiếu nhật ký từ chối trên cụm thật và khoá ký trong OpenBao gắn HSM thật.
