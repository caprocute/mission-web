/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/oversight.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/oversight/complaints/eligible-profiles': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Kết quả đánh giá của tôi còn có thể khiếu nại
     * @description Trả các kết quả đánh giá của người gọi đã `DA_KY_SO` kèm ngày công bố, hạn khiếu nại, các hình thức còn được phép và danh sách cấu phần của snapshot để chọn ở SCR-1201 (trường 1, 2, 3). Khi quá hạn chỉ còn hình thức `BAO_LUU` (BR-12-01, MSG-12-01). Thao tác suy luận: TL-02b không ghi API riêng cho danh sách chọn này.
     */
    get: operations['listComplainableProfiles'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách khiếu nại trong phạm vi
     * @description Với `member` trả khiếu nại của chính mình (theo dõi tiến trình ở SCR-1201). Với người xử lý và người ký trả các khiếu nại được giao hoặc trong phạm vi, mặc định sắp theo hạn trả lời (SCR-1202 trường 1). Danh sách không chứa nội dung C2, chỉ `subjectLine`.
     */
    get: operations['listComplaints'];
    put?: never;
    /**
     * Lưu nháp khiếu nại mới
     * @description Tạo khiếu nại ở dạng nháp (chưa gửi, `status` là `null`). Nội dung lý do, đề nghị cụ thể được máy chủ mã hoá trường (C2). Tệp kèm theo tải lên trước qua `POST /api/v1/files` (SVC-08) rồi gửi mã đối tượng ở `attachmentObjectIds`. Kiểm tra đủ trường bắt buộc chỉ áp khi gửi.
     */
    post: operations['createComplaint'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết khiếu nại
     * @description Trả khiếu nại kèm nội dung C2 đã giải mã cho người có quyền (chủ hồ sơ, người xử lý, người ký); mỗi lần giải mã được ghi sổ cái (BR-12-10). Dòng thời gian lấy từ sổ cái và luồng xử lý (SCR-1202 trường 3).
     */
    get: operations['getComplaint'];
    /**
     * Sửa khiếu nại nháp
     * @description Ghi đè nội dung khiếu nại khi chưa gửi. Khiếu nại đã gửi trả 409 `OVERSIGHT-409-INVALID-STATE`.
     */
    put: operations['updateComplaint'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/submit': {
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
     * Ký số và gửi khiếu nại
     * @description Khởi động ký số nội dung khiếu nại qua SVC-10 (cần xác thực bước lên khi ký), sau đó chuyển hồ sơ đánh giá sang nhánh `KHIEU_NAI`, đặt khiếu nại `MOI`, phát EVT-25 để SVC-06 khởi động mẫu luồng `xu-ly-khieu-nai` v2 và chọn người xử lý theo tách vai trò. Kết quả hiện hành vẫn có hiệu lực (BR-12-06, MSG-12-02). Trả 202; job hoàn tất khi chữ ký xong (EVT-18).
     */
    post: operations['submitComplaint'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/withdraw': {
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
     * Rút khiếu nại
     * @description Đóng khiếu nại với lý do rút trước khi có kết luận; trạng thái `RUT` rồi `DONG`, hồ sơ đánh giá trở lại `DA_KY_SO`, nội dung khiếu nại vẫn được lưu.
     */
    post: operations['withdrawComplaint'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/reserve': {
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
     * Xác nhận bảo lưu ý kiến
     * @description Sau khi có văn bản trả lời (`DA_TRA_LOI`), người khiếu nại không đồng ý xác nhận bảo lưu; ý kiến (C2) được lưu thành `complaint_response` loại `BAO_LUU` và in trên Mẫu 3 (BR-12-09). Thời hạn xác nhận 5 ngày làm việc (MSG-12-04).
     */
    post: operations['reserveComplaintOpinion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/accept': {
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
     * Tiếp nhận khiếu nại
     * @description Chuyển `MOI` sang `DA_TIEP_NHAN`. OPA kiểm tra tách vai trò (BR-12-04, MSG-12-05).
     */
    post: operations['acceptComplaint'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/reject': {
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
     * Không thụ lý khiếu nại
     * @description Chuyển `MOI` sang `KHONG_THU_LY` (rồi tự động `DONG` sau khi gửi thông báo). Bắt buộc lý do (quá hạn, sai thẩm quyền, trùng nội dung đã trả lời theo BR-12-11).
     */
    post: operations['rejectComplaint'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/duplicate-candidates': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Tra cứu các khiếu nại nghi trùng nội dung đã trả lời
     * @description Hỗ trợ cán bộ thụ lý kiểm tra điều kiện trùng nội dung đã trả lời không có tình tiết mới (BR-12-11) trước khi quyết định thụ lý hoặc không thụ lý ở SCR-1202.
     */
    get: operations['listComplaintDuplicateCandidates'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/explanation-requests': {
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
     * Yêu cầu giải trình
     * @description Tạo một `complaint_response` loại `YEU_CAU` cho mỗi bên (người hoặc đơn vị), gửi thông báo, đặt hạn (mặc định 3 ngày làm việc); khiếu nại chuyển `DANG_GIAI_TRINH`.
     */
    post: operations['requestComplaintExplanations'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/explanations': {
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
     * Bên liên quan gửi giải trình
     * @description Bên được yêu cầu trả lời yêu cầu giải trình, kèm tài liệu (SCR-1202 chế độ bên liên quan, QT-12 bước 6). Tạo `complaint_response` loại `GIAI_TRINH`. Khi mọi yêu cầu đã trả lời hoặc quá hạn, khiếu nại tự chuyển `CHO_KET_LUAN`. TL-02b không ghi API cho bước này; đường dẫn là suy luận.
     */
    post: operations['submitComplaintExplanation'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/responses': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Các bản ghi trả lời của khiếu nại
     * @description Yêu cầu giải trình, giải trình đã nhận, kết luận và bảo lưu (`oversight.complaint_response`) của một khiếu nại, theo thời gian. Nội dung C2 được giải mã cho người có quyền và ghi sổ cái.
     */
    get: operations['listComplaintResponses'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/conclusion': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    /**
     * Ghi kết luận xử lý khiếu nại
     * @description Người xử lý (AC-07) ghi kết quả xử lý, điều chỉnh đề nghị và số văn bản trả lời sinh từ mẫu TL-KN-01 (QT-12 bước 8, SCR-1202 trường 6, 7, 9) trước khi người có thẩm quyền ký (bước 10). Từ `DA_TIEP_NHAN` không qua giải trình thì bắt buộc `noExplanationNote`. Khiếu nại ở `CHO_KET_LUAN`. Kết luận có điều chỉnh với kỳ `DA_KHOA` trả kèm cảnh báo MSG-12-07 và phải tạo yêu cầu mở lại kỳ (BR-12-07); không có API điều chỉnh trực tiếp. TL-02b không tách API cho bước này; đường dẫn là suy luận.
     */
    put: operations['saveComplaintConclusion'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/complaints/{id}/respond': {
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
     * Ký số văn bản trả lời khiếu nại
     * @description Người có thẩm quyền (AC-08) ký số văn bản trả lời đã sinh từ mẫu và gửi người khiếu nại; khiếu nại chuyển `DA_TRA_LOI` (rồi `DANG_THI_HANH` nếu có điều chỉnh gắn yêu cầu mở lại kỳ). Đây là thao tác `POST /oversight/complaints/{id}/responses` của TL-01 Phụ lục C. Trả 202; job hoàn tất khi chữ ký xong (EVT-18), sau đó gửi MSG-12-04 cho người khiếu nại.
     */
    post: operations['respondComplaint'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/reopen-requests': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách yêu cầu mở lại kỳ
     * @description Yêu cầu do người gọi tạo, được giao duyệt hoặc trong phạm vi.
     */
    get: operations['listReopenRequests'];
    put?: never;
    /**
     * Tạo yêu cầu mở lại kỳ
     * @description Lưu yêu cầu ở `CHO_DUYET_1`, gọi OPA chỉ định người duyệt thứ nhất (`decider`) và thứ hai (`second_approver`) loại trừ người tạo và người đã tham gia hồ sơ, khởi động mẫu luồng `mo-lai-ky-hai-nguoi` v1. Kỳ phải `DA_KHOA`; phạm vi ít nhất một đối tượng, không chọn `TOAN_KY` nếu không có quyết định hậu kiểm toàn diện (BR-13-05). Gọi từ SCR-1202 khi kết luận khiếu nại có điều chỉnh (BR-12-07) hoặc từ SCR-1401 khi kết luận có vi phạm.
     */
    post: operations['createReopenRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/reopen-requests/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết yêu cầu mở lại kỳ
     * @description Yêu cầu kèm hai phê duyệt (`oversight.reopen_approval`) và người duyệt được chỉ định cho từng lượt.
     */
    get: operations['getReopenRequest'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/reopen-requests/{id}/simulate': {
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
     * Tính thử ảnh hưởng của yêu cầu mở lại kỳ
     * @description Gọi SVC-07 chế độ mô phỏng với phiên bản Policy Pack của kỳ (BR-13-06), trả chỉ tiêu trước và sau cho các hồ sơ trong phạm vi; không ghi gì (SCR-1301 trường 8).
     */
    post: operations['simulateReopenRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/reopen-requests/{id}/approvals': {
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
     * Phê duyệt yêu cầu mở lại kỳ (lượt 1 hoặc 2)
     * @description Người duyệt được OPA chỉ định, đúng lượt, sau xác thực bước lên (MSG-13-02) ký số phê duyệt. Lượt 1 (`decider`) chuyển `CHO_DUYET_2`; lượt 2 (`second_approver`, khác người duyệt 1 và khác người tạo) chuyển `DA_DUYET`, MOD-08 đưa kỳ sang `MO_LAI_HAU_KIEM` trong phạm vi đã duyệt và phát EVT-22 (MSG-13-03). Đây là thao tác `POST /oversight/reopen-requests/{id}/approve` của TL-01 Phụ lục C. Hai phê duyệt đồng thời được tuần tự hoá bằng khoá dòng (TL-03).
     */
    post: operations['approveReopenRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/reopen-requests/{id}/reject': {
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
     * Từ chối yêu cầu mở lại kỳ
     * @description Người duyệt được chỉ định ghi ý kiến, ký số từ chối; yêu cầu chuyển `TU_CHOI` và đóng. Theo QT-13 bước 6, 7, việc từ chối cũng qua xác thực bước lên.
     */
    post: operations['rejectReopenRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/reopen-requests/{id}/cancel': {
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
     * Huỷ yêu cầu mở lại kỳ
     * @description Người tạo huỷ yêu cầu trước khi đủ hai phê duyệt, bắt buộc lý do; yêu cầu chuyển `DA_DONG` (TL-03; TL-02b không có trạng thái riêng cho huỷ).
     */
    post: operations['cancelReopenRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/post-audits': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Danh sách đợt hậu kiểm */
    get: operations['listPostAudits'];
    put?: never;
    /**
     * Khởi tạo đợt hậu kiểm
     * @description Tạo `oversight.post_audit` (mở) theo loại kế hoạch, đột xuất hoặc theo phản ánh, với đối tượng và căn cứ (QT-13 bước 1). TL-02b không ghi API; đường dẫn là suy luận.
     */
    post: operations['createPostAudit'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/post-audits/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết đợt hậu kiểm
     * @description Phát hiện, kết luận chi tiết (C2) được giải mã cho người có quyền và ghi sổ cái.
     */
    get: operations['getPostAudit'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/post-audits/{id}/close': {
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
     * Lập báo cáo và đóng đợt hậu kiểm
     * @description Ghi kết luận, phát hiện chi tiết (C2), yêu cầu mở lại kỳ liên quan và tệp bằng chứng xuất từ sổ cái; đặt `closedAt` (QT-13 bước 13). Đường dẫn suy luận.
     */
    post: operations['closePostAudit'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/alerts': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách cảnh báo bất thường
     * @description Cảnh báo trong phạm vi cây tổ chức, sắp theo mức rồi thời gian (SCR-1401 trường 2), kèm thẻ phân bố 90 ngày (trường 3). OPA ẩn cảnh báo mà người xem là đối tượng (BR-14-03). Đây là `GET /oversight/anomalies` của TL-01 Phụ lục C.
     */
    get: operations['listAnomalyAlerts'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/alerts/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết cảnh báo và chuỗi truy vết
     * @description Trả cảnh báo, chuỗi bằng chứng, bảng nguồn và các hành động (kể cả hành động tự động). Theo TL-02b, lần mở đầu tiên của người xử lý chuyển `MOI` sang `DANG_XEM`; đây là hiệu ứng phụ ghi trạng thái trên một GET, máy chủ thực hiện luỹ đẳng và không đổi ETag của lần đọc khác.
     */
    get: operations['getAnomalyAlert'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/alerts/{id}/actions': {
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
     * Xử lý cảnh báo (xác nhận hợp lệ, giao kiểm tra, kết luận, ghi chú)
     * @description Mọi hành động có ghi chú 20 đến 2.000 ký tự và được ghi sổ cái (BR-14-05). `XAC_NHAN_HOP_LE` (từ `DANG_XEM`): cảnh báo `HOP_LE` rồi `DONG`, gỡ cờ chặn (MSG-14-03). `GIAO_KIEM_TRA` (từ `DANG_XEM`): `DA_GIAO_KIEM_TRA`, bắt buộc `assignedTo`. `KET_LUAN` (chỉ `inspector`, từ `DA_GIAO_KIEM_TRA`): `CO_VI_PHAM` hoặc `KHONG_VI_PHAM` theo `verdict`; có vi phạm với kỳ đã khoá thì giao diện mở SCR-1301 tạo yêu cầu mở lại kỳ (MSG-14-04, BR-14-02). Người xử lý không được là đối tượng (BR-14-08). Thao tác này thay `POST /oversight/anomalies/{id}/resolution` của TL-01 Phụ lục C.
     */
    post: operations['createAlertAction'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ledger/entries': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tra cứu sổ cái
     * @description Lọc bản ghi sổ cái theo đối tượng, khoảng thời gian (mặc định 90 ngày gần nhất, tối đa 2 năm mỗi lần) và loại sự kiện. Trường C2 trong giá trị trước, sau hiện dạng che. Mỗi lần tra cứu được ghi sổ cái. Bản ghi cũ hơn 24 tháng đọc từ kho lưu trữ Parquet nên chậm hơn.
     */
    get: operations['listLedgerEntries'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ledger/entries/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết bản ghi sổ cái
     * @description Bản ghi kèm các băm, niêm phong Merkle chứa bản ghi (nếu đã niêm phong) và các biên nhận neo của niêm phong cell.
     */
    get: operations['getLedgerEntry'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ledger/verify-runs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Các lần kiểm tra toàn vẹn
     * @description Lần kiểm tra gần nhất trước (SCR-1302 trường 6), gồm lần chạy theo lịch và theo yêu cầu.
     */
    get: operations['listLedgerVerifyRuns'];
    put?: never;
    /**
     * Chạy kiểm tra toàn vẹn chuỗi băm
     * @description Tạo tác vụ kiểm tra toàn bộ hoặc một phân vùng chuỗi: tính lại chuỗi băm, đối chiếu niêm phong Merkle và bản neo, chỉ ra phân vùng và bản ghi đầu tiên bị phá vỡ (BR-13-10). Phát hiện phá vỡ thì ledger-service khoá ghi phân vùng và phát EVT-20 mức Cao (BR-13-11, MSG-13-06); hoàn tất không phá vỡ thì MSG-13-05. Job có `resultLocation` trỏ tới `/api/v1/ledger/verify-runs/{id}`.
     */
    post: operations['createLedgerVerifyRun'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ledger/verify-runs/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Kết quả một lần kiểm tra toàn vẹn */
    get: operations['getLedgerVerifyRun'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ledger/exports': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xuất bằng chứng sổ cái (JSON kèm chữ ký)
     * @description Xuất các bản ghi, niêm phong và bản neo trong phạm vi lọc, ký bằng khoá của ledger-service để kiểm tra độc lập bằng CLI `ledger-verify` (BR-13-12, MSG-13-07). Trả 202; `resultLocation` của job trỏ tới tệp tải về.
     */
    post: operations['createLedgerExport'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/oversight/ledger/proofs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Bằng chứng bao hàm Merkle của một sự kiện
     * @description Trả đường dẫn Merkle từ lá `SHA-256(0x01 || entry_hash)` lên gốc tenant, rồi từ gốc tenant lên gốc cell, kèm chữ ký gốc cell, token RFC 3161 và biên nhận neo (TL-03 chương 8). Sự kiện chưa được niêm phong trả 409 `LEDGER-409-NOT-SEALED`. Đường dẫn giữ đúng TL-01 Phụ lục C; dịch vụ sở hữu là SVC-09 dù tiền tố là `/oversight`.
     */
    get: operations['getLedgerInclusionProof'];
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
     * @description Hình thức (`oversight.complaint.kind`, BR-12-03).
     * @enum {string}
     */
    ComplaintKind: 'KHIEU_NAI' | 'GIAI_TRINH' | 'BAO_LUU';
    /**
     * @description Bước bị khiếu nại (`oversight.complaint.target_stage`).
     * @enum {string}
     */
    ComplaintTargetStage: 'THAM_DINH' | 'DE_XUAT' | 'QUYET_DINH';
    /**
     * @description Trạng thái khiếu nại (`oversight.complaint.status`, bộ của TL-02b thêm `RUT`). TL-03 không có trạng thái nháp; khiếu nại chưa gửi có `status` là `null`.
     * @enum {string}
     */
    ComplaintStatus:
      | 'MOI'
      | 'DA_TIEP_NHAN'
      | 'DANG_GIAI_TRINH'
      | 'CHO_KET_LUAN'
      | 'DA_TRA_LOI'
      | 'DANG_THI_HANH'
      | 'KHONG_THU_LY'
      | 'RUT'
      | 'DONG';
    /**
     * @description Kết quả của khiếu nại (`oversight.complaint.outcome`).
     * @enum {string}
     */
    ComplaintOutcome: 'GIU_NGUYEN' | 'DIEU_CHINH' | 'BAO_LUU';
    /**
     * @description Kết quả xử lý ghi ở kết luận (`oversight.complaint_response.outcome`, SCR-1202 trường 6). Máy chủ suy ra `complaint.outcome`: `GIU_NGUYEN` giữ nguyên, hai giá trị còn lại thành `DIEU_CHINH`.
     * @enum {string}
     */
    ComplaintResolution: 'GIU_NGUYEN' | 'CHAP_NHAN_MOT_PHAN' | 'CHAP_NHAN';
    /**
     * @description Loại bản ghi trả lời (`oversight.complaint_response.record_kind`).
     * @enum {string}
     */
    ComplaintRecordKind: 'YEU_CAU' | 'GIAI_TRINH' | 'KET_LUAN' | 'BAO_LUU';
    /** @description Một cấu phần bị khiếu nại lấy từ snapshot (`targets_json`). */
    ComplaintTarget: {
      /** @description Khoá cấu phần trong snapshot, ví dụ `NV-THEN-CHOT-3.C`. */
      key: string;
      stage: components['schemas']['ComplaintTargetStage'];
      /** @description Nhãn hiển thị. */
      label?: string;
    };
    /** @description Kết quả đánh giá có thể khiếu nại (suy luận, SCR-1201 trường 1..3). */
    ComplainableProfile: {
      profileId: components['schemas']['Uuid'];
      period: components['schemas']['PeriodRef'];
      rating?: components['schemas']['RatingLevel'];
      score?: number;
      publishedAt?: components['schemas']['DateTime'];
      /** @description Số quyết định công bố. */
      publishDecision?: string;
      complaintDeadline: components['schemas']['Date'];
      /** @description Số ngày làm việc còn lại; âm khi quá hạn. */
      workingDaysLeft?: number;
      allowedKinds: components['schemas']['ComplaintKind'][];
      targets: components['schemas']['ComplaintTarget'][];
    };
    /** @description Nội dung khiếu nại khi lưu nháp (SCR-1201 bảng trường). */
    ComplaintInput: {
      profileId: components['schemas']['Uuid'];
      kind: components['schemas']['ComplaintKind'];
      targetStage: components['schemas']['ComplaintTargetStage'];
      /** @description Ít nhất một cấu phần khi gửi (BR-12-02). */
      targets?: components['schemas']['ComplaintTarget'][];
      /** @description Tiêu đề chung không chứa nội dung nhạy cảm; bỏ trống thì máy chủ sinh từ kết quả bị khiếu nại. */
      subjectLine?: string;
      /** @description Lý do, căn cứ, 50 đến 8.000 ký tự khi gửi (`content_enc`). */
      content?: string;
      /** @description Đề nghị cụ thể (`request_text_enc`). */
      requestText?: string;
      /** @description Tệp kèm ở SVC-08 (`attachment_object_ids`), đã quét mã độc và tính SHA-256. */
      attachmentObjectIds?: components['schemas']['Uuid'][];
      /**
       * @description Bảo lưu ý kiến nếu không được chấp nhận (SCR-1201 trường 7; TL-03 chỉ có `reserve_opinion_enc`, cờ này là suy luận).
       * @default false
       */
      reserveOpinion: boolean;
    };
    /** @description Dòng danh sách khiếu nại (không chứa C2). */
    ComplaintSummary: {
      id: components['schemas']['Uuid'];
      /** @description Mã khiếu nại dạng KN-yyyy-nnnn (suy luận, TL-03 không có cột mã). */
      code: string;
      complainant?: components['schemas']['Ref'];
      subjectLine?: string;
      kind: components['schemas']['ComplaintKind'];
      targetStage?: components['schemas']['ComplaintTargetStage'];
      status: components['schemas']['ComplaintStatus'] | null;
      /** Format: date-time */
      filedAt?: string | null;
      /** Format: date */
      responseDeadline?: string | null;
      workingDaysLeft?: number | null;
    };
    ComplaintTimelineItem: {
      at: components['schemas']['DateTime'];
      /** @description Mô tả hành động, ví dụ "Tiếp nhận". */
      action: string;
      actor?: components['schemas']['Ref'];
      ledgerEntryId?: components['schemas']['Uuid'];
    };
    /** @description Khiếu nại (`oversight.complaint`). */
    Complaint: components['schemas']['ComplaintSummary'] & {
      profileId: components['schemas']['Uuid'];
      period: components['schemas']['PeriodRef'];
      targets?: components['schemas']['ComplaintTarget'][];
      /** @description Lý do, căn cứ đã giải mã; chỉ có khi người gọi có quyền đọc C2. */
      content?: string;
      requestText?: string;
      reserveOpinion?: boolean;
      /** @description Ý kiến bảo lưu đã xác nhận. */
      reservedOpinion?: string | null;
      attachmentObjectIds?: components['schemas']['Uuid'][];
      handlerUnit?: components['schemas']['Ref'];
      handler?: components['schemas']['Ref'];
      outcome?: components['schemas']['ComplaintOutcome'] | null;
      conclusion?: components['schemas']['ComplaintConclusion'];
      /**
       * Format: uuid
       * @description Yêu cầu mở lại kỳ thi hành kết luận, nếu có.
       */
      reopenRequestId?: string | null;
      timeline?: components['schemas']['ComplaintTimelineItem'][];
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Bên được yêu cầu giải trình (SCR-1202 trường 4). */
    ExplanationParty: {
      /**
       * @description Người hoặc đơn vị (suy luận mã).
       * @enum {string}
       */
      kind: 'NGUOI' | 'DON_VI';
      id: components['schemas']['Uuid'];
    };
    /** @description Một điều chỉnh đề nghị (SCR-1202 trường 7, `adjustment_json`). */
    ComplaintAdjustment: {
      targetKey: string;
      oldValue?: number | string | null;
      newValue: number | string;
      reason: string;
    };
    ComplaintConclusionInput: {
      outcome: components['schemas']['ComplaintResolution'];
      /** @description Bắt buộc khi `outcome` khác `GIU_NGUYEN`. */
      adjustments?: components['schemas']['ComplaintAdjustment'][];
      /** @description Nội dung văn bản trả lời (`response_enc`). */
      responseText: string;
      /** @description Số văn bản trả lời sinh từ mẫu TL-KN-01. */
      responseDocRef?: string;
      /** @description Ghi chú khi kết luận thẳng từ `DA_TIEP_NHAN` không qua giải trình (suy luận). */
      noExplanationNote?: string;
    };
    /** @description Kết luận hiện hành của khiếu nại (bản ghi `KET_LUAN`). */
    ComplaintConclusion: {
      outcome?: components['schemas']['ComplaintResolution'];
      adjustments?: components['schemas']['ComplaintAdjustment'][];
      responseDocRef?: string | null;
      /** Format: uuid */
      signRequestId?: string | null;
      /** @description Kỳ đã `DA_KHOA`, điều chỉnh phải qua mở lại kỳ (MSG-12-07). */
      periodLocked?: boolean;
    } | null;
    /** @description Bản ghi `oversight.complaint_response`. */
    ComplaintResponseRecord: {
      id: components['schemas']['Uuid'];
      complaintId: components['schemas']['Uuid'];
      recordKind: components['schemas']['ComplaintRecordKind'];
      responder?: components['schemas']['Ref'];
      party?: components['schemas']['ExplanationParty'];
      /**
       * Format: uuid
       * @description Với `GIAI_TRINH`, yêu cầu được trả lời (suy luận).
       */
      requestId?: string | null;
      /** Format: date */
      dueDate?: string | null;
      content?: string;
      attachmentObjectIds?: components['schemas']['Uuid'][];
      outcome?: components['schemas']['ComplaintResolution'] | null;
      adjustments?: components['schemas']['ComplaintAdjustment'][];
      responseDocRef?: string | null;
      /** Format: uuid */
      signRequestId?: string | null;
      respondedAt: components['schemas']['DateTime'];
    };
    OversightReasonInput: {
      reason: string;
    };
    /**
     * @description Trạng thái yêu cầu (`oversight.reopen_request.status`); bốn giá trị đầu của TL-02b, TL-03 thêm `DA_THUC_HIEN` (kỳ đã mở lại, EVT-22) và `DA_DONG`.
     * @enum {string}
     */
    ReopenRequestStatus:
      'CHO_DUYET_1' | 'CHO_DUYET_2' | 'DA_DUYET' | 'TU_CHOI' | 'DA_THUC_HIEN' | 'DA_DONG';
    /**
     * @description Phạm vi chuẩn hoá (`oversight.reopen_request.scope`).
     * @enum {string}
     */
    ReopenScope: 'TOAN_KY' | 'DON_VI' | 'HO_SO';
    /**
     * @description Loại hậu kiểm làm căn cứ (`reopen_request.audit_kind`, SCR-1301 trường 3). TL-03 không đặt CHECK cho cột này và dùng bộ khác ở `post_audit.audit_kind`; hợp đồng giữ bộ của SCR-1301.
     * @enum {string}
     */
    ReopenAuditKind: 'KE_HOACH' | 'DOT_XUAT' | 'PHAN_ANH';
    ReopenScopeItem: {
      profileId?: components['schemas']['Uuid'];
      unitId?: components['schemas']['Uuid'];
      /** @description Bảng được sửa, ví dụ `product.acceptance`. */
      table: string;
      recordId?: components['schemas']['Uuid'];
      /** @description Cột được phép sửa. */
      fields?: string[];
    };
    /** @description Căn cứ của yêu cầu (`basis_ref`). */
    ReopenBasis: {
      /**
       * @description Loại căn cứ (suy luận mã).
       * @enum {string}
       */
      kind: 'KHIEU_NAI' | 'CANH_BAO' | 'HAU_KIEM' | 'QUYET_DINH';
      id?: components['schemas']['Uuid'];
      /** @description Mã hiển thị hoặc số quyết định. */
      code?: string;
    };
    ReopenRequestInput: {
      periodId: components['schemas']['Uuid'];
      auditKind: components['schemas']['ReopenAuditKind'];
      scope: components['schemas']['ReopenScope'];
      /** @description Đơn vị hoặc hồ sơ; khác rỗng khi `scope` khác `TOAN_KY`. */
      scopeIds: components['schemas']['Uuid'][];
      /** @description Phạm vi chi tiết (`scope_json`). */
      scopeDetail: {
        items: components['schemas']['ReopenScopeItem'][];
      };
      /**
       * @description Thời hạn mở theo ngày làm việc; máy chủ tính `openUntil` theo lịch tenant.
       * @default 3
       */
      openWorkingDays: number;
      reason: string;
      basis: components['schemas']['ReopenBasis'];
    };
    /** @description Một phê duyệt (`oversight.reopen_approval`). */
    ReopenApproval: {
      id?: components['schemas']['Uuid'];
      approver: components['schemas']['Ref'];
      /** @enum {string} */
      approverRole: 'decider' | 'second_approver';
      /** @enum {string} */
      decision: 'DONG_Y' | 'TU_CHOI';
      /** @description Claim `acr` của phiên step-up. */
      mfaAcr?: string;
      signRequestId?: components['schemas']['Uuid'];
      comment?: string | null;
      decidedAt: components['schemas']['DateTime'];
    };
    /** @description Người duyệt được OPA chỉ định cho một lượt (suy luận, SCR-1301 trường 9). */
    ReopenApproverSlot: {
      /** @enum {integer} */
      round: 1 | 2;
      /** @enum {string} */
      role: 'decider' | 'second_approver';
      assignee?: components['schemas']['Ref'];
      approval?: components['schemas']['ReopenApproval'] | null;
    };
    /** @description Yêu cầu mở lại kỳ (`oversight.reopen_request`). */
    ReopenRequest: {
      id: components['schemas']['Uuid'];
      /** @description MLK-yyyy-nnn, duy nhất trong tenant. */
      code: string;
      period: components['schemas']['PeriodRef'];
      periodStatus?: components['schemas']['PeriodStatus'];
      auditKind?: components['schemas']['ReopenAuditKind'];
      scope: components['schemas']['ReopenScope'];
      scopeIds?: components['schemas']['Uuid'][];
      scopeDetail?: {
        items?: components['schemas']['ReopenScopeItem'][];
      };
      openUntil?: components['schemas']['DateTime'];
      reason: string;
      basis?: components['schemas']['ReopenBasis'];
      requestedBy: components['schemas']['Ref'];
      approvalsRequired: number;
      status: components['schemas']['ReopenRequestStatus'];
      approvers?: components['schemas']['ReopenApproverSlot'][];
      /** Format: date-time */
      executedAt?: string | null;
      /** Format: date-time */
      relockedAt?: string | null;
      createdAt?: components['schemas']['DateTime'];
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Ảnh hưởng dự kiến (tính thử, không lưu). */
    ReopenImpact: {
      policyVersion: string;
      rows: {
        profileId?: components['schemas']['Uuid'];
        metric: string;
        before: string;
        after: string;
      }[];
      formsToRegenerate?: number;
    };
    /**
     * @description Loại hậu kiểm (`oversight.post_audit.audit_kind`, QĐ 15.3).
     * @enum {string}
     */
    PostAuditKind: 'THEO_KE_HOACH' | 'DOT_XUAT' | 'THEO_PHAN_ANH';
    /** @enum {string} */
    PostAuditTargetType: 'HO_SO' | 'DON_VI' | 'NHIEM_VU' | 'MINH_CHUNG';
    /** @enum {string} */
    PostAuditConclusion: 'KHONG_SAI_LECH' | 'HUY_KET_QUA' | 'DANH_GIA_LAI';
    /**
     * @description Đợt hậu kiểm (`oversight.post_audit`).
     * @example {
     *       "id": "01929b60-1111-7222-8333-000000000001",
     *       "periodId": "01929a10-0000-7000-8000-000000000204",
     *       "auditKind": "DOT_XUAT",
     *       "targetType": "DON_VI",
     *       "targetId": "01929a00-bbbb-7ccc-8ddd-000000000043",
     *       "basis": "QĐ 12-QĐ/UBKT ngày 05/02/2027",
     *       "inspector": {
     *         "id": "01929a00-aaaa-7bbb-8ccc-000000000310",
     *         "name": "Đỗ Văn Lâm"
     *       },
     *       "findings": null,
     *       "conclusion": null,
     *       "reopenRequestId": null,
     *       "openedAt": "2027-02-06T08:00:00+07:00",
     *       "closedAt": null,
     *       "rowVersion": 1
     *     }
     */
    PostAudit: {
      id: components['schemas']['Uuid'];
      periodId: components['schemas']['Uuid'];
      auditKind: components['schemas']['PostAuditKind'];
      targetType: components['schemas']['PostAuditTargetType'];
      targetId: components['schemas']['Uuid'];
      basis?: string | null;
      inspector: components['schemas']['Ref'];
      findings?: string | null;
      conclusion?: components['schemas']['PostAuditConclusion'] | null;
      /** Format: uuid */
      reopenRequestId?: string | null;
      openedAt: components['schemas']['DateTime'];
      /** Format: date-time */
      closedAt?: string | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    /**
     * @description Tám dấu hiệu bất thường (`oversight.anomaly_alert.signal_code`, slide 31).
     * @enum {string}
     */
    AnomalySignalCode:
      | 'TU_CHAM_CAO'
      | 'CAO_BANG'
      | 'MUC_TIEU_DE'
      | 'MINH_CHUNG_TRUNG'
      | 'CAP_NHAT_DON'
      | 'SUA_SAU_KHOA'
      | 'CHE_NHO'
      | 'MIEN_TRU_LAP';
    /** @enum {string} */
    AnomalySeverity: 'CAO' | 'VUA' | 'THAP';
    /**
     * @description Trạng thái cảnh báo (`oversight.anomaly_alert.status`).
     * @enum {string}
     */
    AnomalyAlertStatus:
      | 'MOI'
      | 'DANG_XEM'
      | 'HOP_LE'
      | 'DA_GIAO_KIEM_TRA'
      | 'CO_VI_PHAM'
      | 'KHONG_VI_PHAM'
      | 'TU_DONG_DONG'
      | 'DONG';
    /**
     * @description Loại hành động (`oversight.alert_action.action`). TL-03 CHECK gồm `GIAO_KIEM_TRA`, `XAC_NHAN_HOP_LE`, `GHI_CHU`, `DONG`; hợp đồng thêm `KET_LUAN` theo TL-02b SCR-1401 (TL-03 thiếu) và `TU_DONG` cho hành động tự động của hệ thống (suy luận). `DONG`, `TU_DONG` chỉ do hệ thống ghi.
     * @enum {string}
     */
    AlertActionType:
      'XAC_NHAN_HOP_LE' | 'GIAO_KIEM_TRA' | 'KET_LUAN' | 'GHI_CHU' | 'DONG' | 'TU_DONG';
    AlertActionInput: {
      /** @enum {string} */
      type: 'XAC_NHAN_HOP_LE' | 'GIAO_KIEM_TRA' | 'KET_LUAN' | 'GHI_CHU';
      note: string;
      /** @description Người của cơ quan kiểm tra được giao; bắt buộc khi `GIAO_KIEM_TRA`. */
      assignedTo?: components['schemas']['Uuid'];
      /**
       * @description Bắt buộc khi `KET_LUAN`.
       * @enum {string}
       */
      verdict?: 'CO_VI_PHAM' | 'KHONG_VI_PHAM';
    };
    AlertSubject: {
      /** @enum {string} */
      type: 'CA_NHAN' | 'DON_VI' | 'NGUOI_DUYET';
      id: components['schemas']['Uuid'];
      name?: string;
    };
    AnomalyAlertSummary: {
      id: components['schemas']['Uuid'];
      severity: components['schemas']['AnomalySeverity'];
      signalCode: components['schemas']['AnomalySignalCode'];
      subject: components['schemas']['AlertSubject'];
      period?: components['schemas']['PeriodRef'];
      status: components['schemas']['AnomalyAlertStatus'];
      detectedAt: components['schemas']['DateTime'];
    };
    /** @description Thẻ phân bố 90 ngày (SCR-1401 trường 3). */
    AnomalyAlertCounts: {
      /** @default 90 */
      windowDays: number;
      newCount: number;
      validCount: number;
      assignedCount: number;
      violationCount: number;
    };
    /** @description Một nút của chuỗi truy vết (`evidence_chain`), chỉ chứa mã, không chứa C2/C3. */
    EvidenceChainNode: {
      /**
       * @description Loại nút (suy luận mã từ SCR-1401 trường 4).
       * @enum {string}
       */
      kind:
        | 'DIEM'
        | 'CAU_PHAN'
        | 'KPI'
        | 'NHIEM_VU'
        | 'SAN_PHAM'
        | 'MINH_CHUNG'
        | 'BAN_GHI_SO_CAI'
        | 'BAM_TRUNG';
      id: string;
      label?: string;
      children?: components['schemas']['EvidenceChainNode'][];
    };
    /** @description Dòng bảng nguồn (SCR-1401 trường 5). */
    AlertSourceRow: {
      submitter?: components['schemas']['Ref'];
      task?: components['schemas']['Ref'];
      submittedAt?: components['schemas']['DateTime'];
      sha256?: string;
    };
    /** @description Hành động xử lý (`oversight.alert_action`). */
    AlertAction: {
      id: components['schemas']['Uuid'];
      action: components['schemas']['AlertActionType'];
      actor?: components['schemas']['Ref'];
      assignedTo?: components['schemas']['Ref'];
      /** @enum {string|null} */
      verdict?: 'CO_VI_PHAM' | 'KHONG_VI_PHAM' | null;
      note?: string | null;
      /** @description Với hành động tự động: cờ chặn còn hiệu lực (BR-14-04). */
      active?: boolean;
      actedAt: components['schemas']['DateTime'];
    };
    /** @description Cảnh báo bất thường (`oversight.anomaly_alert`). */
    AnomalyAlert: components['schemas']['AnomalyAlertSummary'] & {
      evidenceChain: components['schemas']['EvidenceChainNode'][];
      sources?: components['schemas']['AlertSourceRow'][];
      ledgerEntryIds?: string[];
      modelVersion?: string;
      actions: components['schemas']['AlertAction'][];
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Băm SHA-256 ghi dạng hex chữ thường 64 ký tự (cột `bytea` của TL-03; cách mã hoá là suy luận). */
    LedgerHashHex: string;
    /** @description Bản ghi sổ cái (`ledger.ledger_entry`). */
    LedgerEntry: {
      id: components['schemas']['Uuid'];
      chainPartition: number;
      /** Format: int64 */
      seqNo: number;
      eventId: components['schemas']['Uuid'];
      eventType: string;
      aggregateType: string;
      aggregateId: components['schemas']['Uuid'];
      actor?: components['schemas']['Ref'];
      /** @enum {string} */
      actorKind: 'NGUOI' | 'DICH_VU' | 'HE_THONG';
      occurredAt: components['schemas']['DateTime'];
      recordedAt: components['schemas']['DateTime'];
      /** @description Siêu dữ liệu rút gọn (trước, sau, mã yêu cầu mở lại); không chứa C2, trường nhạy cảm đã che. */
      metadata?: {
        [key: string]: unknown;
      };
      entryHash: components['schemas']['LedgerHashHex'];
    };
    /** @description Niêm phong Merkle mức tenant (`ledger.merkle_seal`) và niêm phong cell chứa nó. */
    LedgerSeal: {
      id: components['schemas']['Uuid'];
      windowStart: components['schemas']['DateTime'];
      windowEnd: components['schemas']['DateTime'];
      /** Format: int64 */
      entryCount?: number;
      tenantRoot: components['schemas']['LedgerHashHex'];
      prevTenantRoot?: components['schemas']['LedgerHashHex'] | null;
      cellSealId?: components['schemas']['Uuid'];
      cellRoot?: components['schemas']['LedgerHashHex'];
      signatureValid?: boolean;
      tsaGenTime?: components['schemas']['DateTime'];
      /** @description Dấu thời gian gắn bù do TSA không phản hồi lúc niêm phong. */
      tsaLate?: boolean;
    };
    /** @description Biên nhận neo (`ledger.anchor_receipt`). */
    LedgerAnchorReceipt: {
      id?: components['schemas']['Uuid'];
      /** @enum {string} */
      anchorTarget: 'CELL_KHAC' | 'KHO_VPTW' | 'TSA_DOC_LAP' | 'OBJECT_LOCK';
      /** @description Biên nhận đã ký của nơi nhận. */
      receipt?: string;
      anchoredAt: components['schemas']['DateTime'];
    };
    LedgerEntryDetail: components['schemas']['LedgerEntry'] & {
      prevHash: components['schemas']['LedgerHashHex'];
      payloadHash: components['schemas']['LedgerHashHex'];
      metadataHash: components['schemas']['LedgerHashHex'];
      seal?: components['schemas']['LedgerSeal'] | null;
      anchors?: components['schemas']['LedgerAnchorReceipt'][];
    };
    /** @enum {string} */
    LedgerVerifyKind: 'DAU_CHUOI' | 'NIEM_PHONG' | 'TOAN_BO' | 'LUU_TRU';
    /** @description Một mắt chuỗi minh hoạ quanh vị trí sai (SCR-1302 trường 5). */
    LedgerChainLink: {
      /** Format: int64 */
      seqNo: number;
      entryId?: components['schemas']['Uuid'];
      prevHash: components['schemas']['LedgerHashHex'];
      storedHash: components['schemas']['LedgerHashHex'];
      recomputedHash?: components['schemas']['LedgerHashHex'];
      matches: boolean;
    };
    /** @description Lần kiểm tra toàn vẹn (`ledger.verify_run`). */
    LedgerVerifyRun: {
      id: components['schemas']['Uuid'];
      /** @description Mã hiển thị VR-yyyy-nnnn (suy luận, TL-03 không có cột mã). */
      code?: string;
      verifyKind: components['schemas']['LedgerVerifyKind'];
      chainPartition?: number | null;
      rangeFrom: components['schemas']['DateTime'];
      rangeTo: components['schemas']['DateTime'];
      /** Format: int64 */
      entriesChecked: number;
      /**
       * Format: int64
       * @description (suy luận)
       */
      sealsChecked?: number;
      /** @description (suy luận) */
      partitionsTotal?: number;
      /** @description (suy luận) */
      partitionsMatched?: number;
      mismatches: number;
      /** @description Bản ghi sai đầu tiên (`first_mismatch`). */
      firstMismatch?: {
        chainPartition?: number;
        /** Format: int64 */
        seqNo?: number;
        entryId?: components['schemas']['Uuid'];
        storedHash?: components['schemas']['LedgerHashHex'];
        recomputedHash?: components['schemas']['LedgerHashHex'];
        sealId?: components['schemas']['Uuid'];
        anchorMatches?: boolean;
      } | null;
      /** @description Các mắt chuỗi quanh vị trí sai để vẽ sơ đồ khối. */
      chain?: components['schemas']['LedgerChainLink'][];
      /**
       * Format: uuid
       * @description Cảnh báo mức Cao đã tạo khi phá vỡ.
       */
      alertId?: string | null;
      startedAt: components['schemas']['DateTime'];
      /** Format: date-time */
      finishedAt?: string | null;
    };
    MerkleProofStep: {
      /**
       * @description Vị trí của nút anh em so với nút đang tính.
       * @enum {string}
       */
      side: 'TRAI' | 'PHAI';
      hash: components['schemas']['LedgerHashHex'];
    };
    /** @description Bằng chứng bao hàm hai mức. Lá `SHA-256(0x01 || entry_hash)`, nút `SHA-256(0x02 || trái || phải)` (ADR-26 của TL-01). */
    LedgerInclusionProof: {
      entry: components['schemas']['LedgerEntry'];
      leafHash: components['schemas']['LedgerHashHex'];
      tenantPath: components['schemas']['MerkleProofStep'][];
      tenantRoot: components['schemas']['LedgerHashHex'];
      cellLeafIndex?: number;
      cellPath?: components['schemas']['MerkleProofStep'][];
      seal: components['schemas']['LedgerSeal'];
      /** @description Chữ ký gốc cell bằng khoá SVC-09. */
      signature?: string;
      signerCertSha256?: components['schemas']['LedgerHashHex'];
      /** @description Token RFC 3161. */
      tsaToken?: string;
      anchors?: components['schemas']['LedgerAnchorReceipt'][];
    };
    /** @description Khiếu nại trước đó nghi trùng nội dung để đối chiếu theo BR-12-11. */
    ComplaintDuplicateCandidate: {
      id: components['schemas']['Uuid'];
      code: string;
      subjectLine: string;
      status: components['schemas']['ComplaintStatus'];
      filedAt: components['schemas']['DateTime'];
      similarityScore: number;
      matchedTargets?: string[];
      conclusionSummary?: string | null;
    };
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
    /**
     * Format: uuid
     * @description Mã định danh UUIDv7 (cột `id` của mọi bảng, baseline mục 9).
     * @example 01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77
     */
    Uuid: string;
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
     * @description Mức xếp loại (baseline mục 9.1).
     * @enum {string}
     */
    RatingLevel: 'HTXSNV' | 'HTTNV' | 'HTNV' | 'KHTNV';
    /**
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
    /** @description Tham chiếu rút gọn tới một đối tượng kèm tên hiển thị. */
    Ref: {
      id: components['schemas']['Uuid'];
      name: string;
    };
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
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
    /**
     * @description Trạng thái kỳ `config.period.status` (baseline mục 9.1).
     * @enum {string}
     */
    PeriodStatus: 'MO' | 'DANG_KHOA' | 'DA_KHOA' | 'MO_LAI_HAU_KIEM';
  };
  responses: {
    /** @description OPA từ chối theo quy tắc tách vai trò PL 4 V.1 (BR-12-04): người gọi đã nghiệm thu, thẩm định, đề xuất hoặc quyết định hồ sơ bị khiếu nại. Mã `OVERSIGHT-403-SEPARATION-OF-DUTIES`, MSG-12-05. */
    SeparationOfDuties: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
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
    /** @description OPA từ chối theo RBAC/ABAC hoặc quy tắc tách vai trò (PL 4 V.1). */
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
    /** @description Xung đột. Hai nghĩa phân biệt bằng `code`: xung đột ghi đồng thời (`<MODULE>-409-VERSION-CONFLICT`, `type` kết thúc bằng `concurrent-modification`) hoặc vi phạm trạng thái nghiệp vụ (ví dụ `TASK-409-PERIOD-LOCKED`). Với xung đột ghi, giao diện giữ dữ liệu đang nhập và hiện khác biệt. */
    Conflict: {
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
  };
  parameters: {
    /** @description Mã kỳ đánh giá (`config.period.id`). */
    PeriodId: components['schemas']['Uuid'];
    /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
    Q: string;
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
    IdPath: components['schemas']['Uuid'];
    /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
    IfMatch: string;
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
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
  listComplainableProfiles: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách kết quả có thể khiếu nại. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['ComplainableProfile'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listComplaints: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Lọc theo một hoặc nhiều trạng thái. */
        status?: components['schemas']['ComplaintStatus'][];
        kind?: components['schemas']['ComplaintKind'];
        /** @description Chỉ khiếu nại có task xử lý hoặc ký đang giao cho người gọi. */
        assignedToMe?: boolean;
        /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
        q?: components['parameters']['Q'];
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
      /** @description Một trang khiếu nại. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['ComplaintSummary'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createComplaint: {
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
        'application/json': components['schemas']['ComplaintInput'];
      };
    };
    responses: {
      /** @description Đã lưu nháp. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Dữ liệu không hợp lệ. Mã: `OVERSIGHT-422-DEADLINE-PASSED` (quá hạn, chỉ còn `BAO_LUU`, MSG-12-01), `COMMON-422-VALIDATION`. */
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
  getComplaint: {
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
      /** @description Khiếu nại. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateComplaint: {
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
        'application/json': components['schemas']['ComplaintInput'];
      };
    };
    responses: {
      /** @description Đã lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
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
  submitComplaint: {
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
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột phiên bản (`OVERSIGHT-409-VERSION-CONFLICT`), khiếu nại đã gửi hoặc hồ sơ không còn ở `DA_KY_SO` (`OVERSIGHT-409-INVALID-STATE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Thiếu trường bắt buộc hoặc quá hạn. Mã: `OVERSIGHT-422-NO-TARGET` (MSG-12-03), `OVERSIGHT-422-DEADLINE-PASSED` (MSG-12-01), `COMMON-422-VALIDATION`. */
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
  withdrawComplaint: {
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
        'application/json': components['schemas']['OversightReasonInput'];
      };
    };
    responses: {
      /** @description Đã rút. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  reserveComplaintOpinion: {
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
        'application/json': {
          /** @description Ý kiến bảo lưu (`oversight.complaint.reserve_opinion_enc`). */
          opinion: string;
        };
      };
    };
    responses: {
      /** @description Đã lưu ý kiến bảo lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
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
  acceptComplaint: {
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
        'application/json': {
          /** @description Ghi chú tiếp nhận (suy luận). */
          note?: string;
        };
      };
    };
    responses: {
      /** @description Đã tiếp nhận. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['SeparationOfDuties'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  rejectComplaint: {
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
        'application/json': {
          /**
           * @description Nhóm lý do không thụ lý theo bảng trạng thái QT-12 (suy luận mã).
           * @enum {string}
           */
          reasonCode: 'QUA_HAN' | 'SAI_THAM_QUYEN' | 'TRUNG_NOI_DUNG';
          reason: string;
        };
      };
    };
    responses: {
      /** @description Đã ghi không thụ lý. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['SeparationOfDuties'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listComplaintDuplicateCandidates: {
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
      /** @description Danh sách khiếu nại tương tự của cùng người khiếu nại hoặc cùng nội dung mục tiêu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['ComplaintDuplicateCandidate'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  requestComplaintExplanations: {
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
        'application/json': {
          parties: components['schemas']['ExplanationParty'][];
          /** @description Nội dung yêu cầu giải trình bằng văn bản. */
          content: string;
          /** @description Hạn giải trình; bỏ trống thì máy chủ đặt 3 ngày làm việc theo lịch tenant. */
          dueDate?: components['schemas']['Date'];
        };
      };
    };
    responses: {
      /** @description Đã gửi yêu cầu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['SeparationOfDuties'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  submitComplaintExplanation: {
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
        'application/json': {
          /** @description Mã `complaint_response` loại `YEU_CAU` đang trả lời. */
          requestId: components['schemas']['Uuid'];
          content: string;
          attachmentObjectIds?: components['schemas']['Uuid'][];
        };
      };
    };
    responses: {
      /** @description Đã ghi giải trình. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ComplaintResponseRecord'];
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
  listComplaintResponses: {
    parameters: {
      query?: {
        recordKind?: components['schemas']['ComplaintRecordKind'];
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
      /** @description Một trang bản ghi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['ComplaintResponseRecord'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  saveComplaintConclusion: {
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
        'application/json': components['schemas']['ComplaintConclusionInput'];
      };
    };
    responses: {
      /** @description Đã ghi kết luận. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Complaint'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['SeparationOfDuties'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  respondComplaint: {
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
        'application/json': {
          /** @description Ý kiến người ký (suy luận). */
          comment?: string;
        };
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['SeparationOfDuties'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      /** @description Chưa có kết luận hoặc văn bản trả lời (`OVERSIGHT-422-CONCLUSION-MISSING`). */
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
  listReopenRequests: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        status?: components['schemas']['ReopenRequestStatus'][];
        /** @description Chỉ yêu cầu đang chờ chính người gọi duyệt. */
        awaitingMyApproval?: boolean;
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
      /** @description Một trang yêu cầu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['ReopenRequest'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createReopenRequest: {
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
        'application/json': components['schemas']['ReopenRequestInput'];
      };
    };
    responses: {
      /** @description Đã tạo yêu cầu. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReopenRequest'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Kỳ chưa khoá hoặc đã có yêu cầu đang mở cho cùng phạm vi (`OVERSIGHT-409-PERIOD-NOT-LOCKED`, `OVERSIGHT-409-INVALID-STATE`). */
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
  getReopenRequest: {
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
      /** @description Yêu cầu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReopenRequest'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  simulateReopenRequest: {
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
    requestBody?: {
      content: {
        'application/json': {
          /** @description Điều chỉnh giả định; bỏ trống thì lấy từ căn cứ (kết luận khiếu nại). */
          adjustments?: components['schemas']['ComplaintAdjustment'][];
        };
      };
    };
    responses: {
      /** @description Kết quả tính thử (chưa ghi). */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReopenImpact'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  approveReopenRequest: {
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
        'application/json': {
          /** @description Ý kiến người duyệt (`oversight.reopen_approval.comment`). */
          comment: string;
        };
      };
    };
    responses: {
      /** @description Đã ghi phê duyệt; trả yêu cầu với trạng thái mới. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReopenRequest'];
        };
      };
      /** @description Thiếu xác thực bước lên: `WWW-Authenticate` mang `error="insufficient_user_authentication", acr_values="aal2"` (RFC 9470, MSG-13-02). */
      401: {
        headers: {
          'WWW-Authenticate'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Vi phạm nguyên tắc bốn mắt hoặc tách vai trò (`OVERSIGHT-403-FOUR-EYES`, MSG-13-01): người duyệt trùng người tạo, trùng người duyệt lượt trước, đã tham gia thẩm định hoặc quyết định hồ sơ bị mở, hoặc không phải người được chỉ định cho lượt này. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  rejectReopenRequest: {
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
        'application/json': {
          comment: string;
        };
      };
    };
    responses: {
      /** @description Đã từ chối. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReopenRequest'];
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
  cancelReopenRequest: {
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
        'application/json': components['schemas']['OversightReasonInput'];
      };
    };
    responses: {
      /** @description Đã huỷ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ReopenRequest'];
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
  listPostAudits: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description true chỉ đợt chưa đóng, false chỉ đợt đã đóng. */
        open?: boolean;
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
      /** @description Một trang đợt hậu kiểm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['PostAudit'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createPostAudit: {
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
        'application/json': {
          periodId: components['schemas']['Uuid'];
          auditKind: components['schemas']['PostAuditKind'];
          targetType: components['schemas']['PostAuditTargetType'];
          targetId: components['schemas']['Uuid'];
          /** @description Căn cứ (số quyết định, mã phản ánh, mã cảnh báo). */
          basis?: string;
        };
      };
    };
    responses: {
      /** @description Đã tạo. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PostAudit'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getPostAudit: {
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
      /** @description Đợt hậu kiểm. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PostAudit'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  closePostAudit: {
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
        'application/json': {
          conclusion: components['schemas']['PostAuditConclusion'];
          findings: string;
          reopenRequestId?: components['schemas']['Uuid'];
          /** @description Tệp bằng chứng xuất từ sổ cái (`POST /ledger/exports`). */
          evidenceObjectIds?: components['schemas']['Uuid'][];
        };
      };
    };
    responses: {
      /** @description Đã đóng. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PostAudit'];
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
  listAnomalyAlerts: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        signal?: components['schemas']['AnomalySignalCode'][];
        severity?: components['schemas']['AnomalySeverity'][];
        status?: components['schemas']['AnomalyAlertStatus'][];
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
      /** @description Một trang cảnh báo và số đếm phân bố. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['AnomalyAlertSummary'][];
            nextCursor: components['schemas']['NextCursor'];
            counts?: components['schemas']['AnomalyAlertCounts'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getAnomalyAlert: {
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
      /** @description Cảnh báo. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnomalyAlert'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createAlertAction: {
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
        'application/json': components['schemas']['AlertActionInput'];
      };
    };
    responses: {
      /** @description Đã ghi hành động; trả cảnh báo với trạng thái mới. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnomalyAlert'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description OPA từ chối: người xử lý là đối tượng của cảnh báo (BR-14-08) hoặc không phải `inspector` khi kết luận (BR-14-02). Mã `OVERSIGHT-403-SEPARATION-OF-DUTIES`, `COMMON-403-OUT-OF-SCOPE`. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listLedgerEntries: {
    parameters: {
      query?: {
        /** @description Loại đối tượng (`ledger_entry.aggregate_type`), ví dụ `appraisal_profile`. */
        aggregateType?: string;
        aggregateId?: components['schemas']['Uuid'];
        from?: components['schemas']['DateTime'];
        to?: components['schemas']['DateTime'];
        /** @description Topic sự kiện, ví dụ `product.evidence.accepted.v1`. */
        eventType?: string;
        chainPartition?: number;
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
      /** @description Một trang bản ghi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['LedgerEntry'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Khoảng thời gian quá 2 năm (`LEDGER-422-RANGE-TOO-LONG`). */
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
  getLedgerEntry: {
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
      /** @description Bản ghi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['LedgerEntryDetail'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listLedgerVerifyRuns: {
    parameters: {
      query?: {
        verifyKind?: components['schemas']['LedgerVerifyKind'];
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
      /** @description Một trang lần kiểm tra. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['LedgerVerifyRun'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createLedgerVerifyRun: {
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
        'application/json': {
          verifyKind: components['schemas']['LedgerVerifyKind'];
          /** @description Bỏ trống để kiểm tra cả 64 phân vùng của tenant. */
          chainPartition?: number;
          rangeFrom?: components['schemas']['DateTime'];
          rangeTo?: components['schemas']['DateTime'];
        };
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
  getLedgerVerifyRun: {
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
      /** @description Lần kiểm tra. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['LedgerVerifyRun'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createLedgerExport: {
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
        'application/json': {
          aggregateType?: string;
          aggregateId?: components['schemas']['Uuid'];
          eventType?: string;
          from: components['schemas']['DateTime'];
          to: components['schemas']['DateTime'];
          /** @description Đính kèm kết quả một lần kiểm tra toàn vẹn (suy luận). */
          includeVerifyRunId?: components['schemas']['Uuid'];
        };
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
  getLedgerInclusionProof: {
    parameters: {
      query: {
        /** @description Mã sự kiện nguồn (`ledger_entry.event_id`). */
        event: components['schemas']['Uuid'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bằng chứng bao hàm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['LedgerInclusionProof'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Sự kiện chưa được niêm phong (`LEDGER-409-NOT-SEALED`); thử lại sau cửa sổ niêm phong kế tiếp. */
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
}
