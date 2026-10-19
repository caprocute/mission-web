> Nguồn: Baseline mục 1 (chuỗi truy vết), 4, 5, 5.1, 7 (EVT), 9, 9.1 (trạng thái chuẩn), 10 (AC), 11 (QT, SCR), 12 (dải mã), 13; TL-02 chương "Ma trận truy vết" và các mục "Trạng thái đối tượng" của QT-06, QT-11 đến QT-15, QT-18; TL-03 các bảng trạng thái; TL-01 Phụ lục C (vai trò); `AGENTS.md` mục 5 (quy ước nhánh, commit).

# Mã định danh và truy vết

Bộ tài liệu thiết kế dùng một hệ mã định danh thống nhất để mọi quyết định đều lần được từ yêu cầu nghiệp vụ tới thành phần triển khai. Với phân hệ web, hệ mã này có hai công dụng hằng ngày: biết một màn hình phải gọi API nào và hiển thị trạng thái nào, và biết một thay đổi ở API hay bảng dữ liệu ảnh hưởng tới những màn hình nào. Trang này tóm tắt các loại mã, chuỗi truy vết và quy tắc dùng mã trong mã nguồn, commit, PR và tên tệp.

## Nguyên tắc chung

Baseline là "nguồn sự thật" của mọi mã ADR, SVC, MOD, EVT, QT, SCR, BR, NFR, SEC, tên schema, tên bảng và tên trạng thái. Một tài liệu chỉ được thêm mã trong dải được cấp cho nó và không được đổi nghĩa mã đã có. Quy tắc này áp dụng nguyên vẹn cho mã nguồn web: không tự đặt mã màn hình mới, không đổi tên giá trị trạng thái (ví dụ phải là `CHO_NGHIEM_THU`, không phải `PENDING_REVIEW`), không đặt tên bảng khác với TL-03. Khi mã nguồn cần một khái niệm chưa có mã, người làm ghi đề xuất vào [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md) để người phụ trách thiết kế cấp mã, thay vì tự tạo.

Baseline mục 12 cấp dải mã như sau: TL-01 dùng ADR-01 đến ADR-40 và quy ước API `/api/v1/<module>/...`; TL-02a dùng BR-00-xx đến BR-08-xx và MSG-00-xx đến MSG-08-xx; TL-02b dùng BR-09-xx đến BR-18-xx và MSG-09-xx đến MSG-18-xx; TL-03 được thêm bảng mới nhưng không đổi tên bảng đã có; TL-04 dùng SEC-01 đến SEC-99, OPS-01 đến OPS-99, PERF-01 đến PERF-99, DR-01 đến DR-20.

## Các loại mã

Bảng dưới liệt kê các loại mã mà người làm web thường gặp, nơi định nghĩa và dạng viết.

| Loại | Dạng | Định nghĩa ở | Ví dụ |
|---|---|---|---|
| Quyết định kiến trúc | `ADR-nn` | Baseline mục 4, TL-01 Phụ lục A | ADR-05 giao diện theo tenant bốn lớp |
| Chỉ tiêu phi chức năng | `NFR-nn` | Baseline mục 3 | NFR-11 trải nghiệm |
| Service | `SVC-nn` | Baseline mục 5 | SVC-17 web-frontend |
| Module của mission-core | `MOD-nn` | Baseline mục 5.1 | MOD-02 `task` |
| Sự kiện | `EVT-nn` | Baseline mục 7 | EVT-23 UiPackDaPhatHanh |
| Tác nhân | `AC-nn` | Baseline mục 10 | AC-06 `member` |
| Quy trình | `QT-nn` | Baseline mục 11, TL-02 | QT-06 cập nhật tiến độ |
| Màn hình | `SCR-qqnn` (hai chữ số đầu là số QT) | Baseline mục 11, TL-02 | SCR-0601 chi tiết nhiệm vụ |
| Quy tắc nghiệp vụ | `BR-qq-nn` | TL-02 từng QT | BR-06-03 |
| Thông báo | `MSG-qq-nn` | TL-02 từng SCR | MSG-09-07 xung đột 409 |
| Biện pháp ATTT | `SEC-nn` | TL-04 mục 9.2 | SEC-19 chống XSS, CSP |
| Quy trình vận hành | `OPS-nn` | TL-04 mục 11.1 | OPS-11 phát hành UI Pack |
| Kịch bản thử tải | `PERF-nn` | TL-04 mục 7.4 | PERF-21 hiệu năng frontend |
| Kịch bản khôi phục | `DR-nn` | TL-04 mục 8.11 | DR-10 |
| SLO | `SLO-<TÊN>` | TL-04 mục 7.1 | SLO-LCP |
| Cell | `CELL-<TÊN>` | Baseline mục 6 | CELL-G4 |
| Bảng dữ liệu | `schema.bảng` | Baseline mục 9, TL-03 | `task.task_progress` |

