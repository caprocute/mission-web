<!-- SINH TỰ ĐỘNG bởi data/seed/da-tenant/sinh_seed.py, không sửa tay. -->
# Tài khoản thử đa tenant

Mọi tài khoản dùng chung mật khẩu thử và mã OTP thử của realm `mission` trên môi trường dev (giá trị nằm trong `infra/dev/.env`, không ghi ở đây). Tên người và đơn vị là hư cấu.

## Khối Đảng – Đảng bộ tỉnh Phú Lâm

Tỉnh ủy, các ban tham mưu, Đảng ủy các cơ quan Đảng tỉnh, một đảng ủy xã và chi bộ thôn; đánh giá theo Quy định 366-QĐ/TW.

| Tài khoản | Họ và tên | Chức danh, đơn vị | Vai trò | Dùng để thử |
|---|---|---|---|---|
| `tu.bithu` | Trần Quốc Hưng | Bí thư Tỉnh ủy, Tỉnh ủy Phú Lâm | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cấp có thẩm quyền quyết định | Người đứng đầu; duyệt mục tiêu của Tỉnh ủy, quyết định xếp loại |
| `tu.phobithu` | Lê Thị Thu Hà | Phó Bí thư Thường trực Tỉnh ủy, Tỉnh ủy Phú Lâm | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp, Người duyệt thứ hai | Soạn mục tiêu của Tỉnh ủy; duyệt thứ hai các thao tác nhạy cảm |
| `tu.truongbantc` | Phạm Minh Đức | Trưởng ban, Ban Tổ chức Tỉnh ủy | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cơ quan tổ chức cán bộ (thẩm định) | Duyệt mục tiêu khối Ban Tổ chức; thẩm định hồ sơ đánh giá |
| `tu.phobantc` | Hà Văn Tùng | Phó Trưởng ban, Ban Tổ chức Tỉnh ủy; kiêm bí thư chi bộ, Chi bộ Ban Tổ chức Tỉnh ủy | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp, Cơ quan tổ chức cán bộ (thẩm định) | Soạn mục tiêu của Ban; kiêm nhiệm Bí thư Chi bộ Ban Tổ chức (có hai phân công) |
| `tu.truongphongcb` | Võ Thị Ngọc Lan | Trưởng phòng, Phòng Cán bộ | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp, Cơ quan tổ chức cán bộ (thẩm định) | Giao việc, nghiệm thu trong Phòng Cán bộ |
| `tu.chuyenviencb` | Đặng Quang Huy | Chuyên viên, Phòng Cán bộ | Cán bộ, công chức, viên chức, đảng viên, Cơ quan tổ chức cán bộ (thẩm định) | Chuyên viên thẩm định; có mục tiêu cá nhân đã duyệt và việc đang làm |
| `tu.chuyenvientcdv` | Bùi Thị Hồng Nhung | Chuyên viên, Phòng Tổ chức – Đảng viên | Cán bộ, công chức, viên chức, đảng viên, Quản trị nghiệp vụ | Soạn bộ tiêu chí, công thức đánh giá (chưa tự phát hành được) |
| `tu.chanhvp` | Hoàng Văn Nam | Chánh Văn phòng Tỉnh ủy, Văn phòng Tỉnh ủy | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu | Duyệt mục tiêu khối Văn phòng; có một bộ mục tiêu đang chờ duyệt |
| `tu.truongphongth` | Ngô Thị Thanh Hương | Trưởng phòng, Phòng Tổng hợp | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Soạn mục tiêu Phòng Tổng hợp, giao việc |
| `tu.chuyenvienth` | Lý Văn Khánh | Chuyên viên, Phòng Tổng hợp | Cán bộ, công chức, viên chức, đảng viên | Chuyên viên; mục tiêu đã duyệt, việc đang làm |
| `tu.chuyenvienth2` | Trịnh Thị Mai | Chuyên viên, Phòng Tổng hợp | Cán bộ, công chức, viên chức, đảng viên | Chuyên viên; mục tiêu đang chờ duyệt, việc mới giao |
| `tu.quantri` | Đỗ Minh Tuấn | Chuyên viên, Phòng Tổng hợp | Quản trị đơn vị | Quản trị hệ thống của Tỉnh ủy |
| `tu.ubkt` | Mai Xuân Trường | Phó Chủ nhiệm Ủy ban Kiểm tra, Ủy ban Kiểm tra Tỉnh ủy | Cán bộ, công chức, viên chức, đảng viên, Cơ quan kiểm tra, giám sát | Kiểm tra, giám sát; đọc sổ cái, xử lý cảnh báo |
| `tu.bithuxa` | Đinh Văn Lực | Bí thư Đảng ủy xã, Đảng ủy xã Tân Lập | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu | Duyệt mục tiêu của chi bộ trực thuộc |
| `tu.bithuchibo` | Nguyễn Thị Lan | Bí thư chi bộ, Chi bộ thôn Đông Hòa | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Soạn mục tiêu chi bộ, giao việc cho đảng viên |
| `tu.dangvien` | Trần Văn Bảy | Đảng viên, Chi bộ thôn Đông Hòa | Cán bộ, công chức, viên chức, đảng viên | Đảng viên cao tuổi; thử chế độ chữ lớn |

