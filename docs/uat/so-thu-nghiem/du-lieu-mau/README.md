# Dữ liệu mẫu hư cấu của UAT Sở Thử nghiệm

Thư mục này chứa toàn bộ dữ liệu nghiệp vụ mà buổi UAT dùng. Mọi tên người, đơn vị và nội dung công việc đều hư cấu, không lấy từ hồ sơ thật (SEC-52), và khớp với dữ liệu thử của hạ tầng dev (`infra/dev/postgres/seed/org-du-lieu-thu.sql`, realm `mission`), nên người dùng thử đăng nhập là thấy đúng đơn vị và chức danh của mình.

| Tệp | Nội dung | Ai dùng |
|---|---|---|
| `to-chuc-va-tai-khoan.csv` | Đơn vị, chức danh và tài khoản của mười người đại diện vai trò | Tổ UAT khi phân vai, người dùng thử khi đăng nhập |
| `ke-hoach-okr-nhiem-vu.yaml` | Mục tiêu, kết quả then chốt, kế hoạch quý, mười bốn nhiệm vụ, kết quả nghiệm thu, điều kiện hồ sơ, khiếu nại | Người dùng thử nhập theo kịch bản |
| `ket-qua-mong-doi.yaml` | Điểm và mức xếp loại quý mong đợi của năm cán bộ, tính tay theo Phụ lục 3, kèm cách tính | Tổ UAT đối chiếu sau khi khoá kỳ |

Kết quả mong đợi được tính tay trước, rồi đối chiếu với bộ tính tham chiếu bằng lệnh `python3 -I docs/uat/so-thu-nghiem/kiem_ket_qua_mong_doi.py`; lệnh này phải in "Khớp 5/5 ca" trước buổi UAT. Sau khi khoá kỳ, tổ UAT so điểm, mức xếp loại và câu giải thích trên phiếu xếp loại của từng người với bảng này, và ghi kết quả vào biên bản.

Dữ liệu đặt mọi nhiệm vụ hoàn thành đúng hạn. Lý do là kỳ rút gọn chỉ kéo dài mười ngày làm việc, nên trừ điểm tiến độ theo số ngày chậm sẽ phụ thuộc ngày thực tế chạy UAT; ca trừ điểm tiến độ đã có trong bộ ca mẫu vàng của gói quy định. Khi chạy với tenant thí điểm thật, dữ liệu là công việc thật của tenant và bảng kết quả mong đợi do cán bộ tổ chức của tenant lập theo cùng mẫu.