Mã MSG đặc biệt quan trọng với frontend vì đó là văn bản người dùng nhìn thấy. TL-02 viết sẵn nội dung tiếng Việt của từng thông báo kèm biến trong ngoặc nhọn, ví dụ MSG-09-09 "Đã trả lại hồ sơ cho đồng chí {ten} bổ sung. Lần trả lại thứ {n}/{toi_da}.". Lỗi trả về theo RFC 9457 có trường `code` để ánh xạ sang MSG (xem [Quy ước API](06-quy-uoc-api.md)).

## Chuỗi truy vết bắt buộc

Baseline mục 1 định nghĩa chuỗi truy vết bắt buộc: Quy trình (QT, TL-02) sang Màn hình (SCR, TL-02), sang API và module hoặc service (MOD/SVC, TL-01), sang bảng dữ liệu và sự kiện (`schema.bảng`, EVT, TL-03), rồi sang thành phần triển khai, chỉ tiêu năng lực và biện pháp ATTT (TL-04). Phía web bổ sung một mắt xích giữa SCR và API là remote chịu trách nhiệm màn hình. Chuỗi đầy đủ mà wiki dùng là:

```text
QT-nn ─► SCR-qqnn ─► remote fe-* (tuyến) ─► /api/v1/<module>/... ─► MOD-nn hoặc SVC-nn ─► schema.bảng, EVT-nn ─► SEC/PERF/OPS
```

Ví dụ với cập nhật tiến độ nhiệm vụ: QT-06 sang SCR-0601 "Chi tiết nhiệm vụ", do remote `fe-task` hiển thị ở tuyến `/tasks/:id`, gọi `POST /api/v1/tasks/{id}/progress` (TL-02; TL-01 ghi `PATCH`, xem trang 14), do MOD-02 `task` trong SVC-05 xử lý, ghi `task.task_progress`, phát EVT-05 `TienDoDaCapNhat`, được đo bằng PERF-25 (chi phí bảy lớp bảo vệ trên API cập nhật tiến độ) và SLO-WRITE. Ma trận truy vết đầy đủ cho QT-00 đến QT-18 nằm ở cuối TL-02 và được tóm lại trong hai trang [05a](05a-quy-trinh-va-man-hinh-qt00-08.md), [05b](05b-quy-trinh-va-man-hinh-qt09-18.md).

Chiều ngược của chuỗi dùng khi đánh giá ảnh hưởng: nếu TL-03 đổi một cột của `appraisal.rating_proposal`, người làm web tra ngược ra SCR-0902, SCR-0903, SCR-0904 và kiểm lại các bảng trường của ba màn hình đó.

## Danh mục sự kiện

Trình duyệt không đọc Kafka, nhưng tên sự kiện xuất hiện trong bảng chức năng của mỗi SCR (cột "Sự kiện phát") và quyết định thông báo đẩy nào người dùng nhận. Topic theo dạng `<miền>.<thực-thể>.<sự-kiện>.v1`; mọi sự kiện mang `eventId` (UUIDv7), `tenantId`, `occurredAt`, `actorId`, `traceparent`, `policyVersion` (nếu có) và `classification`.

