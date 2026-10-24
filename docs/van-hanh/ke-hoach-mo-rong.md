# Kế hoạch đưa tenant thí điểm vào sử dụng và mở rộng theo nhóm tenant

> Nguồn: task VH-08; TL-01 chương "Lộ trình chuyển đổi" (chỉ dùng tiêu chí đợt 2, đợt 3 và chỉ số "tỉ lệ người dùng trên nền tảng mới"; ADR-20 và phần chuyển dần từ hệ cũ không áp dụng, xem TL-01 Phụ lục D mục D.6 và `docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 1a); TL-04 mục 11.2 (OPS-04 cờ tính năng), 11.3 (OPS-06 cửa sổ đóng băng), 11.4 (OPS-07 vòng đời tenant, OPS-08 chuyển tenant giữa cell), 11.8 (OPS-17), 12.6, 12.7; TL-02a QT-01 (trạng thái tenant); bộ UAT `docs/uat/so-thu-nghiem/` (QA-16); bộ hướng dẫn `docs/huong-dan-su-dung/` (VH-05); sổ tay `ops/runbooks/cap-phat-kich-hoat-tenant.md`, `ops/runbooks/chuyen-tenant-giua-cell.md`.

Tài liệu này là phần **quy trình** của kế hoạch. Phần **dữ liệu** (danh sách tenant, nhóm, cell đích, kỳ đầu, số tài khoản) nằm riêng trong [`nhom-tenant.yaml`](nhom-tenant.yaml). Khi có danh sách tenant, lịch kỳ đánh giá và cam kết thật của khách hàng, người phụ trách chỉ thay tệp dữ liệu và chạy lại công cụ kiểm; quy trình dưới đây giữ nguyên.

## 1. Bối cảnh và nguyên tắc

Mission là hệ thống mới, độc lập. Hệ thống không thay thế một hệ cũ đang chạy và không chuyển dữ liệu từ hệ cũ sang. Vì vậy, "đưa tenant vào sử dụng" ở đây có nghĩa là: tenant bắt đầu dùng Mission từ đầu một kỳ đánh giá, với dữ liệu khởi tạo mới gồm cơ cấu tổ chức, nhân sự, danh mục công việc và bộ chính sách đánh giá (Policy Pack). Kế hoạch không có bước định tuyến lưu lượng giữa hai hệ, không chuyển dữ liệu kỳ cũ và không có bước tắt hệ cũ. Kết quả đánh giá của các kỳ trước khi tenant vào hệ vẫn do tenant lưu theo cách hiện hành.

Kế hoạch dựa trên năm nguyên tắc. Mỗi nguyên tắc có lý do riêng.

1. **Bắt đầu từ đầu một kỳ đánh giá.** Một kỳ đánh giá chỉ được tính trên một cách làm duy nhất: hoặc hoàn toàn trên Mission, hoặc hoàn toàn theo cách cũ của tenant. Nếu tenant vào hệ giữa kỳ, điểm của kỳ đó sẽ thiếu các nhiệm vụ đã giao trước ngày vào hệ, và việc tính điểm mất căn cứ. Lý do này giống điều kiện "mỗi nhóm bắt đầu từ đầu một kỳ đánh giá" của TL-01 đợt 2. Kế hoạch dùng kỳ quý làm đơn vị.
2. **Chuẩn bị xong trước cửa sổ đóng băng.** Cửa sổ đóng băng OPS-06 kéo dài từ ngày 24 tháng cuối quý tới hết ngày 10 tháng đầu quý sau, và từ 20/1 tới 05/2. Ngày đầu quý luôn nằm trong cửa sổ này. Do đó mọi thay đổi trên môi trường sản xuất cho nhóm mới phải xong trước ngày bắt đầu đóng băng: tạo tenant, nạp dữ liệu, bật cờ tính năng, mở đăng nhập. Trong cửa sổ đóng băng chỉ còn hỗ trợ người dùng; không bật thêm cờ, trừ bản sửa lỗi nghiêm trọng qua CAB khẩn.
3. **Tách triển khai mã khỏi việc mở cho người dùng.** Mã mới được triển khai lên cell theo đợt phát hành, nhưng tenant chỉ thấy chức năng khi cờ tính năng OPS-04 của tenant đó được bật. Nhờ vậy, khi có sự cố ở một tenant, đội vận hành tắt cờ của riêng tenant đó mà không phải quay lui bản phát hành cho cả cell.
4. **Mở rộng theo nhóm, nhóm sau chỉ vào khi nhóm trước đã ra.** Mỗi nhóm có tiêu chí vào và tiêu chí ra đo được. Một nhóm có sự cố chưa khắc phục sẽ chặn nhóm sau. Lý do là mỗi nhóm mới làm tăng tải cho đội hỗ trợ trong đúng tuần đầu quý, cũng là tuần các tenant cũ khoá kỳ quý trước.
5. **Dữ liệu khởi tạo phải đối soát khớp với nguồn của tenant trước khi mở đăng nhập.** Dữ liệu tổ chức và nhân sự là căn cứ phân quyền và giao việc. Sai một đơn vị hoặc một chức vụ sẽ làm người dùng thấy sai việc ngay ngày đầu. Đối soát gồm so số dòng và so băm theo bảng (mục 5, bước B-04).

## 2. Cách chia nhóm tenant

Người phụ trách chia nhóm theo bốn tiêu chí, theo thứ tự ưu tiên sau.

- **Cell đích và đợt phát hành của cell.** Tenant chỉ vào cell đã vận hành ổn định ít nhất một đợt phát hành. Cell thuộc đợt phát hành sớm (CELL-G4, rồi CELL-SH và các cell nhóm tỉnh đợt 2) nhận nhóm trước. Cell hạng L và hạ tầng riêng (CELL-HN, CELL-HCM, CELL-DTW) nhận nhóm sau cùng. Mô hình thuê bao của tenant (pool, bridge, silo) phải khớp mô hình của cell đích; công cụ kiểm báo lỗi nếu không khớp.
- **Quy mô.** Nhóm đầu của mỗi cell nên nhỏ, để phát hiện vấn đề khi số người bị ảnh hưởng còn ít. Mỗi tenant hạng L (từ khoảng 500.000 tài khoản) đi thành một nhóm riêng. Công cụ cảnh báo khi tổng tài khoản của một cell vượt 80% sức chứa thiết kế (GD-01). Ngưỡng này là ngưỡng mở rà soát thêm cell OPS-23 hoặc chuyển tenant giữa cell OPS-08.
- **Mốc kỳ đánh giá của tenant.** Tenant có lịch kỳ khác thường (ví dụ chốt quý muộn) được xếp vào nhóm có kỳ đầu phù hợp lịch đó.
- **Năng lực hỗ trợ.** Mỗi nhóm không quá số tenant mà đội triển khai khách hàng hỗ trợ được trong tuần đầu. Giả định ban đầu là sáu tenant mỗi nhóm; giá trị này cần chỉnh lại theo nhân lực thật.

Bảng nhóm hiện tại dùng tenant hư cấu. Lệnh `python3 docs/van-hanh/dua_vao_su_dung.py kiem-tra` cho ra lịch dưới đây; mọi mốc đều tính từ tệp dữ liệu.

| Nhóm | Số tenant | Tài khoản | Xét vào | Onboarding | Hạn sẵn sàng | Kỳ đầu | Xét ra |
|---|---|---|---|---|---|---|---|
| N0, thí điểm (đợt 2) | 1 | 3.000 | 24/02/2027 | 03/03/2027 | 23/03/2027 | 01/04/2027 | sau nghiệm thu UAT, khoảng 20/07/2027 |
| N1 (đợt 3) | 5 | 154.000 | 27/08/2027 | 03/09/2027 | 23/09/2027 | 01/10/2027 | 31/10/2027 |
| N2 | 4 | 470.000 | 26/11/2027 | 03/12/2027 | 23/12/2027 | 01/01/2028 | 31/01/2028 |
| N3 | 4 | 283.000 | 25/02/2028 | 03/03/2028 | 23/03/2028 | 01/04/2028 | 01/05/2028 |
| N4 | 1 | 900.000 | 29/05/2028 | 05/06/2028 | 23/06/2028 | 01/07/2028 | 31/07/2028 |
| N5 | 2 | 1.300.000 | 28/08/2028 | 04/09/2028 | 23/09/2028 | 01/10/2028 | 31/10/2028 |

Hai điểm trong bảng cần giải thích. Thứ nhất, nhóm N1 bắt đầu từ Quý IV/2027 chứ không phải Quý III/2027. Nhóm N0 dùng hệ từ Quý II/2027 và chỉ nghiệm thu được sau khi khoá kỳ và trả lời khiếu nại, tức khoảng giữa tháng 7. Lúc đó đã quá hạn chuẩn bị cho Quý III. Thứ hai, công cụ đang cảnh báo nhóm N5: CELL-HCM sẽ lên 1.100.000 tài khoản, vượt 80% sức chứa 1.200.000 của cell hạng L. Trước khi xét vào N5, người phụ trách phải mở rà soát OPS-23 (tách cell) hoặc chọn cell khác cho tenant đó.

## 3. Ký hiệu mốc

Kế hoạch dùng hai mốc gốc cho mỗi nhóm. **T** là ngày bắt đầu kỳ đánh giá đầu tiên của nhóm. **S** là ngày bắt đầu cửa sổ đóng băng chứa T. Với kỳ quý, S là ngày 24 của tháng liền trước T. Các mốc khác tính lùi từ S theo ngày làm việc. Công cụ hiện chỉ loại thứ Bảy và Chủ nhật; ngày nghỉ lễ của khách hàng khai thêm ở trường `ngay_nghi_them`.

| Mốc | Thời điểm | Nội dung |
|---|---|---|
| Xét vào | S − 20 ngày làm việc | Họp xét tiêu chí vào của nhóm |
| Onboarding | S − 15 | Chạy quy trình OPS-07 cho từng tenant |
| Nạp dữ liệu | S − 12 | Nạp cơ cấu tổ chức, nhân sự, danh mục |
| Đối soát | S − 8 | Đối soát dữ liệu khởi tạo với nguồn của tenant |
| Bật cờ, mở đăng nhập | S − 5 | Bật cờ OPS-04, cấp tài khoản, mở đăng nhập để tập huấn và lập kế hoạch quý |
| Hạn sẵn sàng | S − 1 | Mọi thay đổi trên sản xuất cho nhóm đã xong |
| Kỳ đầu | T | Tenant bắt đầu kỳ đánh giá đầu tiên trên Mission |
| Xét ra | T + 30 ngày | Họp xét tiêu chí ra của nhóm |

## 4. Tiêu chí vào của một nhóm

Nhóm chỉ được bắt đầu onboarding khi họp xét vào xác nhận đủ các tiêu chí sau. Thiếu một tiêu chí thì nhóm lùi sang kỳ sau, không vào giữa kỳ.

| Mã | Tiêu chí | Cách kiểm |
|---|---|---|
| V-01 | Nhóm thí điểm N0: UAT của tenant thí điểm đạt theo `docs/uat/so-thu-nghiem/ke-hoach.md` (TC-01 đến TC-08), tức trọn một kỳ, ca mẫu vàng 100%, kiểm tra sổ cái hợp lệ cho cả kỳ. Nhóm khác: nhóm trước đã qua xét ra (mục 6). | Biên bản UAT hoặc biên bản xét ra của nhóm trước |
| V-02 | Cell đích ổn định: SLO của cell đạt trong 28 ngày gần nhất, ngân sách lỗi còn dương, không có sự cố SEV-1 hoặc SEV-2 đang mở. Cell không vượt ngưỡng rà soát OPS-23 sau khi cộng nhóm mới. | Bảng SLO của cell; `dua_vao_su_dung.py kiem-tra` không báo lỗi hoặc cảnh báo cho cell đó |
| V-03 | Dữ liệu tổ chức và nhân sự khởi tạo của từng tenant đã đối soát khớp với nguồn do tenant cung cấp (số dòng và băm theo bảng), không còn dòng lệch mức Cao. | Bước B-04; điều kiện kích hoạt "lệch mức Cao = 0" của tenant-registry |
| V-04 | Bộ chính sách đánh giá của tenant đã được ký phát hành và đạt 100% ca mẫu vàng; lịch kỳ (`config.period`) của kỳ đầu đã khai. | Màn hình chính sách đánh giá; kết quả chạy ca kiểm thử |
| V-05 | Tenant đã cử người đại diện cho từng vai trò AC-01 đến AC-10; quản trị đơn vị và cán bộ tổ chức đã tập huấn theo bộ hướng dẫn VH-05. | Danh sách người đại diện theo mẫu `tham-so.yaml` của QA-16 |
| V-06 | Lịch của nhóm không có mốc chuẩn bị nào rơi vào cửa sổ đóng băng; ca trực tăng cường của tuần đầu đã xếp. | `dua_vao_su_dung.py kiem-tra`; lịch trực |

## 5. Các bước đưa một nhóm vào sử dụng

Các bước dưới đây làm cho từng tenant trong nhóm. Các tenant trong cùng nhóm làm song song được.

1. **B-01. Onboarding OPS-07** (mốc Onboarding). Đội triển khai khách hàng nhập hồ sơ tenant đã duyệt trên màn hình khởi tạo đơn vị triển khai. Pipeline chạy tám bước tự động: ghi danh bạ, tạo CSDL hoặc dòng tenant, tạo khoá, tạo Organization Keycloak và IdP, nạp cây tổ chức, gán Policy Pack và UI Pack mặc định, tạo bản ghi tên miền, kiểm tra khói. Tenant đi qua các trạng thái `KHOI_TAO` → `DANG_CAP_PHAT` → `DANG_CAU_HINH`. Bước nào lỗi thì chạy lại từ bước đó; xử lý lỗi theo `ops/runbooks/cap-phat-kich-hoat-tenant.md`.
2. **B-02. Khai báo đăng nhập.** Quản trị đơn vị của tenant khai báo IdP (tài khoản cơ quan, VNeID, chứng thư số) và kiểm tra kết nối.
3. **B-03. Nạp dữ liệu khởi tạo** (mốc Nạp dữ liệu). Nạp cơ cấu tổ chức và nhân sự từ tệp Excel hoặc qua đồng bộ với CSDL cán bộ, công chức, viên chức và CSDL đảng viên; nạp danh mục công việc của tenant. Dữ liệu là dữ liệu mới của tenant, không lấy từ hệ thống đánh giá cũ.
4. **B-04. Đối soát** (mốc Đối soát). So số dòng và băm theo bảng giữa dữ liệu nguồn tenant cung cấp (đã chuẩn hoá Unicode và khoảng trắng) và dữ liệu đã nạp. Mọi dòng bị loại khi nạp (trùng mã, đơn vị cha không tồn tại, cán bộ thuộc đơn vị không tồn tại) phải được tenant sửa ở nguồn rồi nạp lại. Dòng lệch giữa tổ chức và nhân sự xử lý trên màn hình đồng bộ và đối soát nhân sự. Tiêu chí V-03 chỉ đạt khi hai phía khớp hoàn toàn.
5. **B-05. Gán chính sách và mở kỳ.** Gán bộ chính sách đánh giá đã ký cho từng luồng (Đảng, Nhà nước) và tạo kỳ đầu với lịch ngày nghỉ, ngày làm bù của tenant.
6. **B-06. Kích hoạt** (mốc Bật cờ, mở đăng nhập). Khi các điều kiện kích hoạt đạt (IdP, cây tổ chức, lệch mức Cao bằng 0, gói chính sách đạt ca vàng), tenant chuyển `SAN_SANG`. Sau khi đại diện tenant xác nhận, tenant chuyển `DANG_VAN_HANH`.
7. **B-07. Bật cờ tính năng OPS-04.** Bật cho tenant đúng danh mục cờ mà nhóm trước đã dùng ổn định. Cờ mới chưa qua nhóm trước không bật cho nhóm mới. Mọi thay đổi cờ đi qua kho GitOps nên có lịch sử và quay lui được.
8. **B-08. Cấp tài khoản và mở đăng nhập.** Cấp vai trò theo danh sách người đại diện và theo cơ cấu đã nạp; mở đăng nhập để người dùng tập huấn và lập kế hoạch quý, mục tiêu, OKR trước ngày T. Từ ngày T, tenant bắt đầu giao việc và cập nhật tiến độ trên hệ.

## 6. Tiêu chí ra của một nhóm

Họp xét ra diễn ra ở mốc T + 30 ngày. Riêng nhóm thí điểm N0 xét ra sau khi nghiệm thu UAT của trọn kỳ đầu. Nhóm được xem là đã ra khi đạt đủ các tiêu chí sau.

| Mã | Tiêu chí |
|---|---|
| R-01 | Không còn sự cố SEV-1 hoặc SEV-2 đang mở của các tenant trong nhóm; sự cố SEV-3, SEV-4 có phiếu và hướng xử lý. |
| R-02 | SLO của các cell đích đạt trong 30 ngày kể từ T; ngân sách lỗi không bị đốt quá 50% vì nhóm mới. |
| R-03 | Tỉ lệ sử dụng của từng tenant (mục 10) đạt ít nhất 80% sau 30 ngày; tenant dưới ngưỡng có kế hoạch hỗ trợ riêng. |
| R-04 | Lượng yêu cầu hỗ trợ mới mỗi ngày đã giảm về mức đội hỗ trợ thường trực xử lý được, không còn cần trực tăng cường. |
| R-05 | Không tenant nào trong nhóm đang ở trạng thái tạm ngừng hoặc đang tắt cờ vì sự cố. |

## 7. Tạm dừng mở rộng và xử lý sự cố của một tenant

Vì Mission là hệ mới và không có hệ cũ để chuyển ngược lại, "quay lui" ở đây không phải là đưa người dùng về hệ cũ. Quay lui là thu hẹp phạm vi bị ảnh hưởng theo ba mức, từ nhẹ tới nặng. Dữ liệu tenant đã ghi luôn được giữ nguyên.

| Mức | Khi nào | Bước thực hiện | Ai quyết định |
|---|---|---|---|
| D-1. Tắt cờ của một tenant | Một chức năng mới gây lỗi SEV-3 trở lên ở một tenant | Tắt cờ của chức năng đó cho riêng tenant qua kho GitOps; tenant tiếp tục dùng các chức năng đã ổn định; ghi phiếu sự cố. | Trưởng ca trực cùng chủ sản phẩm |
| D-2. Tạm dừng mở rộng | Một tenant của nhóm có sự cố SEV-1 hoặc SEV-2 chưa khắc phục, hoặc cell đích vi phạm SLO, hoặc tỉ lệ sử dụng của nhóm dưới 50% sau 14 ngày | Không xét vào nhóm kế tiếp; nhóm kế tiếp lùi sang kỳ sau; thông báo cho các tenant của nhóm kế tiếp theo kênh ở mục 9. | Người phụ trách triển khai, báo Hội đồng kiến trúc |
| D-3. Tạm ngừng một tenant | Sự cố an ninh, hoặc sai dữ liệu nghiêm trọng mà tiếp tục ghi sẽ làm hỏng thêm | Chuyển tenant `DANG_VAN_HANH` → `TAM_NGUNG` bằng `vtctl tenant suspend <tenant> --reason "<số văn bản>" --confirm` (API `suspendConfigTenant`, cần xác thực nhiều lớp): chặn đăng nhập ở Keycloak và chặn ghi ở APISIX, giữ nguyên dữ liệu. Khắc phục, kiểm lại, rồi chuyển `TAM_NGUNG` → `DANG_VAN_HANH` bằng `vtctl tenant activate`. Nếu thời gian ngừng làm tenant không kịp các mốc của kỳ, tenant đề nghị điều chỉnh lịch kỳ; nếu không thể hoàn thành kỳ trên hệ, tenant đánh giá kỳ đó theo cách hiện hành và dùng lại Mission từ kỳ sau. | Lãnh đạo đơn vị vận hành cùng đại diện tenant |

Bảng chuyển trạng thái của tenant-registry không cho tenant đi thẳng từ `TAM_NGUNG` về `SAN_SANG` hay `KHOI_TAO`. Vì vậy mọi lần mở lại đều đi qua `DANG_VAN_HANH`, có nhật ký và lý do. Buổi diễn tập đã kiểm điều này (mục 11).

## 8. Chuyển tenant giữa các cell (OPS-08)

Quy trình này dùng khi một tenant tăng trưởng vượt sức chứa của cell pool, khi sáp nhập đơn vị, hoặc khi công cụ kiểm báo một cell sắp vượt ngưỡng (như cảnh báo của nhóm N5). Đây là việc chuyển giữa các cell của chính Mission, không phải chuyển từ hệ cũ. Các bước chính theo TL-04 mục 11.4:

1. Tạo cấu trúc ở cell đích, nhân bản logic dữ liệu của tenant và sao chép đối tượng minh chứng.
2. Khi độ trễ nhân bản về 0, đặt tenant ở chế độ chỉ đọc từ 5 đến 15 phút, đợi đồng bộ cuối, đối soát số dòng và băm theo bảng.
3. Đổi cell của tenant trong danh bạ và bản ghi tên miền, rồi mở lại ghi ở cell đích. Sổ cái được chuyển nguyên chuỗi, kèm bản ghi "chuyển cell" ký bằng khoá của cả hai cell.
4. Giữ dữ liệu ở cell nguồn 30 ngày rồi xoá.

Thao tác chi tiết, bốn mắt, kiểm chứng và quay lui nằm trong sổ tay [`ops/runbooks/chuyen-tenant-giua-cell.md`](../../ops/runbooks/chuyen-tenant-giua-cell.md). Không chuyển cell trong cửa sổ đóng băng, và không chuyển trong khi tenant đang khoá kỳ.

## 9. Truyền thông và hỗ trợ người dùng

| Thời điểm | Việc | Kênh |
|---|---|---|
| T − 45 ngày | Thông báo chính thức cho tenant: kỳ đầu, lịch tập huấn, đầu mối | Văn bản của đơn vị vận hành gửi lãnh đạo tenant |
| Mốc Onboarding | Tập huấn quản trị đơn vị, cán bộ tổ chức, quản trị nghiệp vụ theo tài liệu AC-02, AC-03, AC-07 của bộ VH-05 | Lớp trực tuyến, kèm bộ hướng dẫn |
| Mốc Bật cờ, mở đăng nhập | Gửi người dùng thông báo đăng nhập kèm phần thao tác dùng chung và tài liệu theo vai trò; tập huấn lãnh đạo, quản lý trực tiếp, cán bộ | Thư điện tử công vụ, thông báo trên hệ, bản in PDF từ `docs/huong-dan-su-dung/dung-ban-in.py` |
| T − 3 ngày | Nhắc ngày bắt đầu kỳ và các việc đầu kỳ: lập kế hoạch quý, soạn mục tiêu | Thông báo trên hệ |
| T tới T + 7 | Trực tăng cường: đội hỗ trợ trực theo giờ hành chính mở rộng, có người của đội triển khai cho từng tenant; tổng hợp câu hỏi thường gặp mỗi ngày và bổ sung vào bộ hướng dẫn | Kênh hỗ trợ của đơn vị, đầu mối theo tenant |
| T + 30 | Khảo sát ngắn mức hài lòng, ghi riêng phản hồi về chế độ Đảng viên cao tuổi | Biểu mẫu trên hệ |

Tuần đầu của nhóm mới luôn trùng tuần các tenant cũ khoá kỳ quý trước. Vì vậy lịch trực tăng cường phải được xác nhận trong danh mục chuẩn bị trước mốc hạn OPS-17.

## 10. Theo dõi tỉ lệ sử dụng

TL-01 đặt chỉ số "tỉ lệ người dùng trên nền tảng mới": sau đợt 2 là tenant thí điểm, sau đợt 3 là 100%. Với một hệ mới không thay thế hệ cũ, chỉ số này được đo theo hai tầng:

- **Tỉ lệ tenant đã đưa vào sử dụng.** Bằng số tenant ở trạng thái `DANG_VAN_HANH` chia cho số tenant đã đăng ký dùng hệ. Mục tiêu sau đợt 3 là 100%.
- **Tỉ lệ sử dụng trong từng tenant.** Bằng số người dùng có ít nhất một thao tác nghiệp vụ trong 30 ngày (cập nhật tiến độ, check-in, nộp minh chứng, duyệt) chia cho số tài khoản đã cấp. Ngưỡng ra của nhóm là 80% (R-03).

Bảng theo dõi dùng mẫu [`theo-doi-ti-le.csv`](theo-doi-ti-le.csv), mỗi dòng là một tenant ở một ngày đo. Bảng này là đầu vào của VH-09 (chấm lại bảng chỉ số chuyển đổi mỗi quý). Dòng hiện có trong tệp là số liệu giả lập của buổi diễn tập.

## 11. Diễn tập

Buổi diễn tập ngày 23/10/2026 đưa nhóm thử N-THU (hai tenant hư cấu) vào sử dụng trên bản giả lập. Sau đó buổi diễn tập tắt cờ của một tenant và tạm ngừng tenant còn lại. Biên bản ở [`ops/dien-tap/2026-10-23-mo-rong-nhom.md`](../../ops/dien-tap/2026-10-23-mo-rong-nhom.md). Buổi diễn tập trên staging thật chưa làm được vì chưa có môi trường staging; biên bản ghi rõ phần nào đã chạy với mã thật và phần nào chạy trên bản giả lập. Buổi diễn tập lần 2 ngày 24/10/2026 ([biên bản](../../ops/dien-tap/2026-10-24-mo-rong-nhom.md)) chạy lại bản giả lập và kiểm chuỗi tạm ngừng tenant bằng mã thật. Kết quả: tenant chuyển được sang tạm ngừng và các dịch vụ từ chối yêu cầu của tenant đó; việc chặn đăng nhập ở Keycloak khi đó chưa có mã. Ngay sau buổi diễn tập, hai phát hiện đã được sửa bằng mã (biên bản mục 7): Keycloak tự chặn đăng nhập của tenant tạm ngừng, còn APISIX và các dịch vụ cho tenant tạm ngừng đọc dữ liệu nhưng chặn lệnh ghi. Vì vậy mức D-3 không còn bước tay tắt Organization trên trang quản trị Keycloak.

## 12. Khi nối với hệ thật

Người phụ trách làm theo bốn bước.

1. Thay `nhom-tenant.yaml` bằng danh sách tenant thật, cell đích, kỳ đầu và số tài khoản; khai thêm ngày nghỉ lễ.
2. Chạy `dua_vao_su_dung.py kiem-tra` và xử lý mọi lỗi, cảnh báo.
3. Với mỗi nhóm, thay thư mục dữ liệu diễn tập bằng tệp nguồn do tenant cung cấp, chạy đối soát ở bước B-04, rồi đưa vào sử dụng trên staging trước sản xuất.
4. Ghi biên bản xét vào, xét ra của từng nhóm vào `ops/dien-tap/` hoặc hồ sơ vận hành.

Quy trình ở các mục 4 đến 9 không đổi.

## Liên quan

- [Bảng nhóm tenant](nhom-tenant.yaml), [công cụ kiểm và diễn tập](dua_vao_su_dung.py), [bảng theo dõi tỉ lệ](theo-doi-ti-le.csv)
- [Bộ UAT tenant thí điểm](../uat/so-thu-nghiem/README.md) (QA-16)
- [Hướng dẫn sử dụng theo vai trò](../huong-dan-su-dung/README.md) (VH-05)
- [Sổ tay cấp phát và kích hoạt tenant](../../ops/runbooks/cap-phat-kich-hoat-tenant.md), [sổ tay chuyển tenant giữa cell](../../ops/runbooks/chuyen-tenant-giua-cell.md)