## Khối chính quyền – Ủy ban nhân dân tỉnh Phú Lâm

UBND tỉnh, Văn phòng, ba sở, Trung tâm Phục vụ hành chính công, Thanh tra tỉnh và một UBND xã; đánh giá cán bộ, công chức, viên chức theo Thông tư 22 và Nghị định 335/2025.

| Tài khoản | Họ và tên | Chức danh, đơn vị | Vai trò | Dùng để thử |
|---|---|---|---|---|
| `cq.chutich` | Lê Hoàng Phúc | Chủ tịch Ủy ban nhân dân tỉnh, Ủy ban nhân dân tỉnh Phú Lâm | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cấp có thẩm quyền quyết định | Người đứng đầu; duyệt mục tiêu của tỉnh, quyết định xếp loại |
| `cq.phochutich` | Nguyễn Thị Kim Oanh | Phó Chủ tịch Ủy ban nhân dân tỉnh, Ủy ban nhân dân tỉnh Phú Lâm | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp, Người duyệt thứ hai | Soạn mục tiêu của tỉnh; duyệt thứ hai các thao tác nhạy cảm |
| `cq.chanhvp` | Trương Công Danh | Chánh Văn phòng, Văn phòng Ủy ban nhân dân tỉnh | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu | Duyệt mục tiêu khối Văn phòng và Trung tâm |
| `cq.truongphongth` | Phan Thị Hải Yến | Trưởng phòng, Phòng Tổng hợp | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Giao việc trong Phòng Tổng hợp |
| `cq.chuyenvienth` | Vũ Đức Anh | Chuyên viên, Phòng Tổng hợp | Cán bộ, công chức, viên chức, đảng viên | Chuyên viên tổng hợp; mục tiêu đã duyệt, việc đang làm |
| `cq.giamdoctt` | Châu Thị Ánh | Giám đốc Trung tâm, Trung tâm Phục vụ hành chính công tỉnh | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Soạn mục tiêu Trung tâm, giao việc cho viên chức |
| `cq.vienchuctt` | Lâm Văn Tài | Viên chức, Trung tâm Phục vụ hành chính công tỉnh | Cán bộ, công chức, viên chức, đảng viên | Viên chức tiếp nhận hồ sơ một cửa |
| `cq.giamdocsnv` | Đoàn Văn Hiển | Giám đốc Sở, Sở Nội vụ | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cơ quan tổ chức cán bộ (thẩm định) | Thẩm định hồ sơ đánh giá; có một bộ mục tiêu đang chờ duyệt |
| `cq.truongphongccvc` | Cao Minh Tâm | Trưởng phòng, Phòng Công chức, viên chức | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp, Quản trị nghiệp vụ | Giao việc; soạn bộ tiêu chí đánh giá công chức |
| `cq.chuyenviennv` | Huỳnh Thị Thảo | Chuyên viên, Phòng Công chức, viên chức | Cán bộ, công chức, viên chức, đảng viên, Cơ quan tổ chức cán bộ (thẩm định) | Chuyên viên thẩm định; mục tiêu chờ duyệt |
| `cq.giamdocstc` | Phạm Văn Lộc | Giám đốc Sở, Sở Tài chính | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu | Duyệt mục tiêu của Sở Tài chính |
| `cq.phogiamdocstc` | Hồ Thị Bích Ngọc | Phó Giám đốc Sở, Sở Tài chính | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Soạn mục tiêu của Sở Tài chính |
| `cq.truongphongns` | Lương Quốc Bảo | Trưởng phòng, Phòng Quản lý ngân sách | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Giao việc thẩm định dự toán |
| `cq.chuyenvienns` | Tạ Thị Minh Châu | Chuyên viên, Phòng Quản lý ngân sách | Cán bộ, công chức, viên chức, đảng viên | Chuyên viên ngân sách; mục tiêu đã duyệt, việc đang làm |
| `cq.chuyenvienns2` | Kiều Văn Sơn | Chuyên viên, Phòng Quản lý ngân sách; kiêm chuyên viên, Phòng Chuyển đổi số | Cán bộ, công chức, viên chức, đảng viên | Kiêm nhiệm Tổ công tác chuyển đổi số (có hai phân công) |
| `cq.truongphongcds` | Mạc Thị Phương | Trưởng phòng, Phòng Chuyển đổi số | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Trưởng phòng Chuyển đổi số |
| `cq.thanhtra` | Quách Văn Hải | Thanh tra viên, Thanh tra tỉnh | Cán bộ, công chức, viên chức, đảng viên, Cơ quan kiểm tra, giám sát | Thanh tra viên; đọc sổ cái, xử lý cảnh báo |
| `cq.chutichxa` | Lã Văn Toàn | Chủ tịch Ủy ban nhân dân xã, Ủy ban nhân dân xã Tân Lập | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cán bộ quản lý trực tiếp | Soạn mục tiêu của xã, giao việc |
| `cq.congchucxa` | Ninh Thị Hoa | Công chức cấp xã, Ủy ban nhân dân xã Tân Lập | Cán bộ, công chức, viên chức, đảng viên | Công chức Văn phòng – Thống kê xã |
| `cq.quantri` | Đỗ Thành Long | Chuyên viên, Phòng Tổng hợp | Quản trị đơn vị | Quản trị hệ thống, người soạn cấu hình |
| `cq.quantri2` | Tô Thị Hạnh | Chuyên viên, Phòng Tổng hợp | Quản trị đơn vị | Quản trị thứ hai, duyệt cấu hình (bốn mắt) |

