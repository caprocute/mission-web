# Danh sách mật khẩu phổ biến cho Keycloak dev (ASVS 2.1.7, NFR-08)

`mat-khau-pho-bien.txt` là danh sách mật khẩu bị cấm, dùng cho chính sách `passwordBlacklist(mat-khau-pho-bien.txt)` của realm dev (`../realm-mission.json`). Docker Compose gắn thư mục này vào `/opt/keycloak/data/password-blacklists/`, thư mục mặc định Keycloak đọc danh sách. Keycloak so mật khẩu đã chuyển chữ thường với từng dòng của tệp, nên tệp chỉ chứa chữ thường, mỗi dòng một mật khẩu và không có dòng chú thích.

Nguồn: tệp `Passwords/Common-Credentials/10k-most-common.txt` của SecLists (giấy phép MIT, https://github.com/danielmiessler/SecLists), chuyển chữ thường, bỏ trùng và chỉ giữ mật khẩu dài từ 8 ký tự (ngắn hơn thì chính sách `length(8)` đã chặn).

Realm của cell (`deploy/charts/iam/files/realm/realm-cell.yaml`) chưa bật chính sách này vì tệp phải nằm trong ảnh Keycloak của cell (task INF dựng ảnh); khi dựng ảnh, chép tệp này vào cùng đường dẫn và thêm `passwordBlacklist(mat-khau-pho-bien.txt)` vào `passwordPolicy`.
