/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/dossiers.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/dossiers/meetings': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách hội nghị kiểm điểm trong phạm vi người gọi
     * @description Trả các hội nghị mà người gọi là thành phần (chủ trì, thư ký, thành viên, người được kiểm điểm) hoặc thuộc đơn vị người gọi chuẩn bị (cơ quan tổ chức). Đường vào "Hồ sơ số › Kiểm điểm" của SCR-1101 (suy luận: bảng chức năng SCR-1101 không ghi API danh sách).
     */
    get: operations['listDossierMeetings'];
    put?: never;
    /**
     * Tạo hội nghị kiểm điểm
     * @description Bước 1 của QT-11: cơ quan tổ chức lập kế hoạch kiểm điểm với đối tượng, chủ trì, thư ký, thành phần, ngày giờ và địa điểm. Hội nghị bắt đầu ở `DU_KIEN`. Khi tạo, MOD-06 chạy phát hiện trường hợp đặc thù (bước 2) và yêu cầu SVC-11 sinh dự thảo Mẫu 1A đến 1D cho đối tượng (bước 3, EVT-17 khi xong).
     */
    post: operations['createDossierMeeting'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/meetings/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết hội nghị kiểm điểm
     * @description Dữ liệu ba cột của SCR-1101: thẻ kiểm tra thời hạn gửi dự thảo (vùng 1), thành phần và trạng thái từng người, dự thảo biểu mẫu, kết quả biểu quyết so với mức đề xuất của hệ thống (vùng 4). Danh sách góp ý và hạn chế kỳ trước đọc ở hai đường dẫn con.
     */
    get: operations['getDossierMeeting'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    /**
     * Sửa kế hoạch hội nghị (dời ngày, địa điểm, thành phần, lý do giữ ngày)
     * @description Dùng khi kiểm tra thời hạn không đạt (MSG-11-01): cơ quan tổ chức dời ngày hội nghị, hoặc chủ trì ghi lý do được cấp uỷ chấp thuận để giữ nguyên ngày (lý do in vào biên bản, BR-11-01). Chỉ sửa được khi hội nghị ở `DU_KIEN` hoặc `DA_GUI_DU_THAO`. Thao tác này là suy luận từ mục "Ngoại lệ và xử lý" của QT-11; bảng chức năng SCR-1101 không ghi API.
     */
    patch: operations['updateDossierMeeting'];
    trace?: never;
  };
  '/dossiers/meetings/{id}/send-draft': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Gửi dự thảo bản kiểm điểm tới thành viên
     * @description Bước 4–5 của QT-11. Người được kiểm điểm gửi dự thảo đã hoàn thiện; máy chủ ghi `draftSentAt`, gửi thông báo mời góp ý (MSG-11-02, EVT-21) và đếm số ngày làm việc trọn vẹn giữa thời điểm gửi và ngày hội nghị theo `config.holiday_calendar` và tham số `kiem_diem.cach_dem_ngay`. Thời hạn không đạt không làm hỏng thao tác: phản hồi 200 mang `deadlineCheck.passed = false` và `msg = MSG-11-01` để giao diện cảnh báo (BR-11-01). Hội nghị chuyển `DA_GUI_DU_THAO`.
     */
    post: operations['sendDossierMeetingDraft'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/meetings/{id}/comments': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Danh sách góp ý của hội nghị
     * @description Vùng 2 của SCR-1101: góp ý trước hội nghị và ý kiến thư ký ghi tại hội nghị, phân loại ưu điểm, hạn chế, kiến nghị. Nội dung là C2, máy chủ giải mã theo quyền và ghi nhật ký truy cập. Khi tenant bật chế độ ẩn danh, OPA lọc trường `commenter` với người được kiểm điểm (BR-11-06).
     */
    get: operations['listDossierMeetingComments'];
    put?: never;
    /**
     * Gửi góp ý hoặc ghi ý kiến tại hội nghị
     * @description Bước 6–7 của QT-11. Thành viên gửi góp ý trước hoặc trong hội nghị (`phase = TRUOC_HOI_NGHI`); chỉ thư ký được ghi ý kiến tại hội nghị (`phase = HOI_NGHI`). Nội dung C2 được mã hoá trường ở máy chủ (`comment_enc`, `dek_id`). Không nhận góp ý sau khi chủ trì kết thúc hội nghị.
     */
    post: operations['createDossierMeetingComment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/meetings/{id}/minutes': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Ghi biểu quyết và sinh biên bản hội nghị
     * @description Bước 7–8 của QT-11. Thư ký gửi kết quả điểm danh và biểu quyết; máy chủ ghi `vote_json`, `participants_json`, chuyển hội nghị `DA_HOP` và yêu cầu SVC-11 sinh biên bản (trả 202). Khi EVT-17 đến, hội nghị chuyển `DA_LAP_BIEN_BAN`. Biểu quyết không thay mức quyết định của cấp có thẩm quyền (BR-11-07). Lý do giữ ngày họp (nếu có) được in vào biên bản. Theo TL-01 PL C, thao tác này cần phiên AAL2.
     */
    post: operations['createDossierMeetingMinutes'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/meetings/{id}/close': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Kết thúc hội nghị và yêu cầu ký biên bản
     * @description Chủ trì kết thúc hội nghị: khoá góp ý, tạo `dossier.remediation_item` từ các góp ý loại `HAN_CHE` (bước 9, BR-11-08), và khi biên bản đã sinh thì tạo yêu cầu ký biên bản (mục đích `BIEN_BAN`) cho chủ trì và thư ký. Phản hồi kèm MSG-11-03. Hội nghị chuyển `DA_KY` khi nhận đủ EVT-18 của hai chữ ký; EVT-11 phát cho hồ sơ liên quan theo TL-02b.
     */
    post: operations['closeDossierMeeting'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/meetings/{id}/remediation-items': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Hạn chế kỳ trước và hạn chế phát sinh của hội nghị
     * @description Vùng 3 của SCR-1101: các hạn chế của người được kiểm điểm từ biên bản các kỳ trước kèm trạng thái khắc phục, cùng hạn chế do hội nghị này tạo. Nội dung là C2 (`content_enc`). Đường dẫn là suy luận.
     */
    get: operations['listDossierMeetingRemediationItems'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/remediation-items/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    /**
     * Cập nhật trạng thái khắc phục một hạn chế
     * @description Thư ký cập nhật trạng thái khắc phục hạn chế kỳ trước (SCR-1101 bảng trường 8: "Thư ký cập nhật trạng thái"). Chuyển sang `DA_KHAC_PHUC` hoặc `CHUA_KHAC_PHUC` ghi `verified_by`, `verified_at`. Đường dẫn là suy luận vì bảng chức năng SCR-1101 không ghi API.
     */
    patch: operations['updateDossierRemediationItem'];
    trace?: never;
  };
  '/dossiers/packages': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách gói hồ sơ số
     * @description Đường vào "Hồ sơ số › Gói hồ sơ số" của SCR-1102 (đường dẫn suy luận).
     */
    get: operations['listDossierPackages'];
    put?: never;
    /**
     * Lập gói hồ sơ quý, năm hoặc diện Trung ương
     * @description Đóng gói hồ sơ cho một đơn vị và kỳ (TL-01 PL C "Đóng gói hồ sơ quý, năm"). Thông thường MOD-06 tự lập gói ở `DANG_LAP` khi nhận EVT-12; thao tác này cho cơ quan tổ chức lập thủ công khi gói chưa có (ví dụ sau mở lại kỳ, gói phiên bản mới). Mỗi đơn vị, kỳ và loại gói chỉ có một gói đang hiệu lực.
     */
    post: operations['createDossierPackage'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/packages/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết gói hồ sơ số
     * @description Phần đầu trang và vùng 1, 4, 5 của SCR-1102: mã gói, hạn nộp, tiến độ sinh tệp, thẻ chuyển hồ sơ diện TW, thẻ ký số gói (người ký, nhà cung cấp, mã băm manifest). Danh sách biểu mẫu, trường hợp đặc thù và danh sách kiểm tra đọc ở các đường dẫn riêng.
     */
    get: operations['getDossierPackage'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/packages/{id}/forms': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Danh sách biểu mẫu sinh từ dữ liệu của gói
     * @description Vùng 1 của SCR-1102: mỗi dòng là một biểu mẫu (Mẫu 1A đến 1D, 2, 3, bảng chấm điểm), đối tượng, trạng thái sinh và ký, định dạng PDF/A-2b kèm tem phiên bản. Đường dẫn là suy luận.
     */
    get: operations['listDossierPackageForms'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/packages/{id}/checklist': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Danh sách kiểm tra trước khi ký gói
     * @description Vùng 3 của SCR-1102, tính tức thời theo BR-11-09: mọi hồ sơ `DA_KY_SO`, mã băm từng tệp khớp manifest, các cá nhân đã ký bản kiểm điểm, Mẫu 3 đã ký, không còn khiếu nại mở. Nút "Ký số gói và nộp" chỉ bật khi `passed = true`. Đường dẫn là suy luận.
     */
    get: operations['getDossierPackageChecklist'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/packages/{id}/regenerate': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Sinh lại các biểu mẫu lỗi của gói
     * @description Gửi lại các render job ở `LOI`, giữ các tệp đã sinh (QT-11 Ngoại lệ). Gói chuyển `DANG_SINH`; khi EVT-17 của tệp cuối đến, gói chuyển `CHO_KY`, nếu vẫn có tệp lỗi thì quay về `DANG_LAP` và giao diện hiện MSG-11-07.
     */
    post: operations['regenerateDossierPackageForms'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/packages/{id}/export': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Tạo tệp ZIP của gói kèm manifest có chữ ký
     * @description Tạo tệp ZIP gồm các tệp PDF/A và manifest JSON (băm SHA-256 từng tệp, chữ ký gói khi đã ký). Chỉ cho gói từ `CHO_KY` trở đi. Kết quả tải ở `resultLocation` của job khi `HOAN_TAT`.
     */
    post: operations['exportDossierPackage'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/packages/{id}/transfers': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Gửi gói con hồ sơ diện Trung ương quản lý
     * @description Bước 14 của QT-11 (BR-11-11): tách gói con cho hồ sơ diện TW quản lý, mã hoá theo chứng thư bên nhận và gửi EdXML qua integration-gateway (SVC-13). Chỉ khi gói đã ký và có hồ sơ diện TW. Gửi thất bại thì SVC-13 thử lại theo lịch và thông báo MSG-11-06; gói chính vẫn nộp bình thường.
     */
    post: operations['createDossierPackageTransfer'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/packages/{id}/sign': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Ký số gói hồ sơ và nộp cấp trên
     * @description Bước 13 của QT-11. Máy chủ kiểm lại danh sách kiểm tra (BR-11-09), chốt manifest, tạo yêu cầu ký mục đích `GOI_HO_SO` ở SVC-10 cho người gọi và trả 202 kèm `signRequestId`. Giao diện theo dõi yêu cầu ký bằng `GET /esign/requests/{id}` (chờ xác nhận trên ứng dụng ký từ xa). Khi EVT-18 đến, chữ ký được gắn dấu thời gian RFC 3161, gói chuyển `DA_KY` rồi `DA_NOP`, bị khoá (BR-11-10) và ghi sổ cái; giao diện hiện MSG-11-05.
     */
    post: operations['signDossierPackage'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/render-jobs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Yêu cầu sinh biểu mẫu chính thức cho gói hồ sơ
     * @description Bước 10 của QT-11: MOD-06 gửi lệnh sinh tới SVC-11 (Mẫu 1A đến 1D, 2, 3, bảng chấm điểm, PDF/A-2b có tem phiên bản Policy Pack) từ dữ liệu đã quyết định (BR-11-02, BR-11-03). Gói chuyển `DANG_LAP` → `DANG_SINH`. Mỗi tệp xong phát EVT-17. Client nội bộ (saga chốt kỳ) cũng gọi thao tác này.
     */
    post: operations['createDossierRenderJob'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/forms': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Sinh biểu mẫu dạng nháp từ danh sách hồ sơ đang chọn
     * @description Sinh biểu mẫu nháp ngoài gói hồ sơ, ví dụ "Xuất Mẫu 3" của SCR-0904 từ danh sách hồ sơ đang chọn. Tệp mang dấu "Bản nháp", tạo `dossier.form_instance` không gắn gói (`dossier_package_id` rỗng). Trả 202; kết quả là mã biểu mẫu ở `resultLocation` của job, tải bằng `GET /dossiers/forms/{id}/file`.
     */
    post: operations['createDossierDraftForms'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/forms/preview': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xem trước biểu mẫu từ dữ liệu hiện tại
     * @description Sinh bản xem trước có dấu "Bản nháp" từ dữ liệu hiện tại, không lưu `form_instance`, trả tệp PDF ngay (một phiếu sinh dưới 2 giây theo TL-01 mục SVC-11). Dùng ở SCR-0901 "Xem trước Mẫu 1B" và SCR-1202 "Xem văn bản" trả lời khiếu nại.
     */
    post: operations['previewDossierForm'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/forms/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Thông tin một biểu mẫu đã sinh
     * @description Siêu dữ liệu của `dossier.form_instance`: mã mẫu, phiên bản mẫu, trạng thái sinh và ký, băm dữ liệu đầu vào và băm tệp. Đường dẫn là suy luận, dùng khi theo dõi job sinh nháp.
     */
    get: operations['getDossierForm'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/forms/{id}/file': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Tải tệp PDF/A của biểu mẫu
     * @description Tải tệp biểu mẫu (dự thảo hoặc chính thức, bản đã nhúng chữ ký nếu đã ký). Máy chủ ghi nhật ký truy cập. Dùng ở SCR-1101 "Tải dự thảo Mẫu 1B", SCR-1102 "Xem biểu mẫu", SCR-0903 "Tải Mẫu 1B". Header `Digest` mang SHA-256 của tệp để đối chiếu manifest (suy luận).
     */
    get: operations['downloadDossierFormFile'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/special-cases': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Trường hợp đặc thù phát hiện trong kỳ
     * @description Vùng 2 của SCR-1102: các trường hợp đặc thù của PL 6 phát sinh trong kỳ cho đơn vị, kèm cách xử lý theo Policy Pack và số hồ sơ, gom nhóm theo mục. Danh sách không trả chi tiết C2. Đường dẫn là suy luận.
     */
    get: operations['listDossierSpecialCases'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/dossiers/special-cases/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết một trường hợp đặc thù
     * @description "Xem chi tiết từng hồ sơ" của SCR-1102. Trả thêm trường `detail` (C2, ví dụ thông tin thai sản, xác minh); máy chủ ghi nhật ký truy cập. Đường dẫn là suy luận.
     */
    get: operations['getDossierSpecialCase'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/esign/requests': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Yêu cầu ký của người gọi
     * @description Danh sách yêu cầu ký mà người gọi là người ký, ví dụ biên bản chờ chủ trì và thư ký ký, bản kiểm điểm chờ cá nhân ký (QT-11 bước 11). Đường dẫn là suy luận.
     */
    get: operations['listMyEsignRequests'];
    put?: never;
    /**
     * Mở yêu cầu ký số từ xa
     * @description Tạo yêu cầu ký tại một mốc pháp lý cho chính người gọi (chủ thể ký) và đẩy sang nhà cung cấp (MySign, Viettel-CA, CA chuyên dùng Chính phủ, Ban Cơ yếu). Client chỉ gửi bản ghi nghiệp vụ nguồn; danh sách tài liệu và băm do máy chủ lấy từ module nguồn, client không bao giờ gửi băm để ký. Ký từ xa là bất đồng bộ: phản hồi 202 trả yêu cầu ở `CHO_XAC_NHAN` với `expiresAt` (thường 120 giây, MSG-04-09); giao diện theo dõi bằng `GET /esign/requests/{id}` hoặc SSE, và EVT-18 phát khi hoàn tất. Cần phiên AAL2 với `auth_time` không quá 5 phút. Hợp nhất `POST /esign/sessions` của TL-01 PL C.
     */
    post: operations['createEsignRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/esign/requests/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Trạng thái yêu cầu ký
     * @description Trạng thái yêu cầu ký và tiến độ ký lô (đã ký / tổng). Giao diện thăm dò tối đa tới `expiresAt` theo `Retry-After` (TL-01 chương 11: phiên chờ xác nhận tới 120 giây qua polling), hoặc nghe SSE. Hợp nhất `GET /esign/sessions/{id}` của TL-01 PL C.
     */
    get: operations['getEsignRequest'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/esign/certificates/mine': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Chứng thư ký số của người gọi
     * @description Chứng thư đã đăng ký của người gọi kèm hiệu lực và kết quả kiểm tra thu hồi gần nhất, để hiển thị "Người ký, nhà cung cấp" (SCR-1102 trường 6, SCR-0904 trường 7) và chặn ký khi chứng thư hết hạn. Đường dẫn là suy luận.
     */
    get: operations['listMyEsignCertificates'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    /**
     * @description Loại hội nghị (`dossier.review_meeting.meeting_type`).
     * @enum {string}
     */
    DossierMeetingType: 'KIEM_DIEM_QUY' | 'KIEM_DIEM_NAM' | 'CHUYEN_DE';
    /**
     * @description Trạng thái hội nghị (`dossier.review_meeting.status`).
     * @enum {string}
     */
    DossierMeetingStatus: 'DU_KIEN' | 'DA_GUI_DU_THAO' | 'DA_HOP' | 'DA_LAP_BIEN_BAN' | 'DA_KY';
    /**
     * @description Vai trò của một người trong hội nghị (suy luận; `participants_json` của TL-03 không quy định giá trị).
     * @enum {string}
     */
    DossierMeetingRole: 'CHU_TRI' | 'THU_KY' | 'THANH_VIEN' | 'NGUOI_DUOC_KIEM_DIEM';
    /**
     * @description Trạng thái của thành phần dự trên thẻ thành phần SCR-1101 (đã góp ý, đã đọc, chưa mở, vắng có lý do), cộng `CO_MAT` khi điểm danh (suy luận; TL không quy định mã).
     * @enum {string}
     */
    DossierParticipantStatus: 'DA_GOP_Y' | 'DA_DOC' | 'CHUA_MO' | 'CO_MAT' | 'VANG_CO_LY_DO';
    /**
     * @description Loại nội dung góp ý (`dossier.review_comment.kind`).
     * @enum {string}
     */
    DossierCommentKind: 'UU_DIEM' | 'HAN_CHE' | 'KIEN_NGHI';
    /**
     * @description Giai đoạn góp ý (`dossier.review_comment.phase`).
     * @enum {string}
     */
    DossierCommentPhase: 'TRUOC_HOI_NGHI' | 'HOI_NGHI';
    /**
     * @description Nguồn góp ý (`dossier.review_comment.comment_kind`): góp ý của thành viên, phần tự kiểm điểm, kết luận.
     * @enum {string}
     */
    DossierCommentSource: 'GOP_Y' | 'TU_KIEM_DIEM' | 'KET_LUAN';
    /**
     * @description Trạng thái khắc phục (`dossier.remediation_item.status`).
     * @enum {string}
     */
    DossierRemediationStatus: 'MO' | 'DANG_KHAC_PHUC' | 'DA_KHAC_PHUC' | 'CHUA_KHAC_PHUC';
    /**
     * @description Loại gói (`dossier.dossier_package.package_kind`).
     * @enum {string}
     */
    DossierPackageKind: 'QUY' | 'NAM' | 'DIEN_TW';
    /**
     * @description Trạng thái gói hồ sơ (`dossier.dossier_package.status`, bộ trạng thái của TL-02b QT-11).
     * @enum {string}
     */
    DossierPackageStatus: 'DANG_LAP' | 'DANG_SINH' | 'CHO_KY' | 'DA_KY' | 'DA_NOP' | 'DA_LUU_TRU';
    /**
     * @description Nơi chuyển hồ sơ diện TW (`dossier.dossier_package.transferred_to`).
     * @enum {string}
     */
    DossierTransferDestination: 'BTC_TW' | 'VPTW';
    /**
     * @description Mã biểu mẫu (`dossier.form_instance.form_code`).
     * @enum {string}
     */
    DossierFormCode:
      'MAU_1A' | 'MAU_1B' | 'MAU_1C' | 'MAU_1D' | 'MAU_2' | 'MAU_3' | 'BANG_CHAM_DIEM';
    /**
     * @description Trạng thái biểu mẫu (`dossier.form_instance.status`).
     * @enum {string}
     */
    DossierFormStatus: 'CHO_SINH' | 'DA_SINH' | 'DA_KY' | 'LOI';
    /**
     * @description Trạng thái tác vụ sinh ở SVC-11 (`docgen.render_job.status`).
     * @enum {string}
     */
    DocgenRenderStatus: 'CHO' | 'DANG_SINH' | 'XONG' | 'LOI';
    /**
     * @description Cách phát hiện (`dossier.special_case.detected_by`).
     * @enum {string}
     */
    DossierSpecialCaseDetectedBy: 'TU_DONG' | 'THU_CONG';
    /**
     * @description Hệ quả với việc xếp loại (`dossier.special_case.effect`).
     * @enum {string}
     */
    DossierSpecialCaseEffect:
      | 'KHONG_XEP_LOAI'
      | 'XEP_LOAI_TAI_DON_VI_CU'
      | 'BAO_LUU'
      | 'XEP_LOAI_BINH_THUONG'
      | 'CHO_KET_LUAN';
    /**
     * @description Mốc pháp lý của yêu cầu ký (`esign.sign_request.purpose`).
     * @enum {string}
     */
    EsignPurpose:
      | 'TU_DANH_GIA'
      | 'THAM_DINH'
      | 'QUYET_DINH'
      | 'BIEN_BAN'
      | 'GOI_HO_SO'
      | 'PHE_DUYET_MO_LAI'
      | 'PHAT_HANH_POLICY';
    /**
     * @description Nhà cung cấp chữ ký số (`esign.sign_request.provider`).
     * @enum {string}
     */
    EsignProvider: 'MYSIGN' | 'VIETTEL_CA' | 'CA_CHINH_PHU' | 'BAN_CO_YEU';
    /**
     * @description Trạng thái yêu cầu ký (`esign.sign_request.status`).
     * @enum {string}
     */
    EsignRequestStatus: 'TAO' | 'CHO_XAC_NHAN' | 'DA_KY' | 'TU_CHOI' | 'HET_HAN' | 'LOI';
    /**
     * @description Định dạng chữ ký (`esign.sign_item.signature_format`).
     * @enum {string}
     */
    EsignSignatureFormat: 'PADES_LTA' | 'CADES_T' | 'JWS';
    /**
     * @description Giá trị SHA-256 (cột `bytea` của TL-03) biểu diễn dạng 64 ký tự hex chữ thường.
     * @example 9f2c4e7a1b3d5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcd
     */
    DossierSha256: string;
    /** @description Kết quả kiểm tra thời hạn gửi dự thảo (QT-11 bước 5, BR-11-01), tính theo `config.holiday_calendar` và tham số `kiem_diem.cach_dem_ngay` của Policy Pack. */
    DossierDeadlineCheck: {
      /** @description Số ngày làm việc trọn vẹn giữa thời điểm gửi và ngày hội nghị; rỗng khi chưa gửi dự thảo. */
      workingDays?: number | null;
      /**
       * @description Số ngày tối thiểu theo Policy Pack, mặc định 3.
       * @example 3
       */
      requiredWorkingDays: number;
      /** @description Đạt yêu cầu thời hạn. */
      passed: boolean;
      /** @example QĐ Điều 6 */
      legalRef?: string;
      /** @description Mã thông báo khi không đạt, `MSG-11-01`. */
      msg?: string;
      /** @description Lý do được cấp uỷ chấp thuận để giữ nguyên ngày họp; in vào biên bản. */
      overrideReason?: string | null;
    };
    DossierMeetingParticipant: {
      person: components['schemas']['Ref'];
      role: components['schemas']['DossierMeetingRole'];
      status?: components['schemas']['DossierParticipantStatus'];
    };
    /** @description Kết quả biểu quyết theo mức xếp loại (`dossier.review_meeting.vote_json`). Tổng không vượt số người có mặt; không thay mức quyết định (BR-11-07). */
    DossierVote: {
      HTXSNV?: number;
      HTTNV?: number;
      HTNV?: number;
      KHTNV?: number;
    };
    /** @description Biểu mẫu rút gọn gắn với hội nghị (dự thảo Mẫu 1A đến 1D, biên bản). */
    DossierFormSummary: {
      id: components['schemas']['Uuid'];
      formCode: components['schemas']['DossierFormCode'];
      status: components['schemas']['DossierFormStatus'];
    };
    /** @description Hội nghị rút gọn cho danh sách. */
    DossierMeetingSummary: {
      id: components['schemas']['Uuid'];
      /** @description Tên hiển thị do máy chủ ghép từ loại, kỳ và tập thể (suy luận). */
      title?: string;
      meetingType: components['schemas']['DossierMeetingType'];
      orgUnit: components['schemas']['Ref'];
      period: components['schemas']['PeriodRef'];
      scheduledAt: components['schemas']['DateTime'];
      status: components['schemas']['DossierMeetingStatus'];
      /** @description Vai trò của người gọi trong hội nghị; rỗng khi người gọi chỉ xem theo quyền đơn vị. */
      myRole?: components['schemas']['DossierMeetingRole'];
    };
    /**
     * @description Hội nghị kiểm điểm (`dossier.review_meeting`) như SCR-1101 hiển thị.
     * @example {
     *       "id": "0192a3f4-1101-7000-8000-000000000001",
     *       "title": "Hội nghị kiểm điểm cuối năm 2026 của Chi bộ Ban Tổ chức",
     *       "period": {
     *         "id": "0192a3f4-0002-7000-8000-000000002026",
     *         "label": "Năm 2026",
     *         "startDate": "2026-01-01",
     *         "endDate": "2026-12-31"
     *       },
     *       "orgUnit": {
     *         "id": "0192a3f4-0001-7000-8000-000000000010",
     *         "name": "Chi bộ Ban Tổ chức"
     *       },
     *       "meetingType": "KIEM_DIEM_NAM",
     *       "scheduledAt": "2027-01-14T08:00:00+07:00",
     *       "location": "Phòng họp tầng 3, trụ sở Ban Tổ chức",
     *       "subjectPerson": {
     *         "id": "0192a3f4-0003-7000-8000-000000000103",
     *         "name": "Trần Thị Mai"
     *       },
     *       "chair": {
     *         "id": "0192a3f4-0003-7000-8000-000000000101",
     *         "name": "Nguyễn Văn Hùng"
     *       },
     *       "secretary": {
     *         "id": "0192a3f4-0003-7000-8000-000000000102",
     *         "name": "Lê Thu Hà"
     *       },
     *       "status": "DA_GUI_DU_THAO",
     *       "myRole": "THANH_VIEN",
     *       "draftSentAt": "2027-01-08T16:20:00+07:00",
     *       "deadlineCheck": {
     *         "workingDays": 3,
     *         "requiredWorkingDays": 3,
     *         "passed": true,
     *         "legalRef": "QĐ Điều 6"
     *       },
     *       "participants": [
     *         {
     *           "person": {
     *             "id": "0192a3f4-0003-7000-8000-000000000104",
     *             "name": "Phạm Quốc Bảo"
     *           },
     *           "role": "THANH_VIEN",
     *           "status": "DA_GOP_Y"
     *         },
     *         {
     *           "person": {
     *             "id": "0192a3f4-0003-7000-8000-000000000105",
     *             "name": "Đỗ Minh Châu"
     *           },
     *           "role": "THANH_VIEN",
     *           "status": "VANG_CO_LY_DO"
     *         }
     *       ],
     *       "draftForms": [
     *         {
     *           "id": "0192a3f4-1103-7000-8000-000000000001",
     *           "formCode": "MAU_1B",
     *           "status": "DA_SINH"
     *         }
     *       ],
     *       "proposedLevel": "HTTNV",
     *       "vote": null,
     *       "commentsLocked": false,
     *       "rowVersion": 4
     *     }
     */
    DossierMeeting: components['schemas']['DossierMeetingSummary'] & {
      /** @description Địa điểm (SCR-1101 trường 2; TL-03 chưa có cột, suy luận). */
      location?: string;
      /**
       * Format: date-time
       * @description Thời điểm họp thực tế (`meeting_at`).
       */
      meetingAt?: string | null;
      /** @description Người được kiểm điểm khi hội nghị dành cho một người (`subject_person_id`). */
      subjectPerson?: components['schemas']['Ref'] | null;
      chair: components['schemas']['Ref'];
      secretary: components['schemas']['Ref'];
      /**
       * Format: date-time
       * @description Thời điểm gửi dự thảo (`draft_sent_at`).
       */
      draftSentAt?: string | null;
      deadlineCheck: components['schemas']['DossierDeadlineCheck'];
      participants?: components['schemas']['DossierMeetingParticipant'][];
      /** @description Dự thảo biểu mẫu của người được kiểm điểm. */
      draftForms?: components['schemas']['DossierFormSummary'][];
      /** @description Mức đề xuất của hệ thống, hiển thị cạnh biểu quyết (vùng 4). */
      proposedLevel?: components['schemas']['RatingLevel'];
      vote?: components['schemas']['DossierVote'] | null;
      minutesSha256?: components['schemas']['DossierSha256'] | null;
      /** @description Yêu cầu ký biên bản (`sign_request_id`). */
      signRequestId?: components['schemas']['Uuid'] | null;
      /** @description Góp ý đã khoá sau khi chủ trì kết thúc hội nghị. */
      commentsLocked?: boolean;
      rowVersion: components['schemas']['RowVersion'];
    };
    DossierMeetingPage: {
      items: components['schemas']['DossierMeetingSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    DossierMeetingCreate: {
      periodId: components['schemas']['Uuid'];
      orgUnitId: components['schemas']['Uuid'];
      meetingType: components['schemas']['DossierMeetingType'];
      scheduledAt: components['schemas']['DateTime'];
      location?: string;
      chairPersonId: components['schemas']['Uuid'];
      secretaryPersonId: components['schemas']['Uuid'];
      /** @description Bỏ trống khi hội nghị kiểm điểm tập thể. */
      subjectPersonId?: components['schemas']['Uuid'];
      participantPersonIds?: components['schemas']['Uuid'][];
    };
    /** @description Các trường sửa được trước khi họp; trường không gửi giữ nguyên (JSON Merge Patch). */
    DossierMeetingUpdate: {
      scheduledAt?: components['schemas']['DateTime'];
      location?: string;
      participantPersonIds?: components['schemas']['Uuid'][];
      /** @description Lý do giữ ngày họp khi thời hạn không đạt (chỉ chủ trì ghi). */
      deadlineOverrideReason?: string;
    };
    DossierAttendance: {
      personId: components['schemas']['Uuid'];
      /**
       * @description Điểm danh (suy luận, cùng tập giá trị với `DossierParticipantStatus`).
       * @enum {string}
       */
      attendance: 'CO_MAT' | 'VANG_CO_LY_DO';
    };
    DossierMinutesRequest: {
      meetingAt?: components['schemas']['DateTime'];
      attendance: components['schemas']['DossierAttendance'][];
      vote: components['schemas']['DossierVote'];
    };
    /**
     * @description Góp ý kiểm điểm (`dossier.review_comment`). Nội dung là C2.
     * @example {
     *       "id": "0192a3f4-1104-7000-8000-000000000001",
     *       "meetingId": "0192a3f4-1101-7000-8000-000000000001",
     *       "subjectPersonId": "0192a3f4-0003-7000-8000-000000000103",
     *       "commenter": {
     *         "id": "0192a3f4-0003-7000-8000-000000000104",
     *         "name": "Phạm Quốc Bảo"
     *       },
     *       "kind": "HAN_CHE",
     *       "phase": "TRUOC_HOI_NGHI",
     *       "content": "Việc tham mưu rà soát quy hoạch cán bộ quý III còn chậm so với kế hoạch khoảng hai tuần.",
     *       "createdAt": "2027-01-10T09:15:00+07:00"
     *     }
     */
    DossierComment: {
      id: components['schemas']['Uuid'];
      meetingId: components['schemas']['Uuid'];
      subjectPersonId: components['schemas']['Uuid'];
      /** @description Người góp ý; `null` khi tenant bật ẩn danh và người xem là người được kiểm điểm (BR-11-06). */
      commenter?: components['schemas']['Ref'] | null;
      source?: components['schemas']['DossierCommentSource'];
      kind: components['schemas']['DossierCommentKind'];
      phase: components['schemas']['DossierCommentPhase'];
      /** @description Nội dung góp ý (giải mã từ `comment_enc`). */
      content: string;
      createdAt: components['schemas']['DateTime'];
    };
    DossierCommentPage: {
      /** @description Tenant bật chế độ ẩn danh người góp ý. */
      anonymous?: boolean;
      items: components['schemas']['DossierComment'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    DossierCommentCreate: {
      subjectPersonId: components['schemas']['Uuid'];
      kind: components['schemas']['DossierCommentKind'];
      phase: components['schemas']['DossierCommentPhase'];
      content: string;
    };
    /**
     * @description Hạn chế cần khắc phục (`dossier.remediation_item`).
     * @example {
     *       "id": "0192a3f4-1105-7000-8000-000000000001",
     *       "person": {
     *         "id": "0192a3f4-0003-7000-8000-000000000103",
     *         "name": "Trần Thị Mai"
     *       },
     *       "meetingId": "0192a3f4-1101-7000-8000-000000000000",
     *       "sourcePeriod": {
     *         "id": "0192a3f4-0002-7000-8000-000000002025",
     *         "label": "Năm 2025",
     *         "startDate": "2025-01-01",
     *         "endDate": "2025-12-31"
     *       },
     *       "content": "Nâng cao chất lượng thẩm định hồ sơ nhân sự, giảm số hồ sơ phải trả lại.",
     *       "status": "DANG_KHAC_PHUC",
     *       "rowVersion": 2
     *     }
     */
    DossierRemediationItem: {
      id: components['schemas']['Uuid'];
      person: components['schemas']['Ref'];
      meetingId: components['schemas']['Uuid'];
      reviewCommentId?: components['schemas']['Uuid'] | null;
      sourcePeriod?: components['schemas']['PeriodRef'];
      duePeriodId?: components['schemas']['Uuid'] | null;
      /** @description Nội dung hạn chế và yêu cầu khắc phục (giải mã từ `content_enc`). */
      content: string;
      status: components['schemas']['DossierRemediationStatus'];
      verifiedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      verifiedAt?: string | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    DossierRemediationItemPage: {
      items: components['schemas']['DossierRemediationItem'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    DossierRemediationItemUpdate: {
      status: components['schemas']['DossierRemediationStatus'];
      /** @description Ghi chú xác nhận (suy luận). */
      note?: string;
    };
    /** @description Tiến độ sinh và ký tệp của gói, ví dụ "104/106 tệp xong". */
    DossierPackageFormCounts: {
      total: number;
      rendered: number;
      failed: number;
      signed?: number;
    };
    /** @description Thẻ chuyển hồ sơ diện TW (SCR-1102 vùng 4). */
    DossierCentralTransfer: {
      profileCount: number;
      destination?: components['schemas']['DossierTransferDestination'] | null;
      /**
       * @description Trạng thái gửi gói con (suy luận; trạng thái chi tiết nằm ở `integ` của SVC-13).
       * @enum {string}
       */
      transferStatus: 'CHUA_GUI' | 'DANG_GUI' | 'DA_GUI' | 'LOI';
    };
    /**
     * @description Gói hồ sơ số (`dossier.dossier_package`). Hợp đồng theo TL-02b: một gói cho mỗi đơn vị, kỳ và loại gói; `person` chỉ có khi gói thuộc một cá nhân (TL-03 khai `person_id`).
     * @example {
     *       "id": "0192a3f4-1102-7000-8000-000000000142",
     *       "code": "DP-2026-N-0142",
     *       "packageKind": "NAM",
     *       "period": {
     *         "id": "0192a3f4-0002-7000-8000-000000002026",
     *         "label": "Năm 2026",
     *         "startDate": "2026-01-01",
     *         "endDate": "2026-12-31"
     *       },
     *       "orgUnit": {
     *         "id": "0192a3f4-0001-7000-8000-000000000010",
     *         "name": "Ban Tổ chức Tỉnh uỷ"
     *       },
     *       "status": "CHO_KY",
     *       "deadline": "2027-02-01",
     *       "versionNo": 1,
     *       "formCounts": {
     *         "total": 106,
     *         "rendered": 106,
     *         "failed": 0,
     *         "signed": 104
     *       },
     *       "profileCount": 48,
     *       "manifestHash": "9f2c4e7a1b3d5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcd",
     *       "central": {
     *         "profileCount": 2,
     *         "destination": "BTC_TW",
     *         "transferStatus": "CHUA_GUI"
     *       },
     *       "rowVersion": 12
     *     }
     */
    DossierPackage: {
      id: components['schemas']['Uuid'];
      /** @example DP-2026-N-0142 */
      code: string;
      packageKind: components['schemas']['DossierPackageKind'];
      period: components['schemas']['PeriodRef'];
      orgUnit?: components['schemas']['Ref'];
      person?: components['schemas']['Ref'] | null;
      status: components['schemas']['DossierPackageStatus'];
      /**
       * Format: date
       * @description Hạn nộp cấp trên (`deadline`).
       */
      deadline?: string | null;
      /** Format: date-time */
      submittedAt?: string | null;
      /** @description Phiên bản gói, tăng khi lập lại sau mở lại kỳ (suy luận). */
      versionNo?: number;
      formCounts: components['schemas']['DossierPackageFormCounts'];
      profileCount?: number;
      manifestHash?: components['schemas']['DossierSha256'] | null;
      packageSha256?: components['schemas']['DossierSha256'] | null;
      central?: components['schemas']['DossierCentralTransfer'];
      transferredTo?: components['schemas']['DossierTransferDestination'] | null;
      signRequestId?: components['schemas']['Uuid'] | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    DossierPackagePage: {
      items: components['schemas']['DossierPackage'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    DossierPackageCreate: {
      periodId: components['schemas']['Uuid'];
      orgUnitId: components['schemas']['Uuid'];
      packageKind: components['schemas']['DossierPackageKind'];
      deadline?: components['schemas']['Date'];
    };
    DossierChecklistItem: {
      /** @description Mã mục kiểm tra (suy luận), ví dụ `HO_SO_DA_KY_SO`, `MA_BAM_KHOP`, `CA_NHAN_DA_KY`, `MAU_3_DA_KY`, `KHONG_KHIEU_NAI_MO`. */
      code: string;
      label: string;
      /**
       * @description Đạt hoặc cảnh báo (SCR-1102 trường 4; mã suy luận).
       * @enum {string}
       */
      status: 'DAT' | 'CANH_BAO';
      /** @description Số mục chưa đạt nếu có. */
      count?: number;
    };
    /** @description Danh sách kiểm tra trước khi ký gói (BR-11-09). */
    DossierPackageChecklist: {
      passed: boolean;
      evaluatedAt?: components['schemas']['DateTime'];
      items: components['schemas']['DossierChecklistItem'][];
    };
    DossierRegenerateRequest: {
      /** @description Chỉ sinh lại các biểu mẫu lỗi này; bỏ trống là mọi biểu mẫu `LOI` của gói. */
      formIds?: components['schemas']['Uuid'][];
    };
    DossierTransferRequest: {
      destination: components['schemas']['DossierTransferDestination'];
      /** @description Hồ sơ diện TW cần gửi; bỏ trống là mọi hồ sơ diện TW của gói. */
      profileIds?: components['schemas']['Uuid'][];
    };
    DossierPackageSignRequest: {
      provider?: components['schemas']['EsignProvider'];
      /** @description Chứng thư chọn để ký (`esign.certificate_ref.id`); bỏ trống dùng chứng thư mặc định của người ký. */
      certificateId?: components['schemas']['Uuid'];
    };
    /** @description Job ký gói kèm mã yêu cầu ký ở SVC-10. */
    DossierSignJob: components['schemas']['Job'] & {
      signRequestId: components['schemas']['Uuid'];
    };
    /**
     * @description Biểu mẫu đã hoặc đang sinh (`dossier.form_instance` kết hợp `docgen.render_job`).
     * @example {
     *       "id": "0192a3f4-1103-7000-8000-000000000001",
     *       "formCode": "MAU_1B",
     *       "packageId": "0192a3f4-1102-7000-8000-000000000142",
     *       "profileId": "0192a3f4-0901-7000-8000-000000000201",
     *       "subjectPerson": {
     *         "id": "0192a3f4-0003-7000-8000-000000000103",
     *         "name": "Trần Thị Mai"
     *       },
     *       "templateVersion": "v2.0",
     *       "status": "DA_SINH",
     *       "renderStatus": "XONG",
     *       "format": "PDF/A-2b",
     *       "dataSnapshotHash": "4a5b6c7d8e9f00112233445566778899aabbccddeeff00112233445566778899",
     *       "pdfSha256": "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
     *     }
     */
    DossierForm: {
      id: components['schemas']['Uuid'];
      formCode: components['schemas']['DossierFormCode'];
      packageId?: components['schemas']['Uuid'] | null;
      profileId: components['schemas']['Uuid'];
      subjectPerson?: components['schemas']['Ref'];
      /** @description Phiên bản mẫu, in thành tem chân trang (BR-11-03). */
      templateVersion: string;
      status: components['schemas']['DossierFormStatus'];
      renderStatus?: components['schemas']['DocgenRenderStatus'];
      /** @description Lỗi sinh (`docgen.render_job.error`), dùng cho MSG-11-07. */
      renderError?: string | null;
      /**
       * @description Định dạng tệp và chuẩn PDF/A.
       * @example PDF/A-2b
       */
      format?: string;
      /** @description Bản nháp có dấu "Bản nháp" (suy luận). */
      draft?: boolean;
      dataSnapshotHash?: components['schemas']['DossierSha256'];
      pdfSha256?: components['schemas']['DossierSha256'] | null;
      /** Format: date-time */
      signedAt?: string | null;
    };
    DossierFormPage: {
      items: components['schemas']['DossierForm'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    DossierRenderJobRequest: {
      packageId: components['schemas']['Uuid'];
      /** @description Chỉ sinh các mẫu này; bỏ trống là đủ danh mục theo Policy Pack. */
      formCodes?: components['schemas']['DossierFormCode'][];
    };
    DossierDraftFormsRequest: {
      formCode: components['schemas']['DossierFormCode'];
      periodId: components['schemas']['Uuid'];
      profileIds: components['schemas']['Uuid'][];
    };
    DossierFormPreviewRequest: {
      /** @description Mã biểu mẫu hoặc mã mẫu văn bản ở `docgen.template`, ví dụ `MAU_1B`; văn bản trả lời khiếu nại dùng mã mẫu của Policy Pack (suy luận). */
      formCode: string;
      /**
       * @description Module sở hữu bản ghi nguồn.
       * @enum {string}
       */
      sourceModule: 'appraisal' | 'oversight' | 'dossier';
      sourceRefId: components['schemas']['Uuid'];
    };
    /**
     * @description Trường hợp đặc thù PL 6 (`dossier.special_case`).
     * @example {
     *       "id": "0192a3f4-1106-7000-8000-000000000001",
     *       "person": {
     *         "id": "0192a3f4-0003-7000-8000-000000000107",
     *         "name": "Vũ Hoàng Long"
     *       },
     *       "caseCode": "DIEU_DONG_DUOI_2_THANG",
     *       "legalRef": "PL 6 §1",
     *       "handling": "Đơn vị cũ đánh giá, đơn vị mới cho ý kiến",
     *       "detectedBy": "TU_DONG",
     *       "effect": "XEP_LOAI_TAI_DON_VI_CU",
     *       "effectiveFrom": "2026-11-15",
     *       "effectiveTo": "2026-12-31"
     *     }
     */
    DossierSpecialCase: {
      id: components['schemas']['Uuid'];
      person: components['schemas']['Ref'];
      /** @description Mã trường hợp theo danh mục 13 trường hợp của Policy Pack, ví dụ `DIEU_DONG_DUOI_2_THANG`. */
      caseCode: string;
      /** @example PL 6 §1 */
      legalRef: string;
      /** @description Cách xử lý theo Policy Pack. */
      handling?: string;
      detectedBy: components['schemas']['DossierSpecialCaseDetectedBy'];
      effect: components['schemas']['DossierSpecialCaseEffect'];
      /** Format: date */
      effectiveFrom?: string | null;
      /** Format: date */
      effectiveTo?: string | null;
      /** @description Chi tiết (giải mã từ `detail_enc`); chỉ trả ở đọc một đối tượng. */
      detail?: string | null;
      confirmedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      confirmedAt?: string | null;
    };
    DossierSpecialCaseGroup: {
      legalRef: string;
      caseCode: string;
      handling?: string;
      count: number;
    };
    DossierSpecialCasePage: {
      /** @description Gom nhóm theo mục PL 6 kèm số hồ sơ. */
      groups?: components['schemas']['DossierSpecialCaseGroup'][];
      items: components['schemas']['DossierSpecialCase'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Chứng thư đã dùng hoặc đã đăng ký (`esign.certificate_ref`); chỉ phần công khai. */
    EsignCertificate: {
      id: components['schemas']['Uuid'];
      certSha256?: components['schemas']['DossierSha256'];
      subjectDn: string;
      issuerDn: string;
      serialNo: string;
      notBefore: components['schemas']['DateTime'];
      notAfter: components['schemas']['DateTime'];
      /** @description Kết quả OCSP gần nhất (`last_ocsp_status`), ví dụ GOOD, REVOKED, UNKNOWN. */
      lastOcspStatus?: string | null;
      /** Format: date-time */
      lastOcspAt?: string | null;
    };
    /**
     * @description Yêu cầu ký (`esign.sign_request`) kèm tiến độ các tài liệu (`esign.sign_item`).
     * @example {
     *       "id": "0192a3f4-1810-7000-8000-000000000002",
     *       "purpose": "BIEN_BAN",
     *       "sourceModule": "dossier",
     *       "sourceRefId": "0192a3f4-1101-7000-8000-000000000001",
     *       "provider": "MYSIGN",
     *       "isBatch": false,
     *       "status": "CHO_XAC_NHAN",
     *       "expiresAt": "2027-01-14T11:02:00+07:00",
     *       "itemCount": 1,
     *       "signedCount": 0
     *     }
     */
    EsignRequest: {
      id: components['schemas']['Uuid'];
      purpose: components['schemas']['EsignPurpose'];
      /**
       * @description Module của bản ghi yêu cầu ký.
       * @example dossier
       */
      sourceModule: string;
      sourceRefId: components['schemas']['Uuid'];
      provider: components['schemas']['EsignProvider'];
      isBatch: boolean;
      status: components['schemas']['EsignRequestStatus'];
      expiresAt: components['schemas']['DateTime'];
      /** Format: date-time */
      completedAt?: string | null;
      /** @description Tổng số tài liệu. */
      itemCount?: number;
      /** @description Số tài liệu đã ký (tiến độ ký lô). */
      signedCount?: number;
      signatureFormat?: components['schemas']['EsignSignatureFormat'];
      certificate?: components['schemas']['EsignCertificate'] | null;
      problem?: components['schemas']['ProblemDetails'];
    };
    EsignRequestPage: {
      items: components['schemas']['EsignRequest'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    EsignRequestCreate: {
      purpose: components['schemas']['EsignPurpose'];
      /**
       * @description Module sở hữu bản ghi cần ký (giá trị suy luận theo tên module baseline; TL-03 để `source_module` tự do).
       * @enum {string}
       */
      sourceModule: 'task' | 'appraisal' | 'dossier' | 'oversight' | 'policy';
      sourceRefId: components['schemas']['Uuid'];
      provider?: components['schemas']['EsignProvider'];
      /** @description Chứng thư chọn để ký; bỏ trống dùng chứng thư mặc định. */
      certificateId?: components['schemas']['Uuid'];
    };
    /**
     * Format: uuid
     * @description Mã định danh UUIDv7 (cột `id` của mọi bảng, baseline mục 9).
     * @example 01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77
     */
    Uuid: string;
    FieldError: {
      /** @description Tên trường theo JSON của thân yêu cầu, dạng đường dẫn chấm */
      field: string;
      /** @description Thông điệp tiếng Việt */
      message: string;
      /** @description Mã quy tắc nghiệp vụ nếu có, ví dụ `BR-05-01` */
      rule?: string;
    };
    /** @description RFC 9457, kiểu nội dung `application/problem+json`. Mở rộng theo TL-01: `code` dạng `<MODULE>-<HTTP>-<TÊN>`, `errors[]` cho lỗi theo trường, `traceId` để tra cứu. `detail` không chứa thông tin nội bộ. */
    ProblemDetails: {
      /** @description URI ổn định của loại lỗi, ví dụ `https://okrkpi.vn/problems/period-locked` */
      type: string;
      /** @description Tiêu đề tiếng Việt */
      title: string;
      status: number;
      detail?: string;
      instance?: string;
      /** @description Mã lỗi, ví dụ `TASK-409-PERIOD-LOCKED`; ánh xạ sang MSG ở giao diện */
      code?: string;
      /** @description Mã thông điệp TL-02 tương ứng nếu có, ví dụ `MSG-06-05` */
      msg?: string;
      errors?: components['schemas']['FieldError'][];
      traceId?: string;
    };
    /** @description Tham chiếu rút gọn tới một đối tượng kèm tên hiển thị. */
    Ref: {
      id: components['schemas']['Uuid'];
      name: string;
    };
    /**
     * Format: date
     * @description Ngày theo lịch (ISO 8601), không có giờ.
     * @example 2026-12-15
     */
    Date: string;
    /** @description Kỳ đánh giá rút gọn (`config.period`). */
    PeriodRef: {
      id: components['schemas']['Uuid'];
      /** @example Quý IV/2026 */
      label: string;
      startDate: components['schemas']['Date'];
      endDate: components['schemas']['Date'];
    };
    /**
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
    /**
     * @description Mức xếp loại (baseline mục 9.1).
     * @enum {string}
     */
    RatingLevel: 'HTXSNV' | 'HTTNV' | 'HTNV' | 'KHTNV';
    /**
     * @description Giá trị `row_version` (khoá lạc quan). Cùng giá trị với ETag, gửi lại trong `If-Match`.
     * @example 7
     */
    RowVersion: number;
    /**
     * @description Trạng thái tác vụ dài. TL chưa đặc tả lược đồ job; hợp đồng giữ bộ giá trị WEB-007 đã dùng (lấy từ các trạng thái job trong TL-03).
     * @enum {string}
     */
    JobStatus: 'CHO' | 'DANG_CHAY' | 'HOAN_TAT' | 'THAT_BAI' | 'DA_HUY';
    /** @description Tác vụ dài trả qua `GET /api/v1/jobs/{id}`. */
    Job: {
      id: string;
      /** @description Loại tác vụ, ví dụ PERIOD_LOCK, ESIGN_BATCH, EXPORT, IMPORT, RENDER, TEST_RUN, BULK_ASSIGN */
      type: string;
      status: components['schemas']['JobStatus'];
      progress?: number;
      /** @description Mô tả bước đang chạy, tiếng Việt */
      message?: string;
      /** @description Đường dẫn kết quả khi HOAN_TAT */
      resultLocation?: string;
      problem?: components['schemas']['ProblemDetails'];
      updatedAt?: components['schemas']['DateTime'];
    };
  };
  responses: {
    /** @description Đã nhận yêu cầu ký gói (trả trong 200 ms). Theo dõi job bằng `Location` và yêu cầu ký bằng `GET /esign/requests/{signRequestId}`. */
    DossierSignAccepted: {
      headers: {
        Location: components['headers']['Location'];
        'Retry-After': components['headers']['RetryAfter'];
        [name: string]: unknown;
      };
      content: {
        'application/json': components['schemas']['DossierSignJob'];
      };
    };
    /** @description Lỗi khác theo RFC 9457. */
    Problem: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Chưa xác thực, token hết hạn, hoặc thao tác cần xác thực bước lên AAL2 (RFC 9470); khi đó `WWW-Authenticate` mang `error="insufficient_user_authentication", acr_values="aal2"`. */
    Unauthorized: {
      headers: {
        'WWW-Authenticate'?: string;
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description OPA từ chối theo RBAC/ABAC hoặc quy tắc tách vai trò (Phụ lục 4, mục V.1). */
    Forbidden: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Không tìm thấy, hoặc đối tượng nằm ngoài phạm vi người gọi (không lộ sự tồn tại). */
    NotFound: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Dữ liệu không hợp lệ theo quy tắc nghiệp vụ (BR), thiếu lý do bắt buộc, hoặc `Idempotency-Key` dùng lại với nội dung khác. Lỗi theo trường nằm trong `errors[]`. */
    UnprocessableEntity: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Thiếu header `If-Match` cho thao tác cập nhật (RFC 6585). */
    PreconditionRequired: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Tác vụ nền đã nhận (trả trong 200 ms, NFR-03). Theo dõi bằng `Location` hoặc luồng SSE `/api/v1/notifications/stream`. */
    Accepted: {
      headers: {
        Location: components['headers']['Location'];
        'Retry-After': components['headers']['RetryAfter'];
        [name: string]: unknown;
      };
      content: {
        'application/json': components['schemas']['Job'];
      };
    };
    /** @description Xung đột. Hai nghĩa phân biệt bằng `code`: xung đột ghi đồng thời (`<MODULE>-409-VERSION-CONFLICT`, `type` kết thúc bằng `concurrent-modification`) hoặc vi phạm trạng thái nghiệp vụ (ví dụ `TASK-409-PERIOD-LOCKED`). Với xung đột ghi, giao diện giữ dữ liệu đang nhập và hiện khác biệt. */
    Conflict: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Cắt tải khi cell gần bão hoà; thử lại có giãn cách. */
    ServiceUnavailable: {
      headers: {
        'Retry-After': components['headers']['RetryAfter'];
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
  };
  parameters: {
    /** @description Mã kỳ đánh giá (`config.period.id`). */
    PeriodId: components['schemas']['Uuid'];
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
    IdPath: components['schemas']['Uuid'];
    /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
    IfMatch: string;
  };
  requestBodies: never;
  headers: {
    /** @description Phiên bản hiện tại của đối tượng (`row_version`), gửi lại trong `If-Match`. */
    ETag: string;
    /** @description Đường dẫn theo dõi tác vụ dài `/api/v1/jobs/{id}` hoặc đối tượng vừa tạo. */
    Location: string;
    /** @description Số giây nên chờ trước lần gọi kế tiếp. */
    RetryAfter: number;
  };
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  listDossierMeetings: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Lọc theo trạng thái hội nghị. */
        status?: components['schemas']['DossierMeetingStatus'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang hội nghị. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierMeetingPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createDossierMeeting: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierMeetingCreate'];
      };
    };
    responses: {
      /** @description Đã tạo hội nghị. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierMeeting'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Kỳ đã khoá với thao tác lập kế hoạch hoặc đã có hội nghị cùng loại cho đối tượng trong kỳ (`DOSSIER-409-PERIOD-LOCKED`, `DOSSIER-409-MEETING-EXISTS`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Dữ liệu không hợp lệ, ví dụ chủ trì trùng thư ký hoặc người ngoài đơn vị (`DOSSIER-422-VALIDATION`). */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  getDossierMeeting: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Hội nghị. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierMeeting'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateDossierMeeting: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierMeetingUpdate'];
      };
    };
    responses: {
      /** @description Hội nghị sau khi sửa; thời hạn được tính lại. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierMeeting'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột phiên bản (`DOSSIER-409-VERSION-CONFLICT`) hoặc hội nghị đã họp (`DOSSIER-409-MEETING-HELD`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  sendDossierMeetingDraft: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đã gửi dự thảo; kèm kết quả kiểm tra thời hạn. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierMeeting'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải người được kiểm điểm của hội nghị (`DOSSIER-403-NOT-SUBJECT`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Xung đột phiên bản (`DOSSIER-409-VERSION-CONFLICT`) hoặc hội nghị không còn ở `DU_KIEN` (`DOSSIER-409-INVALID-STATE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listDossierMeetingComments: {
    parameters: {
      query?: {
        /** @description Lọc theo giai đoạn góp ý. */
        phase?: components['schemas']['DossierCommentPhase'];
        /** @description Lọc theo loại nội dung. */
        kind?: components['schemas']['DossierCommentKind'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang góp ý. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierCommentPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createDossierMeetingComment: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierCommentCreate'];
      };
    };
    responses: {
      /** @description Đã lưu góp ý. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierComment'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải thành phần hội nghị, hoặc ghi `phase = HOI_NGHI` mà không phải thư ký (`DOSSIER-403-NOT-PARTICIPANT`, `DOSSIER-403-NOT-SECRETARY`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Hội nghị đã kết thúc, góp ý đã khoá (`DOSSIER-409-COMMENTS-LOCKED`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  createDossierMeetingMinutes: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierMinutesRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải thư ký hội nghị (`DOSSIER-403-NOT-SECRETARY`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Xung đột phiên bản (`DOSSIER-409-VERSION-CONFLICT`) hoặc hội nghị chưa gửi dự thảo hay đã kết thúc (`DOSSIER-409-INVALID-STATE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Tổng số phiếu biểu quyết vượt số người có mặt (`DOSSIER-422-VOTE-EXCEEDS-ATTENDEES`). */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  closeDossierMeeting: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đã kết thúc hội nghị. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierMeeting'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải chủ trì hội nghị (`DOSSIER-403-NOT-CHAIR`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Xung đột phiên bản (`DOSSIER-409-VERSION-CONFLICT`) hoặc chưa có biểu quyết (`DOSSIER-409-NO-VOTE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listDossierMeetingRemediationItems: {
    parameters: {
      query?: {
        /** @description `KY_TRUOC` chỉ lấy hạn chế từ kỳ trước, `HOI_NGHI_NAY` chỉ lấy hạn chế do hội nghị này tạo (suy luận). */
        origin?: 'KY_TRUOC' | 'HOI_NGHI_NAY';
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang hạn chế. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierRemediationItemPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateDossierRemediationItem: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierRemediationItemUpdate'];
      };
    };
    responses: {
      /** @description Hạn chế sau khi cập nhật. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierRemediationItem'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listDossierPackages: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Loại gói. */
        kind?: components['schemas']['DossierPackageKind'];
        /** @description Trạng thái gói. */
        status?: components['schemas']['DossierPackageStatus'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang gói hồ sơ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierPackagePage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createDossierPackage: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierPackageCreate'];
      };
    };
    responses: {
      /** @description Đã lập gói ở `DANG_LAP`. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierPackage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Đã có gói hiệu lực cho đơn vị, kỳ và loại gói (`DOSSIER-409-PACKAGE-EXISTS`), hoặc kỳ chưa có quyết định xếp loại (`DOSSIER-409-PERIOD-NOT-DECIDED`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getDossierPackage: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Gói hồ sơ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierPackage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listDossierPackageForms: {
    parameters: {
      query?: {
        /** @description Lọc theo trạng thái biểu mẫu, ví dụ `LOI` để xem tệp sinh lỗi. */
        status?: components['schemas']['DossierFormStatus'];
        /** @description Lọc theo mã mẫu. */
        formCode?: components['schemas']['DossierFormCode'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang biểu mẫu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierFormPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getDossierPackageChecklist: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Kết quả danh sách kiểm tra. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierPackageChecklist'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  regenerateDossierPackageForms: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['DossierRegenerateRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột phiên bản (`DOSSIER-409-VERSION-CONFLICT`), không có tệp lỗi (`DOSSIER-409-NO-FAILED-FORMS`) hoặc gói đã ký, đã nộp bị khoá (`DOSSIER-409-PACKAGE-LOCKED`, BR-11-10). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  exportDossierPackage: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Gói chưa tới `CHO_KY` (`DOSSIER-409-PACKAGE-NOT-READY`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  createDossierPackageTransfer: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierTransferRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Gói chưa ký (`DOSSIER-409-PACKAGE-NOT-SIGNED`) hoặc không có hồ sơ diện TW (`DOSSIER-409-NO-CENTRAL-PROFILES`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Nơi nhận chưa được cấu hình kết nối ở `integ.connector` (`DOSSIER-422-DESTINATION-NOT-CONFIGURED`). */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  signDossierPackage: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['DossierPackageSignRequest'];
      };
    };
    responses: {
      202: components['responses']['DossierSignAccepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Danh sách kiểm tra chưa đạt (`DOSSIER-409-CHECKLIST-NOT-PASSED`, `msg` MSG-11-04), gói không ở `CHO_KY` (`DOSSIER-409-INVALID-STATE`), gói đã nộp bị khoá (`DOSSIER-409-PACKAGE-LOCKED`) hoặc xung đột phiên bản (`DOSSIER-409-VERSION-CONFLICT`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Chứng thư của người ký hết hạn hoặc bị thu hồi (`ESIGN-422-CERTIFICATE-INVALID`). */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  createDossierRenderJob: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierRenderJobRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Gói không ở `DANG_LAP` (`DOSSIER-409-INVALID-STATE`) hoặc còn hồ sơ chưa có quyết định và không thuộc trường hợp đặc thù (`DOSSIER-409-PROFILES-NOT-DECIDED`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  createDossierDraftForms: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierDraftFormsRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  previewDossierForm: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DossierFormPreviewRequest'];
      };
    };
    responses: {
      /** @description Tệp PDF xem trước. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/pdf': string;
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getDossierForm: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Biểu mẫu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierForm'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  downloadDossierFormFile: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Nội dung tệp. */
      200: {
        headers: {
          /** @description Tên tệp gợi ý, ví dụ `attachment; filename="MAU_1B-2026.pdf"`. */
          'Content-Disposition'?: string;
          /** @description SHA-256 của tệp, dạng `sha-256=<base64>` (suy luận). */
          Digest?: string;
          [name: string]: unknown;
        };
        content: {
          'application/pdf': string;
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Biểu mẫu chưa sinh xong hoặc sinh lỗi (`DOSSIER-409-FORM-NOT-RENDERED`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  listDossierSpecialCases: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Chỉ lấy trường hợp của hồ sơ thuộc gói này. */
        packageId?: components['schemas']['Uuid'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang trường hợp đặc thù và bảng gom nhóm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierSpecialCasePage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getDossierSpecialCase: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trường hợp đặc thù. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DossierSpecialCase'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listMyEsignRequests: {
    parameters: {
      query?: {
        /** @description Lọc theo trạng thái yêu cầu ký. */
        status?: components['schemas']['EsignRequestStatus'];
        /** @description Lọc theo mốc pháp lý. */
        purpose?: components['schemas']['EsignPurpose'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang yêu cầu ký. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EsignRequestPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  createEsignRequest: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EsignRequestCreate'];
      };
    };
    responses: {
      /** @description Đã tạo yêu cầu ký, đang chờ người ký xác nhận trên thiết bị. */
      202: {
        headers: {
          /** @description Đường dẫn theo dõi yêu cầu ký `/api/v1/esign/requests/{id}`. */
          Location?: string;
          'Retry-After': components['headers']['RetryAfter'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EsignRequest'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải chủ thể ký của bản ghi nguồn hoặc vi phạm tách vai trò (`ESIGN-403-NOT-SIGNER`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Bản ghi nguồn chưa sẵn sàng để ký, ví dụ biên bản chưa sinh (`ESIGN-409-SOURCE-NOT-READY`), hoặc đã có yêu cầu ký đang chờ cho cùng bản ghi và người ký (`ESIGN-409-REQUEST-PENDING`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Chứng thư hết hạn, bị thu hồi hoặc nhà cung cấp chưa cấu hình (`ESIGN-422-CERTIFICATE-INVALID`, `ESIGN-422-PROVIDER-UNAVAILABLE`). */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      503: components['responses']['ServiceUnavailable'];
      default: components['responses']['Problem'];
    };
  };
  getEsignRequest: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Yêu cầu ký. */
      200: {
        headers: {
          'Retry-After': components['headers']['RetryAfter'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EsignRequest'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listMyEsignCertificates: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách chứng thư. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['EsignCertificate'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
}