| Mã | Tên | Topic | Phát bởi |
|---|---|---|---|
| EVT-01 | MucTieuDaDuyet | okr.objective.approved.v1 | MOD-01 |
| EVT-02 | KrDaCheckIn | okr.keyresult.checkedin.v1 | MOD-01 |
| EVT-03 | KeHoachDaDuyet | task.plan.approved.v1 | MOD-02 |
| EVT-04 | NhiemVuDaGiao | task.task.assigned.v1 | MOD-02 |
| EVT-05 | TienDoDaCapNhat | task.task.progressed.v1 | MOD-02 |
| EVT-06 | TrangThaiNhiemVuDoi | task.task.statuschanged.v1 | MOD-02 |
| EVT-07 | MinhChungDaNop | product.evidence.submitted.v1 | MOD-03 |
| EVT-08 | MinhChungDaXacNhan | product.evidence.accepted.v1 | MOD-03 |
| EVT-09 | KyDaKhoa | config.period.locked.v1 | MOD-08 qua saga SVC-06 |
| EVT-10 | DiemDaTinh | scoring.score.computed.v1 | SVC-07 |
| EVT-11 | HoSoDanhGiaChuyenBuoc | appraisal.profile.stepcompleted.v1 | MOD-04 |
| EVT-12 | XepLoaiDaQuyetDinh | appraisal.rating.decided.v1 | MOD-04 |
| EVT-13 | PolicyPackDaPhatHanh | policy.pack.published.v1 | MOD-05 (SVC-17 tiêu thụ) |
| EVT-14 | DonViDaThayDoi | org.unit.changed.v1 | SVC-04 |
| EVT-15 | PhanCongDaThayDoi | org.assignment.changed.v1 | SVC-04 |
| EVT-16 | NhanSuDaThayDoi | org.person.changed.v1 | SVC-04 |
| EVT-17 | HoSoSoDaSinh | dossier.form.generated.v1 | SVC-11 |
| EVT-18 | KySoDaHoanTat | esign.signature.completed.v1 | SVC-10 |
| EVT-19 | SoCaiDaNiemPhong | ledger.seal.created.v1 | SVC-09 |
| EVT-20 | DauHieuBatThuong | analytics.anomaly.detected.v1 | SVC-15 |
| EVT-21 | YeuCauThongBao | notify.message.requested.v1 | Mọi module |
| EVT-22 | KyDaMoLaiHauKiem | oversight.period.reopened.v1 | MOD-07 |
| EVT-23 | UiPackDaPhatHanh | config.uipack.published.v1 | MOD-08 (SVC-17 vô hiệu cache CDN) |
| EVT-24 | VanBanChiDaoDaNhan | integ.document.received.v1 | SVC-13 |
| EVT-25 | KhieuNaiDaGui | oversight.complaint.filed.v1 | MOD-07 |
| EVT-26 | KyDaHuyKhoa | config.period.unlocked.v1 | MOD-08 qua saga SVC-06 |

## Trạng thái chuẩn

Baseline mục 9.1 định nghĩa trạng thái của các đối tượng chính. Giao diện phải dùng đúng giá trị này trong mô hình dữ liệu TypeScript, bộ lọc và nhãn; nhãn tiếng Việt chỉ là phần hiển thị. Ví dụ `TaskStatus` trong `@vt/api-client` khai báo đúng bảy giá trị của `task.task.status`.

| Đối tượng (cột) | Trạng thái |
|---|---|
| Kỳ đánh giá `config.period.status` | `MO` → `DANG_KHOA` → `DA_KHOA` → (`MO_LAI_HAU_KIEM` → `DA_KHOA`) |
| Kế hoạch `task.plan_version.status` | `NHAP` → `CHO_DUYET` → `DA_DUYET` hoặc `TRA_LAI`; điều chỉnh tạo phiên bản mới |
| Nhiệm vụ `task.task.status` | `NHAP` → `DA_GIAO` → `DANG_THUC_HIEN` → `CHO_NGHIEM_THU` → `HOAN_THANH`; nhánh `TAM_DUNG`, `HUY`; cờ `qua_han` |
| Minh chứng `product.evidence_version.status` | `NHAP` → `DA_NOP` → `DANG_NGHIEM_THU` → `DA_XAC_NHAN`, `YEU_CAU_BO_SUNG` hoặc `TU_CHOI`; chỉ `DA_XAC_NHAN` được tính điểm |
| Mục tiêu `okr.objective.status` | `NHAP` → `CHO_DUYET` → `DA_DUYET` → `DONG` |
| Hồ sơ đánh giá `appraisal.appraisal_profile.status` | `CHUA_LAP` → `TU_DANH_GIA` → `CHO_THAM_DINH` → `DA_THAM_DINH` → `CHO_QUYET_DINH` → `DA_QUYET_DINH` → `DA_KY_SO` → `DA_KHOA`; nhánh `KHIEU_NAI` |
| Policy Pack `policy.policy_pack_version.status` | `NHAP` → `DANG_KIEM_THU` → `DAT_KIEM_THU` → `CHO_PHE_DUYET` → `DA_PHAT_HANH` → `NGUNG` |
| Mức xếp loại | `HTXSNV`, `HTTNV`, `HTNV`, `KHTNV` |
| Mức chất lượng nghiệm thu | 1 / 0,75 / 0,5 / 0,25 / 0 |
| Luồng đánh giá | `DANG` (100 điểm), `NHA_NUOC` (30/70) |