## Doanh nghiệp – Công ty TNHH MTV Cấp nước Phú Lâm

Doanh nghiệp nhà nước 100% vốn của tỉnh: phòng nghiệp vụ, xí nghiệp cấp nước, nhà máy nước; giao việc và đánh giá theo KPI sản xuất kinh doanh.

| Tài khoản | Họ và tên | Chức danh, đơn vị | Vai trò | Dùng để thử |
|---|---|---|---|---|
| `dn.chutich` | Hoàng Minh Quân | Chủ tịch Hội đồng thành viên, Công ty TNHH MTV Cấp nước Phú Lâm | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cấp có thẩm quyền quyết định | Duyệt mục tiêu của Công ty, quyết định xếp loại |
| `dn.tonggiamdoc` | Nguyễn Văn Thắng | Tổng Giám đốc, Công ty TNHH MTV Cấp nước Phú Lâm | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cán bộ quản lý trực tiếp | Soạn mục tiêu Công ty; duyệt mục tiêu phòng, xí nghiệp |
| `dn.photonggiamdoc` | Phan Thị Thùy Dung | Phó Tổng Giám đốc, Công ty TNHH MTV Cấp nước Phú Lâm | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp, Người duyệt thứ hai | Phụ trách kỹ thuật; duyệt thứ hai |
| `dn.kiemsoatvien` | Lê Văn Cường | Kiểm soát viên, Công ty TNHH MTV Cấp nước Phú Lâm | Cơ quan kiểm tra, giám sát | Kiểm soát viên do chủ sở hữu cử; chỉ kiểm tra, giám sát |
| `dn.truongphongtchc` | Đỗ Thị Thanh Tâm | Trưởng phòng, Phòng Tổ chức – Hành chính | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp, Cơ quan tổ chức cán bộ (thẩm định) | Phụ trách nhân sự; thẩm định đánh giá |
| `dn.chuyenviennhansu` | Vương Quốc Việt | Chuyên viên, Phòng Tổ chức – Hành chính | Cán bộ, công chức, viên chức, đảng viên, Quản trị nghiệp vụ | Soạn bộ KPI, công thức tính điểm |
| `dn.truongphongkd` | Trần Thị Mỹ Linh | Trưởng phòng, Phòng Kinh doanh – Dịch vụ khách hàng | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Soạn mục tiêu phòng, giao việc kinh doanh |
| `dn.nhanvienkd` | Phạm Hoài Nam | Nhân viên, Phòng Kinh doanh – Dịch vụ khách hàng | Cán bộ, công chức, viên chức, đảng viên | Nhân viên kinh doanh; mục tiêu đã duyệt, việc đang làm |
| `dn.nhanvienkd2` | Lê Thị Ngọc Trâm | Nhân viên, Phòng Kinh doanh – Dịch vụ khách hàng | Cán bộ, công chức, viên chức, đảng viên | Nhân viên chăm sóc khách hàng; mục tiêu chờ duyệt |
| `dn.truongphongkt` | Bạch Văn Hùng | Trưởng phòng, Phòng Kế hoạch – Kỹ thuật | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Trưởng phòng Kế hoạch – Kỹ thuật |
| `dn.giamdocxn` | Tống Văn Định | Giám đốc Xí nghiệp, Xí nghiệp Cấp nước số 1 | Cán bộ, công chức, viên chức, đảng viên, Lãnh đạo, người đứng đầu, Cán bộ quản lý trực tiếp | Soạn mục tiêu xí nghiệp, duyệt mục tiêu công nhân |
| `dn.totruong` | Nguyễn Văn Hậu | Tổ trưởng, Xí nghiệp Cấp nước số 1 | Cán bộ, công chức, viên chức, đảng viên, Cán bộ quản lý trực tiếp | Tổ trưởng vận hành mạng lưới; giao việc hằng ngày |
| `dn.congnhan` | Đặng Văn Tư | Công nhân vận hành, Xí nghiệp Cấp nước số 1 | Cán bộ, công chức, viên chức, đảng viên | Công nhân; thử trên điện thoại và chế độ chữ lớn |
| `dn.quantri` | Lưu Thị Hồng | Chuyên viên, Phòng Tổ chức – Hành chính | Quản trị đơn vị | Quản trị hệ thống của Công ty |