TL-02 và TL-03 bổ sung trạng thái cho các đối tượng không có trong baseline. Những giá trị này hợp lệ vì nằm trong phạm vi của tài liệu đã thêm chúng; giao diện dùng nguyên văn.

| Đối tượng | Trạng thái | Nguồn |
|---|---|---|
| `config.ui_pack_version.status` | `NHAP`, `DANG_KIEM_TRA`, `CHO_DUYET`, `DA_PHAT_HANH`, `THAY_THE` | TL-02 QT-18, TL-03 |
| `config.form_schema.status` | `NHAP`, `DA_PHAT_HANH`, `NGUNG` | TL-03 |
| `config.component_registry.status` | `HIEU_LUC`, `NGUNG` | TL-03 |
| `config.dashboard_def.share_scope` | `RIENG`, `DON_VI`, `TENANT` | TL-03 |
| `dossier.dossier_package` | `DANG_LAP` → `DANG_SINH` → `CHO_KY` → `DA_KY` → `DA_NOP` → `DA_LUU_TRU` | TL-02 QT-11 |
| `oversight.complaint` | `MOI`, `DA_TIEP_NHAN`, `DANG_GIAI_TRINH`, `CHO_KET_LUAN`, `DA_TRA_LOI`, `DANG_THI_HANH`, `KHONG_THU_LY`, `DONG` (TL-03 thêm `RUT`) | TL-02 QT-12, TL-03 |
| `oversight.reopen_request` | `CHO_DUYET_1`, `CHO_DUYET_2`, `DA_DUYET`, `TU_CHOI` (TL-03 thêm `DA_THUC_HIEN`, `DA_DONG`) | TL-02 QT-13, TL-03 |
| `oversight.anomaly_alert` | `MOI`, `DANG_XEM`, `HOP_LE`, `DA_GIAO_KIEM_TRA`, `CO_VI_PHAM`, `KHONG_VI_PHAM`, `TU_DONG_DONG`, `DONG` | TL-02 QT-14 |
| `registry.tenant` | `KHOI_TAO`, `DANG_CAP_PHAT`, `DANG_CAU_HINH`, `SAN_SANG`, `DANG_VAN_HANH`, `TAM_NGUNG`, `DA_CHAM_DUT` (TL-03 thêm `DANG_CHUYEN_CELL`) | TL-02 QT-01, TL-03 |
| `task.task_change_request` | `NHAP`, `CHO_DUYET`, `DA_DUYET`, `TU_CHOI`, `DA_RUT` | TL-02 QT-06 |
| `okr.kr_checkin` | `NHAP`, `DA_GUI` | TL-02 QT-03 |
| Cờ rủi ro KR (`risk_flag`) | `BINH_THUONG`, `THEO_DOI`, `RUI_RO` | TL-02 QT-03 |
| Mức bảo đảm xác thực của phiên (`acr`) | `CHUA_XAC_THUC`, `AAL1`, `AAL2`, `HET_HAN`, `BI_THU_HOI` | TL-02 QT-00 |
| Lệnh khoá kỳ (`period_lock_order`) | `NHAP`, `CHO_DUYET`, `DA_DUYET`, `DANG_CHAY`, `HOAN_TAT`, `DA_HUY` | TL-02 QT-08 |
| Bước saga khoá kỳ (`period_lock_step`) | `CHO`, `DANG_CHAY`, `DAT`, `DAT_CO_CANH_BAO`, `LOI`, `DA_BU_TRU` | TL-02 QT-08 |

## Tác nhân và vai trò

Baseline mục 10 định nghĩa mười hai tác nhân và vai trò hệ thống tương ứng trong Keycloak và OPA. Kiểu `VtRole` trong `@vt/auth` dùng đúng mười một vai trò của người và trợ lý AI; AC-11 là hệ thống ngoài dùng client credentials nên không xuất hiện ở trình duyệt.

| Mã | Tác nhân | Vai trò |
|---|---|---|
| AC-01 | Quản trị nền tảng (Viettel) | `platform_admin` |
| AC-02 | Quản trị tenant | `tenant_admin` |
| AC-03 | Quản trị nghiệp vụ, BA cấu hình (cơ quan tổ chức) | `policy_author` |
| AC-04 | Lãnh đạo, người đứng đầu | `leader` |
| AC-05 | Cán bộ quản lý trực tiếp (giao việc, nghiệm thu) | `manager` |
| AC-06 | Cán bộ, công chức, viên chức, đảng viên | `member` |
| AC-07 | Cơ quan tổ chức cán bộ (thẩm định) | `appraiser` |
| AC-08 | Cấp có thẩm quyền quyết định | `decider` |
| AC-09 | Người duyệt thứ hai (four-eyes) | `second_approver` |
| AC-10 | Cơ quan kiểm tra, giám sát | `inspector` |
| AC-11 | Hệ thống ngoài (vOffice, iOffice, CSDL CBCCVC, VNeID, MySign) | client credentials |
| AC-12 | Trợ lý AI (chỉ đọc) | `ai_readonly` |

Phân quyền là RBAC theo vai trò cộng ABAC theo cây tổ chức, luồng (`DANG`/`NHA_NUOC`) và lớp dữ liệu (C0 đến C3) qua OPA. Tách vai trò theo PL 4 V.1 nghĩa là với cùng một hồ sơ, người khởi tạo, người nghiệm thu, người thẩm định, người quyết định và người khoá kỳ phải khác nhau. Danh sách vai trò ở TL-01 Phụ lục C có một số tên khác baseline (ví dụ `staff`, `org_officer`, `policy_approver`); wiki và mã dùng tên của baseline, điểm lệch được ghi ở trang 14.

## Quy tắc dùng mã trong mã nguồn, commit, PR và tên tệp

Các quy tắc dưới đây là quy ước của dự án web, tổng hợp từ `AGENTS.md` và cách mã hiện tại đang làm. Mục đích là để công cụ tìm kiếm (kể cả AI agent) lần được từ một mã SCR tới đúng tệp mã.

Mỗi component trang ghi mã SCR và QT trong chú thích đầu lớp, ví dụ `/** SCR-0601 Chi tiết nhiệm vụ: cập nhật tiến độ và báo điểm nghẽn (QT-06). */` trong `task-detail.page.ts`. Widget SDUI ghi tên registry và màn hình chứa nó, ví dụ `vt.widget.myTasksToday` trên SCR-0002. Tuyến trong `app.routes.ts` có chú thích nhóm QT mà remote phụ trách. Client API ghi module xử lý, ví dụ "Client cho API `/api/v1/tasks` của MOD-02".

Tên tệp và tên lớp dùng tiếng Anh theo quy ước Angular (`my-tasks.page.ts`, `MyTasksPage`), không nhúng mã SCR vào tên tệp, vì một trang có thể phục vụ nhiều SCR (ví dụ bản desktop và bản rút gọn). Giá trị trạng thái, tên trường API và tên bảng giữ nguyên dạng của nguồn (`DA_GIAO`, `rowVersion` ánh xạ `row_version`), chỉ nhãn hiển thị được dịch sang tiếng Việt. Mã thông báo MSG được dùng làm khoá khi ánh xạ lỗi sang văn bản, không chép văn bản tự đặt.

Nhánh Git đặt dạng `feat/WEB-xxx-<ten-ngan>` (hoặc `fix/...`, `docs/...`), trong đó WEB-xxx là mã task trong `docs/tasks/`. Thông điệp commit dạng `WEB-xxx: <mô tả tiếng Việt>`, thân commit ghi mã SCR, QT liên quan. Mô tả PR và báo cáo cuối task có dòng "Truy vết: QT-.. → SCR-.. → API .. → MOD-..". Mã WEB-xxx là mã quản lý công việc của dự án web, không thuộc hệ mã thiết kế và không được dùng thay cho QT, SCR.

## Liên quan

- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Quy trình và màn hình QT-00 đến QT-08](05a-quy-trinh-va-man-hinh-qt00-08.md)
- [Quy trình và màn hình QT-09 đến QT-18](05b-quy-trinh-va-man-hinh-qt09-18.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md)
- [Thuật ngữ](13-thuat-ngu.md)
