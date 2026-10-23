/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/appraisals.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/appraisals/profiles/mine': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Hồ sơ đánh giá của tôi
     * @description Danh sách hồ sơ đánh giá của người đang đăng nhập theo kỳ và luồng (thẻ "Tự đánh giá quý" trên SCR-0002, menu Đánh giá › Phiếu tự đánh giá). Thay cho `GET /appraisals?period=&flow=` của TL-01 Phụ lục C (điểm chốt AP-03).
     */
    get: operations['listMyAppraisalProfiles'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách hồ sơ đánh giá theo đơn vị (hàng đợi thẩm định, quyết định)
     * @description Hàng đợi thẩm định (SCR-0902, mặc định `status=CHO_THAM_DINH`, sắp theo hạn) và danh sách hồ sơ chờ quyết định (SCR-0904, `status=CHO_QUYET_DINH`) trong phạm vi ABAC cây tổ chức của người gọi. Mỗi dòng có bốn mức đã đưa ra và gợi ý bất thường (dấu hiệu số 1 của QT-14) để người dùng không phải mở từng hồ sơ. Phân bố mức trên SCR-0904 tính ở client từ danh sách.
     */
    get: operations['listAppraisalProfiles'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết hồ sơ đánh giá
     * @description Chi tiết hồ sơ gồm trạng thái, tiêu chí chung, phiếu tự đánh giá hiện hành, vết bốn mức và thông tin chữ ký. Trường C2 chỉ trả cho người được xem theo OPA; việc đọc trường C2 được ghi nhật ký truy cập. Hồ sơ năm có thêm `quarterRatings`. Trả `ETag` dùng cho mọi thao tác ghi lên hồ sơ.
     */
    get: operations['getAppraisalProfile'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/self-assessment': {
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
     * Lưu nháp phiếu tự đánh giá
     * @description Lưu tiêu chí chung, mức tự đề xuất, giải pháp đột phá, giải trình và nội dung ưu điểm, hạn chế (trường 2, 9 đến 12 của SCR-0901). Lưu nháp không kiểm tra đủ trường bắt buộc; kiểm tra đầy đủ chạy khi nộp. Giao diện tự lưu 30 giây một lần, mỗi lần dùng `Idempotency-Key` mới và `If-Match` mới nhất. Chỉ chủ hồ sơ, khi hồ sơ ở `TU_DANH_GIA`.
     */
    put: operations['saveAppraisalSelfAssessment'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/submit': {
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
     * Ký số và nộp phiếu tự đánh giá
     * @description Kiểm tra đủ trường và quy tắc (giải trình khi mức tự đề xuất khác mức tham khảo, giải pháp đột phá kèm minh chứng `DA_XAC_NHAN` khi tự đề xuất HTXSNV), chốt `content_hash` của phiếu, tạo yêu cầu ký ở SVC-10 rồi trả 202. Hồ sơ chỉ chuyển `CHO_THAM_DINH` khi nhận EVT-18 (BR-09-15); trong lúc chờ, hồ sơ giữ `TU_DANH_GIA`. Thay cho `POST /appraisals/{id}/self-assessment` của TL-01 Phụ lục C (điểm chốt AP-01). Lỗi 422 mã `APPRAISAL-422-REASON-REQUIRED` (MSG-09-02), `APPRAISAL-422-BREAKTHROUGH-REQUIRED` (MSG-09-03); ký lỗi báo qua job với MSG-09-05. Theo dõi ký: `Location` trỏ `/jobs/{id}` (loại `ESIGN`, suy luận); job ở `DANG_CHAY` trong lúc chờ người ký xác nhận trên ứng dụng ký số, chuyển `HOAN_TAT` khi nhận EVT-18 và hồ sơ đã sang `CHO_THAM_DINH`, với `resultLocation` là `/appraisals/profiles/{id}`; chuyển `THAT_BAI` kèm `problem` (MSG-09-05) khi ký lỗi, bị từ chối hoặc hết hạn, hồ sơ giữ `TU_DANH_GIA` và nộp lại được với `Idempotency-Key` mới. Client không gọi `POST /esign/requests` riêng.
     */
    post: operations['submitAppraisalSelfAssessment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/return': {
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
     * Trả lại hồ sơ để cán bộ bổ sung
     * @description Thẩm định viên trả hồ sơ từ `CHO_THAM_DINH` về `TU_DANH_GIA`, giữ nguyên các bản ghi cũ, hoàn thành user task với `ket_qua = TRA_LAI`. Bắt buộc lý do. Vượt số lần trả lại tối đa (tham số, mặc định 2) trả 409 mã `APPRAISAL-409-RETURN-LIMIT`. Thành công hiện MSG-09-09.
     */
    post: operations['returnAppraisalProfile'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/appraisal': {
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
     * Ký số kết quả thẩm định và chuyển đề xuất
     * @description Ghi bản ghi mức `THAM_DINH` kèm nhận xét (C2), lý do khi khác mức tự đề xuất (BR-09-12, MSG-09-06), tạo yêu cầu ký và trả 202. Hồ sơ chuyển `DA_THAM_DINH` khi nhận EVT-18. OPA từ chối người thẩm định là người được đánh giá hoặc đã nghiệm thu một nhiệm vụ của hồ sơ (BR-09-14, 403 kèm `msg` MSG-09-08). Thay cho `POST /appraisals/{id}/review` của TL-01 Phụ lục C (điểm chốt AP-01). Cũng dùng cho thẩm định năm (SCR-0902 chế độ năm).
     */
    post: operations['appraiseAppraisalProfile'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/proposal': {
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
     * Ghi mức đề xuất của quản lý trực tiếp
     * @description Quản lý trực tiếp (AC-05) ghi bản ghi mức `DE_XUAT` khi hồ sơ ở `DA_THAM_DINH`; khác mức thẩm định thì bắt buộc lý do (MSG-09-12, 422 `APPRAISAL-422-REASON-REQUIRED`). Hoàn thành user task, hồ sơ chuyển `CHO_QUYET_DINH`. Bước này không ký số (TL-03 `ck_rating_proposal__signed`).
     */
    post: operations['proposeAppraisalRating'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/scores': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Điểm, ảnh chụp điểm và giải thích xếp loại
     * @description Trả ảnh chụp điểm hiện hành và câu giải thích của DMN cho hồ sơ. Tham số `flow` cho phép chuyển thẻ luồng: khi khác luồng của hồ sơ, máy chủ trả ảnh chụp của hồ sơ cùng người, cùng kỳ ở luồng kia (mỗi luồng là một hồ sơ riêng, TL-03). Hồ sơ `CHUA_LAP` chưa có ảnh chụp trả 409 mã `APPRAISAL-409-NOT-COMPUTED` (MSG-09-01). Thay cho `GET /appraisals/{id}/score` của TL-01 Phụ lục C (điểm chốt AP-04).
     */
    get: operations['getAppraisalScores'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/score-trace': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Vết tính điểm của hồ sơ
     * @description Các bước tính của SVC-07 (`scoring.score_trace`) cho kết quả gắn với ảnh chụp hiện hành: công thức, giá trị vào, giá trị ra, điều khoản, câu giải thích; dùng cho ngăn "Vết tính" (A, B, C từng nhiệm vụ) và thẻ Vết tính. Khoảng 40 bước mỗi kết quả nên không phân trang.
     */
    get: operations['getAppraisalScoreTrace'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/simulate': {
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
     * Tính thử điểm với điều chỉnh đề nghị (không lưu)
     * @description Gọi SVC-07 ở chế độ mô phỏng với các điều chỉnh cấu phần do người xử lý khiếu nại đề nghị, dùng Policy Pack của kỳ; không ghi ảnh chụp, không đổi trạng thái (BR-09-16). Kết quả chỉ để hiển thị trên SCR-1202. Cán bộ kiểm tra gọi gián tiếp qua `simulateReopenRequest` của MOD-07 để xem ảnh hưởng dự kiến của yêu cầu mở lại kỳ (QT-13 bước 4, BR-13-06).
     */
    post: operations['simulateAppraisalScore'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/profiles/{id}/recompute': {
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
     * Tính lại và đối chiếu ảnh chụp điểm
     * @description Giám sát (AC-10) yêu cầu SVC-07 tính lại từ `scoring.score_input_v` với đúng phiên bản quy tắc đã dùng và so băm kết quả với ảnh chụp hiện hành (tiêu chí I6). Không ghi đè ảnh chụp; kết quả đối chiếu nằm ở `resultLocation` của job (suy luận về dạng kết quả).
     */
    post: operations['recomputeAppraisalProfile'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/score-preview': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xem trước A, B, C, KPI của một nhiệm vụ khi nghiệm thu (không lưu)
     * @description Người nghiệm thu chọn mức chất lượng B rồi xem trước A, B, C, KPI theo Policy Pack của kỳ (BR-09-01). SVC-07 tính, không lưu kết quả.
     */
    post: operations['previewAppraisalTaskScore'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/exports': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xuất danh sách hàng đợi thẩm định ra XLSX
     * @description Tạo tác vụ xuất danh sách hồ sơ theo bộ lọc đang dùng; tệp không chứa trường C2. Kết quả tải qua `resultLocation` của job.
     */
    post: operations['exportAppraisalProfiles'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/inputs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Đầu vào tính điểm theo lô cho scoring-service
     * @description Client nội bộ SVC-07 đọc danh sách hồ sơ của kỳ cùng thuộc tính cần cho tính điểm (luồng, khối đối tượng, người đứng đầu, phiên bản Policy Pack, kết quả tiêu chí chung). Không dành cho giao diện. Các trường cụ thể là suy luận từ TL-03 `appraisal_profile`.
     */
    get: operations['listAppraisalScoringInputs'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/inputs/{profileId}/work-items': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Hồ sơ đánh giá (`appraisal_profile.id`). */
        profileId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    /**
     * Đọc tiếp việc lá của một hồ sơ cho scoring-service
     * @description Phân trang con trỏ con cho hồ sơ có hơn 200 việc lá (BS-03 DC-01); con trỏ đầu lấy từ `workItemsNextCursor` của `GET /appraisals/inputs`. Chỉ dành cho client nội bộ SVC-07.
     */
    get: operations['listAppraisalWorkItems'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/incidents': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách sự việc có kết luận
     * @description Sự việc đã ghi của người hoặc của kỳ, mới nhất trước (BS-03 DC-05).
     */
    get: operations['listAppraisalIncidents'];
    put?: never;
    /**
     * Ghi một sự việc có kết luận
     * @description Cơ quan tổ chức cán bộ ghi sự việc đã có kết luận của cấp có thẩm quyền; điều kiện loại trừ của gói đọc bảng này qua đầu vào tính điểm. Ghi vào kỳ đã khoá trả `APPRAISAL-409-PERIOD-LOCKED`; loại sự việc không có trong danh mục của hồ sơ trả `APPRAISAL-422-INCIDENT-TYPE` (suy luận).
     */
    post: operations['createAppraisalIncident'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/incidents/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Xem một sự việc */
    get: operations['getAppraisalIncident'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/batch-decisions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách lô quyết định của người quyết định
     * @description Các lô do chính người gọi ký (`appraisal.batch_decision.decider_id` là người gọi), mới nhất trước. SCR-0904 gọi với `status=CHO_KY` khi mở màn hình để hiện lại tiến độ của lô đang ký, vì người quyết định được phép đóng màn hình trong lúc ký (MSG-09-15).
     */
    get: operations['listAppraisalBatchDecisions'];
    put?: never;
    /**
     * Quyết định xếp loại và ký lô
     * @description Tạo `appraisal.batch_decision` cho 1 đến 1.000 hồ sơ `CHO_QUYET_DINH`, ghi bản ghi mức `QUYET_DINH` cho từng hồ sơ (đổi mức so với mức đề xuất thì bắt buộc lý do ≥ 20 ký tự), yêu cầu xác thực bước lên một lần và tạo yêu cầu ký lô ở SVC-10 (mỗi hồ sơ một chữ ký PAdES). Trả 202 với `Location: /api/v1/jobs/{id}`; thân 202 có `batchId` (mã lô) và `resultLocation` (đường dẫn lô) ngay từ lúc nhận, để thao tác "Huỷ lô" dùng được khi lô đang ký; tiến độ theo SSE. Mỗi chữ ký thành công phát EVT-18 và hồ sơ chuyển `DA_KY_SO`, kèm EVT-12. Hồ sơ lỗi ký giữ `CHO_QUYET_DINH`. Quyết định một hồ sơ cũng đi qua đây (lô 1 hồ sơ, điểm chốt AP-02).
     *
     *     Lỗi: 409 `APPRAISAL-409-DECISION-NO-DUPLICATE` khi trùng số quyết định trong năm của đơn vị; 422 `APPRAISAL-422-HEAD-TOO-EARLY` (MSG-09-14, BR-09-13); 422 `APPRAISAL-422-REASON-REQUIRED`; 409 `APPRAISAL-409-COMPLAINT-OPEN` (MSG-10-03) khi xếp loại năm còn khiếu nại quý chưa kết luận; 422 `APPRAISAL-422-CALIBRATION-REQUIRED` (MSG-09-13, BR-09-17) khi tỷ lệ HTXSNV vượt ngưỡng khối mà chưa xác nhận đã có phiên hiệu chỉnh. Mục có `rowVersion` lệch phiên bản hiện tại bị từ chối cả lô với 409 `APPRAISAL-409-VERSION-CONFLICT`, `errors[]` chỉ rõ mục.
     */
    post: operations['createAppraisalBatchDecision'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/batch-decisions/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết lô quyết định
     * @description Trạng thái lô, số hồ sơ đã ký, lỗi từng hồ sơ. Đường dẫn suy luận: TL-02b chỉ ghi theo dõi qua `GET /jobs/{id}`, nhưng cần đọc lô để lấy `ETag` cho thao tác huỷ và để mở lại màn hình sau khi đóng.
     */
    get: operations['getAppraisalBatchDecision'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/batch-decisions/{id}/cancel': {
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
     * Huỷ lô quyết định chưa hoàn tất
     * @description Dừng các chữ ký chưa thực hiện; hồ sơ đã ký giữ `DA_KY_SO`, hồ sơ chưa ký giữ `CHO_QUYET_DINH`. Lô chuyển `HUY` nếu chưa ký hồ sơ nào, ngược lại `LOI_MOT_PHAN` (suy luận cách chọn trạng thái). Lô đã `DA_KY` trả 409 `APPRAISAL-409-BATCH-COMPLETED`.
     */
    post: operations['cancelAppraisalBatchDecision'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/annual': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tổng hợp xếp loại năm của đơn vị
     * @description Bảng tổng hợp bốn quý, điểm năm, mức tự đề xuất năm, mức hệ thống đề xuất và quy tắc liên thông của từng hồ sơ năm, kèm bốn số đếm cho thẻ số liệu đầu trang (trả riêng trong `counts` theo SCR-1001 trường 3). `flag=lien_thong` chỉ hiện hồ sơ có `blockReason` hoặc `reassignFlag`.
     */
    get: operations['listAnnualAppraisalRatings'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/annual/{id}/explanation': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Giải thích liên thông quý–năm của một hồ sơ năm
     * @description `id` là mã `appraisal.annual_rating`. Trả công thức điểm năm theo tham số `nam.cach_tinh`, bốn kết quả quý với phiên bản Policy Pack đã dùng (BR-10-06), danh sách điều kiện, mức hệ thống đề xuất và câu giải thích có điều khoản (MSG-10-01).
     */
    get: operations['getAnnualAppraisalExplanation'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/annual/{id}/reassignment-proposal': {
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
     * Lập đề xuất bố trí lại công việc
     * @description Với hồ sơ năm có `reassignFlag` (hai quý liên tiếp KHTNV, QĐ khoản 15.6), tạo việc cho cơ quan tổ chức trong MOD-02 với căn cứ khoản 15.6 và đặt `relocationProposed = true`. Không thay đổi mức xếp loại. Hồ sơ không có cờ trả 409 `APPRAISAL-409-NO-REASSIGN-FLAG`; đã lập trả 409 `APPRAISAL-409-REASSIGN-EXISTS`. `If-Match` dùng `rowVersion` của dòng xếp loại năm.
     */
    post: operations['createAnnualReassignmentProposal'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/prospect-groups/{periodId}/{orgUnitId}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã kỳ năm. */
        periodId: components['parameters']['PeriodIdPath'];
        /** @description Mã đơn vị (`org.org_unit.id`). */
        orgUnitId: components['parameters']['OrgUnitIdPath'];
      };
      cookie?: never;
    };
    /**
     * Bảng phân nhóm triển vọng của đơn vị trong kỳ năm
     * @description Danh sách phân nhóm hiện hành (gồm nhóm gợi ý của hệ thống) của một đơn vị trong kỳ năm. Đường dẫn GET là suy luận: TL-02b chỉ ghi PUT và submit, nhưng màn hình cần tải bảng và ETag. Gọi ở kỳ quý trả 409 `APPRAISAL-409-PROSPECT-QUARTER` (MSG-10-05, BR-10-07). Dữ liệu C2, chỉ `leader` và `appraiser` của đơn vị (BR-10-10).
     */
    get: operations['getProspectGroupBoard'];
    /**
     * Lưu nháp danh sách phân nhóm triển vọng
     * @description Lưu toàn bộ danh sách (nhóm, thứ tự trong nhóm, lý do khi khác gợi ý, kế hoạch bồi dưỡng). Kiểm tra: thứ tự không trùng trong nhóm; người KHTNV năm hoặc có cờ bố trí lại không vào `NHOM_1`, `NHOM_2` (422 `APPRAISAL-422-PROSPECT-NOT-ELIGIBLE`, MSG-10-06, BR-10-09). Lưu nháp không bắt buộc lý do: mục khác gợi ý mà chưa có lý do vẫn lưu được, giao diện chỉ nhắc MSG-10-07; lý do (khi có) dài 20 đến 1.000 ký tự. Thiếu lý do chỉ chặn ở bước trình (`.../submit`, 422 `APPRAISAL-422-REASON-REQUIRED`). Chỉ khi danh sách ở `NHAP`.
     */
    put: operations['saveProspectGroupBoard'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/appraisals/prospect-groups/{periodId}/{orgUnitId}/submit': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã kỳ năm. */
        periodId: components['parameters']['PeriodIdPath'];
        /** @description Mã đơn vị (`org.org_unit.id`). */
        orgUnitId: components['parameters']['OrgUnitIdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Trình tập thể lãnh đạo danh sách phân nhóm
     * @description Khởi động user task thông qua tập thể và khoá chỉnh sửa; danh sách chuyển `DA_TRINH`. Thiếu lý do cho điều chỉnh khác gợi ý trả 422. Việc thông qua và ký số danh sách (bước 9 QT-10) do workflow-service và esign-service xử lý, không có đường dẫn riêng ở đây.
     */
    post: operations['submitProspectGroupBoard'];
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
     * @description Trạng thái hồ sơ đánh giá `appraisal.appraisal_profile.status` (baseline mục 9.1).
     * @enum {string}
     */
    AppraisalProfileStatus:
      | 'CHUA_LAP'
      | 'TU_DANH_GIA'
      | 'CHO_THAM_DINH'
      | 'DA_THAM_DINH'
      | 'CHO_QUYET_DINH'
      | 'DA_QUYET_DINH'
      | 'DA_KY_SO'
      | 'DA_KHOA'
      | 'KHIEU_NAI';
    /**
     * @description Loại kỳ (`config.period.kind`, TL-03).
     * @enum {string}
     */
    AppraisalPeriodKind: 'THANG' | 'QUY' | 'NAM' | 'NAM_HOC';
    /**
     * @description Loại mức trong `appraisal.rating_proposal.level_kind` (TL-03).
     * @enum {string}
     */
    RatingLevelKind: 'TU_DE_XUAT' | 'THAM_DINH' | 'DE_XUAT' | 'QUYET_DINH';
    /**
     * @description Mức tiêu chí chung (`appraisal.general_criteria_result.level`, BR-09-05).
     * @enum {string}
     */
    GeneralCriteriaLevel: 'THUC_HIEN_TOT' | 'DAM_BAO' | 'CO_BAN_DAM_BAO' | 'KHONG_DAM_BAO';
    /**
     * @description Bước ghi kết quả tiêu chí chung (`general_criteria_result.stage`).
     * @enum {string}
     */
    GeneralCriteriaStage: 'TU_DANH_GIA' | 'THAM_DINH';
    /**
     * @description Loại ảnh chụp điểm (`score_snapshot.snapshot_kind`).
     * @enum {string}
     */
    ScoreSnapshotKind: 'TAM_TINH' | 'CHOT_KY' | 'TINH_LAI_HAU_KIEM';
    /**
     * @description Loại giải trình (`rating_explanation.explanation_type`).
     * @enum {string}
     */
    RatingExplanationType: 'HA_MUC' | 'KHAC_MUC_TRUOC' | 'BAO_LUU';
    /**
     * @description Trạng thái ký lô (`appraisal.batch_decision.status`).
     * @enum {string}
     */
    AppraisalBatchDecisionStatus: 'NHAP' | 'CHO_KY' | 'DA_KY' | 'LOI_MOT_PHAN' | 'HUY';
    /**
     * @description Nhóm triển vọng theo PL 5 (`prospect_group.group_code`); dữ liệu C2 dạng rõ có kiểm soát.
     * @enum {string}
     */
    ProspectGroupCode: 'NHOM_1' | 'NHOM_2' | 'NHOM_3' | 'NHOM_4';
    /**
     * @description Trạng thái danh sách phân nhóm của một đơn vị trong kỳ (suy luận: TL-02b nêu "nháp", "trình tập thể", "đã ký" và việc không thông qua đưa về nháp; TL-03 chưa có cột trạng thái).
     * @enum {string}
     */
    ProspectBoardStatus: 'NHAP' | 'DA_TRINH' | 'DA_KY';
    /**
     * @description Nhà cung cấp chữ ký số (`esign` provider, TL-03).
     * @enum {string}
     */
    AppraisalSignProvider: 'MYSIGN' | 'VIETTEL_CA' | 'CA_CHINH_PHU' | 'BAN_CO_YEU';
    /**
     * @description Băm SHA-256 dạng hex chữ thường (64 ký tự).
     * @example 9f2c4e1a7b3d5f60a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718
     */
    AppraisalHash: string;
    /** @description Bốn mức đã đưa ra cho một hồ sơ (dòng hiện hành theo từng `level_kind`); `null` khi chưa có. */
    AppraisalRatingLevels: {
      selfProposed?: components['schemas']['RatingLevel'] | null;
      appraised?: components['schemas']['RatingLevel'] | null;
      proposed?: components['schemas']['RatingLevel'] | null;
      decided?: components['schemas']['RatingLevel'] | null;
    };
    /** @description Gợi ý bất thường từ QT-14 hiển thị trên hàng đợi; chỉ để lưu ý, không đổi kết quả. */
    AppraisalAnomalyHint: {
      /**
       * @description Mã dấu hiệu, ví dụ `DAU_HIEU_1`
       * @example DAU_HIEU_1
       */
      code: string;
      /** @example Tự chấm cao 3 quý */
      label: string;
      alertId?: components['schemas']['Uuid'];
    };
    /** @description Một dòng danh sách hồ sơ (hàng đợi thẩm định, quyết định, hồ sơ của tôi). */
    AppraisalProfileRow: {
      id: components['schemas']['Uuid'];
      person: components['schemas']['Ref'];
      /** @description Chức vụ (`org_replica.person_v`) */
      jobTitle?: string;
      orgUnit: components['schemas']['Ref'];
      period: components['schemas']['PeriodRef'];
      periodKind: components['schemas']['AppraisalPeriodKind'];
      flow: components['schemas']['Flow'];
      status: components['schemas']['AppraisalProfileStatus'];
      /** @description Người đứng đầu (BR-09-13) */
      isHead?: boolean;
      /** @description Khối đối tượng của hồ sơ (`appraisal_profile.subject_group_id`); vắng khi hồ sơ không gắn khối. */
      subjectGroup?: components['schemas']['Ref'];
      /** @description Ngưỡng tỷ lệ HTXSNV tối đa của khối (tham số `phan_bo.toi_da_htxsnv` trong manifest của phiên bản Policy Pack gắn với hồ sơ, `appraisal_profile.policy_pack_version_id`), dạng tỉ lệ 0..1. SCR-0904 so phân bố mức với ngưỡng này (trường 5, BR-09-17, MSG-09-13); vắng thì dùng mặc định 0,2 của TL-02b. Máy chủ vẫn kiểm lại khi ký lô. */
      maxExcellentShare?: number;
      /** @description Điểm của ảnh chụp hiện hành */
      totalScore?: number | null;
      /** @description Mức hệ thống tính (tham khảo). */
      autoRatingCode?: components['schemas']['RatingLevel'] | null;
      levels: components['schemas']['AppraisalRatingLevels'];
      /** @description Có mức khác bước trước và đã giải trình (cột ghi chú SCR-0904) */
      differsExplained?: boolean;
      /** @description Quá hạn không tự đánh giá, chuyển thẩm định với mức tự đề xuất trống (QT-09 ngoại lệ 3) */
      noSelfAssessment?: boolean;
      dueDate?: components['schemas']['Date'];
      anomalyHints?: components['schemas']['AppraisalAnomalyHint'][];
      rowVersion: components['schemas']['RowVersion'];
    };
    AppraisalProfilePage: {
      items: components['schemas']['AppraisalProfileRow'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Kết quả một nhóm tiêu chí chung (`appraisal.general_criteria_result`). */
    GeneralCriteriaItem: {
      /** @example TCC-01 */
      criteriaCode: string;
      /**
       * @description Tên tiêu chí lấy từ Policy Pack (suy luận)
       * @example Bản lĩnh chính trị, tư tưởng
       */
      label?: string;
      met: boolean;
      /** @description Nhận xét theo tiêu chí (C2). */
      comment?: string;
    };
    /** @description Tiêu chí chung của một bước, kèm tỷ lệ đáp ứng và mức quy đổi (BR-09-05). */
    GeneralCriteriaResult: {
      stage: components['schemas']['GeneralCriteriaStage'];
      items: components['schemas']['GeneralCriteriaItem'][];
      complianceRatio: number;
      level: components['schemas']['GeneralCriteriaLevel'];
    };
    /** @description B, C cán bộ tự nhận cho một nhiệm vụ (`self_assessment.claims_json`), dùng cho bảng so sánh trên SCR-0902 (cấu trúc suy luận). */
    AppraisalTaskClaim: {
      taskId: components['schemas']['Uuid'];
      qualityLevel?: components['schemas']['QualityLevel'];
      delayDays?: number;
      /** @description Hệ số C quy đổi từ `delayDays` theo bảng tiến độ của Policy Pack hồ sơ đang ghim; máy chủ tính, client không gửi (SCR-0902 trường 5, suy luận tên trường). */
      readonly delayFactor?: number;
    };
    /** @description Phiếu tự đánh giá hiện hành (`appraisal.self_assessment` cùng mức `TU_DE_XUAT`). */
    AppraisalSelfAssessment: {
      /** @description Lần nộp; nộp lại sau trả lại tạo phiên bản mới */
      versionNo: number;
      /** @example MAU_2 */
      formCode?: string;
      /** @example 1.0 */
      formVersion?: string;
      selfRatingCode?: components['schemas']['RatingLevel'] | null;
      /** @description Giải pháp đột phá (`breakthrough_enc`). */
      breakthrough?: string;
      breakthroughEvidenceIds?: components['schemas']['Uuid'][];
      /** @description Giải trình khác mức tham khảo (`rating_proposal.reason_enc`). */
      reason?: string;
      /** @description Ưu điểm, hạn chế, hướng khắc phục (`content_enc`). */
      content?: string;
      /** @description Ưu điểm (`strengths_enc`) */
      strengths?: string;
      /** @description Hạn chế (`weaknesses_enc`) */
      weaknesses?: string;
      /** @description Hướng khắc phục (`remedial_plan_enc`) */
      remedialPlan?: string;
      claims?: components['schemas']['AppraisalTaskClaim'][];
      /** @description Điểm tự tính theo B, C cán bộ tự nhận, thẻ "Điểm tự tính (theo minh chứng nộp)" của SCR-0902 trường 4. TL-02b ghi nguồn `appraisal.score_snapshot`; SVC-07 tính bằng cùng công thức Policy Pack với điểm chính thức nhưng thay B, C bằng `claims` khi chốt phiếu; `null` khi phiếu chưa có `claims` (suy luận tên trường và cách lưu). */
      readonly claimedScore?: number | null;
      contentHash?: components['schemas']['AppraisalHash'];
      savedAt?: components['schemas']['DateTime'];
      submittedAt?: components['schemas']['DateTime'];
      signedAt?: components['schemas']['DateTime'];
    };
    /** @description Thông tin chữ ký của một mốc (SCR-0902 trường 3). */
    AppraisalSignature: {
      signedAt: components['schemas']['DateTime'];
      provider: components['schemas']['AppraisalSignProvider'];
      signRequestId?: components['schemas']['Uuid'];
      contentHash?: components['schemas']['AppraisalHash'];
      /** @description Kết quả kiểm tra chữ ký khi mở */
      valid?: boolean;
    };
    /** @description Một mức do con người đưa ra (`appraisal.rating_proposal`). */
    AppraisalRatingProposal: {
      id: components['schemas']['Uuid'];
      levelKind: components['schemas']['RatingLevelKind'];
      ratingCode: components['schemas']['RatingLevel'];
      actor: components['schemas']['Ref'];
      /**
       * @description Vai trò khi đưa ra mức (TL-03 `actor_role`, giữ đúng giá trị CHECK).
       * @enum {string}
       */
      actorRole: 'staff' | 'appraiser' | 'manager' | 'decider';
      isCurrent: boolean;
      differsFromPrev?: boolean;
      /** @description Mức khác `autoRatingCode` của ảnh chụp `scoreSnapshotId` (BS-03 DC-05); khi true thì có lý do. */
      differsFromSuggested?: boolean;
      explanationRequired?: boolean;
      isBulkDecision?: boolean;
      batchDecisionId?: components['schemas']['Uuid'];
      scoreSnapshotId?: components['schemas']['Uuid'];
      /** @description Lý do đưa ra mức (`reason_enc`). */
      reason?: string;
      /** @description Nhận xét (`comment_enc`). */
      comment?: string;
      proposedAt: components['schemas']['DateTime'];
      signedAt?: components['schemas']['DateTime'];
    };
    /** @description Kết quả một quý dùng cho hồ sơ năm. */
    AppraisalQuarterRating: {
      period: components['schemas']['PeriodRef'];
      ratingCode: components['schemas']['RatingLevel'] | null;
      totalScore?: number | null;
      /** @example KHOI_DANG v1.4 */
      policyVersion?: string;
      snapshotId?: components['schemas']['Uuid'];
      inputHash?: components['schemas']['AppraisalHash'];
    };
    /** @description Hồ sơ đánh giá của một người, một kỳ, một luồng (`appraisal.appraisal_profile`). */
    AppraisalProfile: {
      id: components['schemas']['Uuid'];
      person: components['schemas']['Ref'];
      jobTitle?: string;
      orgUnit: components['schemas']['Ref'];
      period: components['schemas']['PeriodRef'];
      periodKind: components['schemas']['AppraisalPeriodKind'];
      flow: components['schemas']['Flow'];
      /** @description Các luồng tenant bật cho người này trong kỳ (thẻ luồng SCR-0901). */
      availableFlows: components['schemas']['Flow'][];
      status: components['schemas']['AppraisalProfileStatus'];
      isHead?: boolean;
      isLeadership?: boolean;
      /**
       * @description Nhãn phiên bản Policy Pack gắn với hồ sơ
       * @example KHOI_DANG v2.0
       */
      policyVersion?: string;
      currentSnapshotId?: components['schemas']['Uuid'];
      finalRatingCode?: components['schemas']['RatingLevel'] | null;
      selfAssessDue?: components['schemas']['Date'];
      /** @description Số ngày làm việc còn lại tới hạn nộp, tính theo lịch nghỉ của tenant */
      workingDaysLeft?: number;
      /** @description Số lần đã trả lại (suy luận, phục vụ MSG-09-09) */
      returnCount?: number;
      /** @description Số lần trả lại tối đa theo tham số luồng (suy luận) */
      returnLimit?: number;
      noSelfAssessment?: boolean;
      complaintDeadline?: components['schemas']['Date'];
      submittedAt?: components['schemas']['DateTime'];
      decidedAt?: components['schemas']['DateTime'];
      lockedAt?: components['schemas']['DateTime'];
      appraiser?: components['schemas']['Ref'];
      decider?: components['schemas']['Ref'];
      /**
       * Format: uuid
       * @description Mã `dossier.form_instance` (trạng thái `DA_KY`) của phiếu Mẫu 1B hoặc Mẫu 2 của hồ sơ đã ký kèm quyết định, để tải bằng `GET /dossiers/forms/{id}/file` (nút "Tải Mẫu 1B (PDF/A)" của SCR-0903); `null` khi chưa có quyết định hoặc chưa ký xong.
       */
      readonly signedFormId?: string | null;
      /** @description Mã mẫu của `signedFormId` (`dossier.form_instance.form_code`), ví dụ `MAU_1B`, `MAU_2`. */
      readonly signedFormCode?: string | null;
      /** @description Kết quả tiêu chí chung theo từng bước đã có. */
      generalCriteria?: components['schemas']['GeneralCriteriaResult'][];
      selfAssessment?: components['schemas']['AppraisalSelfAssessment'] | null;
      submissionSignature?: components['schemas']['AppraisalSignature'];
      /** @description Vết các mức (mọi dòng `rating_proposal`, gồm dòng không còn hiện hành), theo thời gian. */
      ratingTrail: components['schemas']['AppraisalRatingProposal'][];
      /** @description Chỉ có ở hồ sơ năm; bốn kết quả quý chỉ đọc. */
      quarterRatings?: components['schemas']['AppraisalQuarterRating'][];
      anomalyHints?: components['schemas']['AppraisalAnomalyHint'][];
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Nội dung lưu nháp phiếu tự đánh giá; mọi trường tuỳ chọn khi lưu nháp. */
    AppraisalSelfAssessmentInput: {
      criteria?: {
        criteriaCode: string;
        met: boolean;
      }[];
      selfRatingCode?: components['schemas']['RatingLevel'];
      /** @description Bắt buộc khi nộp với HTXSNV, kèm ít nhất một minh chứng `DA_XAC_NHAN`. */
      breakthrough?: string;
      breakthroughEvidenceIds?: components['schemas']['Uuid'][];
      /** @description Bắt buộc 20 đến 2.000 ký tự khi nộp nếu mức tự đề xuất khác mức tham khảo. */
      reason?: string;
      /** @description Bắt buộc khi nộp. */
      content?: string;
      strengths?: string;
      weaknesses?: string;
      remedialPlan?: string;
      claims?: components['schemas']['AppraisalTaskClaim'][];
    };
    /** @description Tham số tạo yêu cầu ký khi nộp phiếu. */
    AppraisalSignRequestInput: {
      signProvider?: components['schemas']['AppraisalSignProvider'];
      /**
       * @description Biểu mẫu sinh bản ký (Mẫu 1B hoặc Mẫu 2); bỏ trống thì theo khối đối tượng.
       * @example MAU_2
       */
      formCode?: string;
      /** @description Băm phiếu client đã xem trước; lệch với máy chủ trả 409 (suy luận, chống ký nội dung khác). */
      contentHash?: components['schemas']['AppraisalHash'];
      /** @description Ảnh chụp điểm người gọi đã xem (BS-03 DC-05); khi mức khác `autoRatingCode` của ảnh chụp này thì bắt buộc `reason` (422 `APPRAISAL-422-SUGGESTED-REASON-REQUIRED`). Bỏ trống thì máy chủ dùng ảnh chụp mới nhất. */
      seenScoreSnapshotId?: components['schemas']['Uuid'];
    };
    AppraisalReturnInput: {
      /** @description Lý do trả lại (độ dài suy luận theo quy ước lý do của TL-02b). */
      reason: string;
    };
    /** @description Kết luận thẩm định (SCR-0902 trường 6 đến 8). */
    AppraisalReviewInput: {
      ratingCode: components['schemas']['RatingLevel'];
      comment: string;
      /** @description Bắt buộc khi mức thẩm định khác mức tự đề xuất (BR-09-12) hoặc khác mức hệ thống gợi ý (BS-03 DC-05). */
      reason?: string;
      /** @description Ảnh chụp điểm người gọi đã xem (BS-03 DC-05); khi mức khác `autoRatingCode` của ảnh chụp này thì bắt buộc `reason` (422 `APPRAISAL-422-SUGGESTED-REASON-REQUIRED`). Bỏ trống thì máy chủ dùng ảnh chụp mới nhất. */
      seenScoreSnapshotId?: components['schemas']['Uuid'];
      /** @description Kết quả tiêu chí chung ở bước thẩm định nếu khác tự đánh giá (suy luận từ `stage = THAM_DINH`). */
      generalCriteria?: {
        criteriaCode: string;
        met: boolean;
      }[];
      signProvider?: components['schemas']['AppraisalSignProvider'];
    };
    AppraisalProposalInput: {
      ratingCode: components['schemas']['RatingLevel'];
      /** @description Bắt buộc khi khác mức thẩm định (MSG-09-12) hoặc khác mức hệ thống gợi ý (BS-03 DC-05). */
      reason?: string;
      comment?: string;
      /** @description Ảnh chụp điểm người gọi đã xem (BS-03 DC-05); khi mức khác `autoRatingCode` của ảnh chụp này thì bắt buộc `reason` (422 `APPRAISAL-422-SUGGESTED-REASON-REQUIRED`). Bỏ trống thì máy chủ dùng ảnh chụp mới nhất. */
      seenScoreSnapshotId?: components['schemas']['Uuid'];
    };
    /** @description Một điều kiện của mức xếp loại, đạt hay chưa (`rule_checks`, `conditions_json`). */
    AppraisalRuleCheck: {
      ratingCode: components['schemas']['RatingLevel'];
      /** @example ≥ 30% nhiệm vụ vượt mục tiêu */
      label: string;
      met: boolean;
      /**
       * @description Giá trị thực tế đã định dạng
       * @example 22%
       */
      actual?: string;
      /** @example 30% */
      threshold?: string;
      /** @example QĐ Điều 11 */
      legalRef?: string;
    };
    /** @description Một lý do hạ mức (`downgrade_reasons`). */
    AppraisalDowngradeReason: {
      fromRating: components['schemas']['RatingLevel'];
      toRating: components['schemas']['RatingLevel'];
      text: string;
      legalRef?: string;
    };
    /** @description Một nhiệm vụ trong bảng kết quả (`group_scores_json`, `detail_json`). */
    AppraisalScoreTaskLine: {
      taskId: components['schemas']['Uuid'];
      title: string;
      approvedQuantity?: number;
      plannedQuantity?: number;
      a?: number;
      b?: components['schemas']['QualityLevel'];
      c?: number;
      kpi: number;
      exceedsTarget?: boolean;
      /** @description Lần nghiệm thu quyết định B, C của dòng (`product.acceptance.id`). */
      acceptanceId?: components['schemas']['Uuid'];
      /** @description Căn cứ điều chỉnh B, C so với tự nhận, ghép từ `product.acceptance` (ví dụ "B: phải sửa nội dung cơ bản (biên bản nghiệm thu 18/12)"); SCR-0902 trường 5 (suy luận tên trường). */
      acceptanceNote?: string;
      /** @description Minh chứng đã được nghiệm thu, mở bằng `GET /evidences/{id}/versions/{v}`. */
      evidenceId?: components['schemas']['Uuid'];
      /** @description Số phiên bản minh chứng đã nghiệm thu, đi cùng `evidenceId`. */
      evidenceVersionNo?: number;
    };
    /** @description Một nhóm nhiệm vụ (then chốt, thường xuyên) với KPI nhóm và điểm (BR-09-03). */
    AppraisalScoreGroup: {
      /** @example THEN_CHOT */
      code: string;
      /** @example Nhiệm vụ then chốt */
      label: string;
      kpi: number;
      maxScore: number;
      score: number;
      tasks?: components['schemas']['AppraisalScoreTaskLine'][];
    };
    /** @description Ảnh chụp điểm bất biến do SVC-07 tính (`appraisal.score_snapshot`); chỉ đọc. */
    AppraisalScoreSnapshot: {
      id: components['schemas']['Uuid'];
      profileId: components['schemas']['Uuid'];
      flow: components['schemas']['Flow'];
      snapshotKind: components['schemas']['ScoreSnapshotKind'];
      scoreResultId?: components['schemas']['Uuid'];
      policyPackVersionId?: components['schemas']['Uuid'];
      policyVersion: string;
      policyContentHash?: components['schemas']['AppraisalHash'];
      inputHash: components['schemas']['AppraisalHash'];
      snapshotHash: components['schemas']['AppraisalHash'];
      totalScore: number;
      regularScore?: number | null;
      keyTaskScore?: number | null;
      /** @description Luồng NHA_NUOC: phần 30 điểm */
      generalCriteriaScore?: number | null;
      /** @description Luồng NHA_NUOC: phần 70 điểm */
      kpiScore?: number | null;
      /** @description Tỷ lệ nhiệm vụ vượt mục tiêu (TL-02b gọi `over_target_ratio`) */
      exceedRatio?: number | null;
      objectiveRatio?: number | null;
      generalCriteriaLevel?: components['schemas']['GeneralCriteriaLevel'] | null;
      autoRatingCode: components['schemas']['RatingLevel'];
      ruleChecks: components['schemas']['AppraisalRuleCheck'][];
      downgradeReasons: components['schemas']['AppraisalDowngradeReason'][];
      groups: components['schemas']['AppraisalScoreGroup'][];
      computedAt: components['schemas']['DateTime'];
      supersededById?: components['schemas']['Uuid'];
    };
    /** @description Câu giải thích của hệ thống (`appraisal.rating_explanation`, BR-09-10). */
    AppraisalRatingExplanation: {
      explanationType?: components['schemas']['RatingExplanationType'];
      systemLevel: components['schemas']['RatingLevel'];
      conditions: components['schemas']['AppraisalRuleCheck'][];
      /** @description Câu giải thích sinh từ mẫu câu DMN (`auto_text`) */
      text?: string;
      legalRef?: string;
    };
    /** @description Điểm và giải thích cho SCR-0901, SCR-0903. */
    AppraisalScoreView: {
      profileId: components['schemas']['Uuid'];
      flow: components['schemas']['Flow'];
      snapshot: components['schemas']['AppraisalScoreSnapshot'];
      explanation?: components['schemas']['AppraisalRatingExplanation'];
    };
    /** @description Một bước tính (`scoring.score_trace`). */
    AppraisalScoreTraceStep: {
      stepNo: number;
      /** @example kpi_nhiem_vu */
      ruleCode: string;
      legalRef?: string;
      taskId?: components['schemas']['Uuid'];
      inputs: {
        [key: string]: unknown;
      };
      output: {
        [key: string]: unknown;
      };
      explanation?: string;
    };
    AppraisalScoreTrace: {
      profileId: components['schemas']['Uuid'];
      scoreResultId: components['schemas']['Uuid'];
      snapshotId?: components['schemas']['Uuid'];
      policyVersion?: string;
      /** @example scoring 1.8.2 */
      engineVersion?: string;
      steps: components['schemas']['AppraisalScoreTraceStep'][];
    };
    AppraisalSimulationInput: {
      adjustments: {
        /**
         * @description Cấu phần điều chỉnh, ví dụ A, B, C hoặc tiêu chí chung
         * @example C
         */
        component: string;
        taskId?: components['schemas']['Uuid'];
        oldValue?: number;
        newValue: number;
        reason?: string;
      }[];
    };
    AppraisalScoreOutcome: {
      totalScore: number;
      ratingCode: components['schemas']['RatingLevel'];
    };
    /** @description Kết quả tính thử, không lưu. */
    AppraisalSimulationResult: {
      policyVersion?: string;
      before: components['schemas']['AppraisalScoreOutcome'];
      after: components['schemas']['AppraisalScoreOutcome'];
      explanation?: string;
    };
    AppraisalScorePreviewInput: {
      taskId: components['schemas']['Uuid'];
      evidenceVersionId?: components['schemas']['Uuid'];
      qualityLevel: components['schemas']['QualityLevel'];
      revisionCause?: components['schemas']['RevisionCause'];
      /** @description Chỉnh sửa do nguyên nhân khách quan (ảnh hưởng cách tính C); cùng nghĩa với `EvidenceAcceptRequest`. */
      isObjectiveRevision?: boolean;
    };
    AppraisalScorePreview: {
      a: number;
      b: components['schemas']['QualityLevel'];
      c: number;
      kpi: number;
      policyVersion?: string;
    };
    AppraisalExportInput: {
      periodId: components['schemas']['Uuid'];
      unitId?: components['schemas']['Uuid'];
      flow?: components['schemas']['Flow'];
      status?: components['schemas']['AppraisalProfileStatus'][];
      /** @enum {string} */
      format: 'XLSX';
    };
    /** @description Đầu vào hồ sơ cho SVC-07 (suy luận từ `appraisal_profile`, `general_criteria_result`). */
    AppraisalScoringInput: {
      profileId: components['schemas']['Uuid'];
      personId: components['schemas']['Uuid'];
      flow: components['schemas']['Flow'];
      orgUnitId: components['schemas']['Uuid'];
      subjectGroupId?: components['schemas']['Uuid'];
      isHead?: boolean;
      isLeadership?: boolean;
      policyPackVersionId: components['schemas']['Uuid'];
      generalCriteria?: {
        criteriaCode: string;
        met: boolean;
      }[];
      /** @description Việc lá của người trong kỳ (BS-03 DC-01), chỉ có khi Policy Pack khai báo đầu vào `work_items` (nguồn `task.aggregate.work_items`); tối đa 200 phần tử, phần còn lại đọc tiếp bằng `workItemsNextCursor`. */
      workItems?: components['schemas']['WorkItem'][];
      /** @description Con trỏ đọc tiếp việc lá ở `GET /appraisals/inputs/{profileId}/work-items`; null khi đã đủ. */
      workItemsNextCursor?: string | null;
      /** @description Phạm vi phụ trách của người giữ chức vụ lãnh đạo, quản lý (BS-03 DC-03), chỉ có khi gói khai báo; tính theo tổ chức tại thời điểm khoá kỳ. */
      responsibilityScope?: components['schemas']['ResponsibilityItem'][];
      /** @description Sự việc có kết luận trong kỳ của người (BS-03 DC-05), chỉ có khi gói khai báo điều kiện loại trừ. */
      incidents?: components['schemas']['AppraisalIncidentInput'][];
    };
    /** @description Một việc lá đưa vào tính điểm (BS-03 DC-01). Lõi chỉ cung cấp đại lượng thô và mức hệ số; công thức, giá trị hệ số và cách đưa về thang 100 nằm trong Policy Pack. */
    WorkItem: {
      taskId: components['schemas']['Uuid'];
      /** @description Việc cha trong cây phân rã (suy luận, cần cho chế độ `MOT_NGUOI_MOT_VIEC`). */
      parentTaskId?: components['schemas']['Uuid'] | null;
      /** @description Điểm chuẩn của việc. */
      standardScore: number;
      /** @description Mức khó năm mức. */
      difficulty?: number;
      /** @description Mức của từng loại hệ số mở (`coefficient_levels`), khoá là mã loại hệ số của gói. */
      coefficientLevels: {
        [key: string]: string;
      };
      /**
       * @description Nhóm tính điểm (`task_group`).
       * @enum {string}
       */
      taskGroup?: 'THUONG_XUYEN' | 'THEN_CHOT';
      /** @description Mã trục (`axis_code`). */
      axisCode?: string | null;
      /** @description Điểm thành phần khi hồ sơ dùng `MOT_NGUOI_MOT_VIEC`. */
      componentScore?: number | null;
      /**
       * @description Trạng thái nhiệm vụ tại thời điểm khoá kỳ.
       * @enum {string}
       */
      statusAtLock:
        | 'NHAP'
        | 'DA_GIAO'
        | 'DANG_THUC_HIEN'
        | 'CHO_NGHIEM_THU'
        | 'HOAN_THANH'
        | 'TAM_DUNG'
        | 'HUY';
      /** @description Mức chất lượng nghiệm thu; null khi chưa nghiệm thu. */
      qualityLevel?: components['schemas']['QualityLevel'] | null;
      /**
       * @description Số ngày làm việc chậm do nguyên nhân chủ quan.
       * @default 0
       */
      subjectiveLateWorkdays: number;
      /**
       * @description Số ngày làm việc chậm do nguyên nhân khách quan.
       * @default 0
       */
      objectiveLateWorkdays: number;
      /**
       * @description Vượt mục tiêu.
       * @default false
       */
      exceeded: boolean;
      /**
       * @description Việc ngoài phạm vi được giao.
       * @default false
       */
      outOfScope: boolean;
      /**
       * @description Lùi hạn hoặc dừng theo quyết định của cấp có thẩm quyền.
       * @default false
       */
      deferredByDecision: boolean;
    };
    AppraisalWorkItemPage: {
      items: components['schemas']['WorkItem'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Một mục người lãnh đạo, quản lý phụ trách (BS-03 DC-03). Các số tính trên chính mục đó, không cộng dồn hai lần phần giảm của cấp dưới. */
    ResponsibilityItem: {
      /**
       * @description Loại mục phụ trách.
       * @enum {string}
       */
      itemType: 'KPI' | 'NHIEM_VU' | 'KR' | 'DON_VI';
      itemId: components['schemas']['Uuid'];
      /** @description Tổng S đã tổng hợp của mục (đặt tên theo BS-03; suy luận). */
      sumS?: number;
      /** @description Tổng Q đã tổng hợp của mục (suy luận). */
      sumQ?: number;
      /** @description Tổng D đã tổng hợp của mục (suy luận). */
      sumD?: number;
      /** @description Tỷ lệ đạt chỉ tiêu (r). */
      targetAchievedRatio?: number | null;
      /** @description Mức chất lượng được xác nhận (m). */
      confirmedLevel?: components['schemas']['QualityLevel'] | null;
      /** @description Số ngày làm việc chậm của chính mục (n). */
      ownLateWorkdays?: number;
    };
    /** @description Sự việc có kết luận đưa vào tính điểm (rút gọn của `AppraisalIncident`). */
    AppraisalIncidentInput: {
      incidentId: components['schemas']['Uuid'];
      incidentType: string;
      concludedOn: components['schemas']['Date'];
      impact?: string;
    };
    /** @description Sự việc đã có kết luận của cấp có thẩm quyền (`appraisal.incident`, BS-03 DC-05). Loại sự việc và tác động lấy từ danh mục của hồ sơ tập khách hàng. */
    AppraisalIncident: {
      id: components['schemas']['Uuid'];
      personId: components['schemas']['Uuid'];
      periodId?: components['schemas']['Uuid'];
      /** @description Mã loại sự việc theo danh mục của hồ sơ. */
      incidentType: string;
      concludedOn: components['schemas']['Date'];
      /** @description Số văn bản kết luận. */
      documentNo: string;
      /** @description Tham chiếu văn bản đã lưu. */
      documentRef?: string;
      /** @description Mã tác động theo danh mục của hồ sơ, ví dụ `LOAI_TRU` (suy luận). */
      impact?: string;
      /** @description Tóm tắt kết luận. */
      summary?: string;
      createdBy?: components['schemas']['Ref'];
      createdAt?: components['schemas']['DateTime'];
      rowVersion: components['schemas']['RowVersion'];
    };
    AppraisalIncidentCreate: {
      personId: components['schemas']['Uuid'];
      periodId?: components['schemas']['Uuid'];
      incidentType: string;
      concludedOn: components['schemas']['Date'];
      documentNo: string;
      documentRef?: string;
      impact?: string;
      summary?: string;
    };
    AppraisalIncidentPage: {
      items: components['schemas']['AppraisalIncident'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    AppraisalScoringInputPage: {
      items: components['schemas']['AppraisalScoringInput'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    AppraisalBatchDecisionItem: {
      profileId: components['schemas']['Uuid'];
      ratingCode: components['schemas']['RatingLevel'];
      /** @description Bắt buộc khi khác mức đề xuất hoặc khác mức hệ thống gợi ý (BS-03 DC-05). */
      reason?: string;
      /** @description Ảnh chụp điểm người gọi đã xem (BS-03 DC-05); khi mức khác `autoRatingCode` của ảnh chụp này thì bắt buộc `reason` (422 `APPRAISAL-422-SUGGESTED-REASON-REQUIRED`). Bỏ trống thì máy chủ dùng ảnh chụp mới nhất. */
      seenScoreSnapshotId?: components['schemas']['Uuid'];
      /** @description Phiên bản hồ sơ người quyết định đã xem (thay cho `If-Match` từng hồ sơ trong lô). */
      rowVersion: components['schemas']['RowVersion'];
    };
    AppraisalBatchDecisionInput: {
      periodId: components['schemas']['Uuid'];
      flow: components['schemas']['Flow'];
      /** @description Số ký hiệu quyết định, không trùng trong năm của đơn vị */
      decisionNo: string;
      decisionDate: components['schemas']['Date'];
      signProvider?: components['schemas']['AppraisalSignProvider'];
      /**
       * @description Xác nhận đã tổ chức phiên hiệu chỉnh khi tỷ lệ HTXSNV vượt ngưỡng (BR-09-17; trường suy luận).
       * @default false
       */
      calibrationAcknowledged: boolean;
      items: components['schemas']['AppraisalBatchDecisionItem'][];
    };
    /** @description Lô quyết định (`appraisal.batch_decision`). */
    AppraisalBatchDecision: {
      id: components['schemas']['Uuid'];
      period: components['schemas']['PeriodRef'];
      flow: components['schemas']['Flow'];
      decisionNo: string;
      decisionDate: components['schemas']['Date'];
      decider: components['schemas']['Ref'];
      profileCount: number;
      profilesHash?: components['schemas']['AppraisalHash'];
      signRequestId?: components['schemas']['Uuid'];
      /** @description Mã job theo dõi tiến độ (suy luận) */
      jobId?: string;
      status: components['schemas']['AppraisalBatchDecisionStatus'];
      /** @description Số hồ sơ đã ký (suy luận, từ `esign.sign_item`) */
      signedCount?: number;
      failedCount?: number;
      /** @description Hồ sơ lỗi ký hoặc bị từ chối. */
      failedItems?: components['schemas']['BatchItemResult'][];
      rowVersion: components['schemas']['RowVersion'];
    };
    AppraisalBatchDecisionPage: {
      items: components['schemas']['AppraisalBatchDecision'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Job ký lô trả trong thân 202 của `POST /appraisals/batch-decisions`: lược đồ `Job` dùng chung cộng mã lô. Khác job thông thường, `resultLocation` trỏ tới lô (`/api/v1/appraisals/batch-decisions/{id}`) ngay từ khi nhận, không chờ `HOAN_TAT`. */
    AppraisalBatchDecisionJob: components['schemas']['Job'] & {
      /** @description Mã lô `appraisal.batch_decision.id`, dùng cho "Huỷ lô" và `GET /appraisals/batch-decisions/{id}`. */
      batchId?: components['schemas']['Uuid'];
    };
    /** @description Một dòng xếp loại năm (`appraisal.annual_rating`). */
    AnnualRatingRow: {
      id: components['schemas']['Uuid'];
      profileId: components['schemas']['Uuid'];
      person: components['schemas']['Ref'];
      orgUnit?: components['schemas']['Ref'];
      flow: components['schemas']['Flow'];
      profileStatus?: components['schemas']['AppraisalProfileStatus'];
      /** @description Mức quyết định của các quý theo thứ tự; `null` khi quý không xếp loại. */
      quarterRatings: (components['schemas']['RatingLevel'] | null)[];
      annualScore?: number | null;
      selfProposedLevel?: components['schemas']['RatingLevel'] | null;
      systemLevel?: components['schemas']['RatingLevel'] | null;
      /** @description Mức năm đã quyết định. */
      ratingCode?: components['schemas']['RatingLevel'] | null;
      hasKhtnvQuarter: boolean;
      consecutiveKhtnv: boolean;
      /** @example CO_QUY_KHTNV */
      blockReason?: string | null;
      reassignFlag: boolean;
      relocationProposed: boolean;
      /** @description Câu ngắn cột "Quy tắc liên thông", viết bằng lời nghiệp vụ để hiển thị nguyên văn; máy chủ không đưa mã quy tắc, mã thông báo, tên tham số hay giá trị mã (như `CO_QUY_KHTNV`) vào câu này. */
      linkageNote?: string;
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Số đếm cho bốn thẻ số liệu SCR-1001. */
    AnnualRatingCounts: {
      profilesCreated: number;
      profilesTotal: number;
      /** @description Bị chặn HTXSNV do có quý KHTNV */
      blockedExcellent: number;
      reassignProposed: number;
      awaitingProspectGrouping: number;
    };
    AnnualRatingPage: {
      items: components['schemas']['AnnualRatingRow'][];
      nextCursor: components['schemas']['NextCursor'];
      counts?: components['schemas']['AnnualRatingCounts'];
      /** @description Dòng phụ ghi bộ quy định đánh giá và phiên bản áp cho các quý, viết bằng lời nghiệp vụ để hiển thị nguyên văn; máy chủ không đưa mã quy tắc, tên tham số hay cụm "Policy Pack" vào câu này. */
      policyNote?: string;
    };
    AnnualRatingExplanation: {
      annualRatingId: components['schemas']['Uuid'];
      /**
       * @description Giá trị tham số `nam.cach_tinh`
       * @example TRUNG_BINH_QUY
       */
      yearCalcMethod?: string;
      /** @example (91,8 + 88,6 + 90,2 + 91,2) / 4 = 90,4 */
      formula?: string;
      annualScore?: number | null;
      exceedRatio?: number | null;
      quarters: components['schemas']['AppraisalQuarterRating'][];
      conditions: components['schemas']['AppraisalRuleCheck'][];
      systemLevel: components['schemas']['RatingLevel'];
      text?: string;
      legalRef?: string;
      policyVersion?: string;
      inputHash?: components['schemas']['AppraisalHash'];
    };
    AnnualReassignmentInput: {
      note?: string;
    };
    AnnualReassignmentResult: {
      annualRatingId: components['schemas']['Uuid'];
      taskId: components['schemas']['Uuid'];
      relocationProposed: boolean;
    };
    /** @description Một cán bộ trên bảng phân nhóm (`appraisal.prospect_group`). */
    ProspectGroupEntry: {
      id?: components['schemas']['Uuid'];
      person: components['schemas']['Ref'];
      /** @description Tóm tắt thẻ: chức vụ, số năm HTXSNV hoặc lý do */
      summary?: string;
      annualRatingCode?: components['schemas']['RatingLevel'] | null;
      reassignFlag?: boolean;
      groupCode: components['schemas']['ProspectGroupCode'];
      suggestedGroup?: components['schemas']['ProspectGroupCode'] | null;
      rankInGroup?: number | null;
      reason?: string;
      trainingPlanRef?: string;
      decidedAt?: components['schemas']['DateTime'];
    };
    /** @description Danh sách phân nhóm triển vọng của một đơn vị trong kỳ năm. */
    ProspectGroupBoard: {
      periodId: components['schemas']['Uuid'];
      orgUnit: components['schemas']['Ref'];
      status: components['schemas']['ProspectBoardStatus'];
      /** @description Gói quy định áp cho danh sách (`policy.policy_pack.id` của phiên bản gắn với hồ sơ năm), để mở tiêu chí bốn nhóm qua `GET /policies/packs/{id}/prospect-criteria` (SCR-1002 "Xem tiêu chí PL 5"). */
      policyPackId?: components['schemas']['Uuid'];
      policyVersion?: string;
      /** @description Lý do tập thể không thông qua lần trước (QT-10 ngoại lệ 4). */
      rejectionReason?: string;
      entries: components['schemas']['ProspectGroupEntry'][];
      /** @description Phiên bản của cả danh sách (suy luận); gửi lại trong `If-Match`. */
      rowVersion: components['schemas']['RowVersion'];
    };
    ProspectGroupBoardInput: {
      entries: {
        personId: components['schemas']['Uuid'];
        groupCode: components['schemas']['ProspectGroupCode'];
        rankInGroup: number;
        /** @description Lý do khi nhóm khác gợi ý (BR-10-08). Không bắt buộc khi lưu nháp; bắt buộc trước khi trình tập thể (`.../submit` trả 422 nếu còn thiếu). */
        reason?: string;
        /** @description Tham chiếu kế hoạch bồi dưỡng, tuỳ chọn (SCR-1002 trường 4, `prospect_group.training_plan_ref`). */
        trainingPlanRef?: string;
      }[];
    };
    /**
     * Format: uuid
     * @description Mã định danh UUIDv7 (cột `id` của mọi bảng, baseline mục 9).
     * @example 01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77
     */
    Uuid: string;
    /**
     * @description Luồng đánh giá (baseline mục 9.1).
     * @enum {string}
     */
    Flow: 'DANG' | 'NHA_NUOC';
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
     * @description Mức xếp loại (baseline mục 9.1).
     * @enum {string}
     */
    RatingLevel: 'HTXSNV' | 'HTTNV' | 'HTNV' | 'KHTNV';
    /**
     * @description Giá trị `row_version` (khoá lạc quan). Cùng giá trị với ETag, gửi lại trong `If-Match`.
     * @example 7
     */
    RowVersion: number;
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
    /**
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
    /**
     * @description Mức chất lượng nghiệm thu năm mức (baseline mục 9.1).
     * @enum {number}
     */
    QualityLevel: 1 | 0.75 | 0.5 | 0.25 | 0;
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
     * @description Nguyên nhân chỉnh sửa minh chứng khi nghiệm thu (`product.acceptance.revision_cause`, CHECK của TL-03), dùng chung cho nghiệm thu (MOD-03) và xem trước, tính điểm (MOD-04, SVC-07). Tính chủ quan hay khách quan của việc chỉnh sửa (TL-02 SCR-0703 trường 4 "Chủ quan, Khách quan, Không có") đi kèm ở cờ `isObjectiveRevision`; "Không có" là bỏ trống cả hai.
     * @enum {string}
     */
    RevisionCause:
      | 'THE_THUC'
      | 'NOI_DUNG_KHONG_COT_LOI'
      | 'NOI_DUNG_CO_BAN'
      | 'SAI_SOT_NGHIEM_TRONG'
      | 'SAI_CHU_TRUONG';
    /** @description Kết quả từng mục của thao tác lô; mục lỗi không làm hỏng mục khác. */
    BatchItemResult: {
      /** @description Mã đối tượng của mục */
      id: string;
      /** @description Mã HTTP tương đương cho mục này */
      status: number;
      problem?: components['schemas']['ProblemDetails'];
    };
  };
  responses: {
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
    /** @description Luồng đánh giá; bỏ trống thì lấy luồng của hồ sơ hoặc mọi luồng tenant bật. */
    FlowQuery: components['schemas']['Flow'];
    /** @description Mã kỳ năm. */
    PeriodIdPath: components['schemas']['Uuid'];
    /** @description Mã đơn vị (`org.org_unit.id`). */
    OrgUnitIdPath: components['schemas']['Uuid'];
    /** @description Mã kỳ đánh giá (`config.period.id`). */
    PeriodId: components['schemas']['Uuid'];
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
    /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
    Q: string;
    IdPath: components['schemas']['Uuid'];
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
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
  listMyAppraisalProfiles: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Luồng đánh giá; bỏ trống thì lấy luồng của hồ sơ hoặc mọi luồng tenant bật. */
        flow?: components['parameters']['FlowQuery'];
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
      /** @description Danh sách hồ sơ của tôi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalProfilePage'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listAppraisalProfiles: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Luồng đánh giá; bỏ trống thì lấy luồng của hồ sơ hoặc mọi luồng tenant bật. */
        flow?: components['parameters']['FlowQuery'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Lọc theo trạng thái hồ sơ; lặp tham số để chọn nhiều giá trị. */
        status?: components['schemas']['AppraisalProfileStatus'][];
        /** @description Gồm cả cây đơn vị cấp dưới của `unit` (suy luận, phục vụ bộ lọc khối trên SCR-0904). */
        includeSubunits?: boolean;
        /** @description Khối đối tượng (`appraisal_profile.subject_group_id`, danh mục `GET /org/subject-groups`); bộ lọc "khối" của SCR-0904 trường 1. Bỏ trống là mọi khối trong phạm vi. */
        subjectGroup?: components['schemas']['Uuid'];
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
      /** @description Một trang hồ sơ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalProfilePage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getAppraisalProfile: {
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
      /** @description Hồ sơ đánh giá. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalProfile'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  saveAppraisalSelfAssessment: {
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
        'application/json': components['schemas']['AppraisalSelfAssessmentInput'];
      };
    };
    responses: {
      /** @description Đã lưu nháp; trả hồ sơ cập nhật. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalProfile'];
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
  submitAppraisalSelfAssessment: {
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
        'application/json': components['schemas']['AppraisalSignRequestInput'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  returnAppraisalProfile: {
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
        'application/json': components['schemas']['AppraisalReturnInput'];
      };
    };
    responses: {
      /** @description Đã trả lại; trả hồ sơ ở `TU_DANH_GIA`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalProfile'];
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
  appraiseAppraisalProfile: {
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
        'application/json': components['schemas']['AppraisalReviewInput'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  proposeAppraisalRating: {
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
        'application/json': components['schemas']['AppraisalProposalInput'];
      };
    };
    responses: {
      /** @description Đã ghi mức đề xuất; trả hồ sơ ở `CHO_QUYET_DINH`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalProfile'];
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
  getAppraisalScores: {
    parameters: {
      query?: {
        /** @description Luồng đánh giá; bỏ trống thì lấy luồng của hồ sơ hoặc mọi luồng tenant bật. */
        flow?: components['parameters']['FlowQuery'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Điểm và giải thích. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalScoreView'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  getAppraisalScoreTrace: {
    parameters: {
      query?: {
        /** @description Luồng đánh giá; bỏ trống thì lấy luồng của hồ sơ hoặc mọi luồng tenant bật. */
        flow?: components['parameters']['FlowQuery'];
        /** @description Chỉ lấy các bước liên quan một nhiệm vụ (suy luận, phục vụ ngăn vết tính của một dòng). */
        taskId?: components['schemas']['Uuid'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Vết tính. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalScoreTrace'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  simulateAppraisalScore: {
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
        'application/json': components['schemas']['AppraisalSimulationInput'];
      };
    };
    responses: {
      /** @description Kết quả tính thử. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalSimulationResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  recomputeAppraisalProfile: {
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
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  previewAppraisalTaskScore: {
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
        'application/json': components['schemas']['AppraisalScorePreviewInput'];
      };
    };
    responses: {
      /** @description Kết quả xem trước. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalScorePreview'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  exportAppraisalProfiles: {
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
        'application/json': components['schemas']['AppraisalExportInput'];
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
  listAppraisalScoringInputs: {
    parameters: {
      query: {
        /** @description Mã kỳ đánh giá. */
        period: components['schemas']['Uuid'];
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
      /** @description Một trang đầu vào. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalScoringInputPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  listAppraisalWorkItems: {
    parameters: {
      query?: {
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        /** @description Hồ sơ đánh giá (`appraisal_profile.id`). */
        profileId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang việc lá. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalWorkItemPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listAppraisalIncidents: {
    parameters: {
      query?: {
        /** @description Lọc theo người. */
        person?: components['schemas']['Uuid'];
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
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
      /** @description Một trang sự việc. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalIncidentPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createAppraisalIncident: {
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
        'application/json': components['schemas']['AppraisalIncidentCreate'];
      };
    };
    responses: {
      /** @description Đã ghi sự việc. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalIncident'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getAppraisalIncident: {
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
      /** @description Sự việc. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalIncident'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listAppraisalBatchDecisions: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Luồng đánh giá; bỏ trống thì lấy luồng của hồ sơ hoặc mọi luồng tenant bật. */
        flow?: components['parameters']['FlowQuery'];
        /** @description Lọc theo trạng thái lô; lặp tham số để chọn nhiều giá trị. */
        status?: components['schemas']['AppraisalBatchDecisionStatus'][];
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
      /** @description Một trang lô quyết định. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalBatchDecisionPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createAppraisalBatchDecision: {
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
        'application/json': components['schemas']['AppraisalBatchDecisionInput'];
      };
    };
    responses: {
      /** @description Đã nhận lô (trả trong 200 ms, NFR-03). Theo dõi bằng `Location` hoặc luồng SSE `/api/v1/notifications/stream`. */
      202: {
        headers: {
          Location: components['headers']['Location'];
          'Retry-After': components['headers']['RetryAfter'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalBatchDecisionJob'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getAppraisalBatchDecision: {
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
      /** @description Lô quyết định. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalBatchDecision'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  cancelAppraisalBatchDecision: {
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
      /** @description Đã huỷ phần chưa ký. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AppraisalBatchDecision'];
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
  listAnnualAppraisalRatings: {
    parameters: {
      query: {
        /** @description Mã kỳ năm (`config.period.kind = NAM`). */
        period: components['schemas']['Uuid'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Luồng đánh giá; bỏ trống thì lấy luồng của hồ sơ hoặc mọi luồng tenant bật. */
        flow?: components['parameters']['FlowQuery'];
        /** @description Lọc hồ sơ có cảnh báo liên thông quý–năm. */
        flag?: 'lien_thong';
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
      /** @description Một trang xếp loại năm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnnualRatingPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getAnnualAppraisalExplanation: {
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
      /** @description Giải thích. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnnualRatingExplanation'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createAnnualReassignmentProposal: {
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
        'application/json': components['schemas']['AnnualReassignmentInput'];
      };
    };
    responses: {
      /** @description Đã tạo việc đề xuất bố trí lại. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnnualReassignmentResult'];
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
  getProspectGroupBoard: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã kỳ năm. */
        periodId: components['parameters']['PeriodIdPath'];
        /** @description Mã đơn vị (`org.org_unit.id`). */
        orgUnitId: components['parameters']['OrgUnitIdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bảng phân nhóm. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ProspectGroupBoard'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  saveProspectGroupBoard: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã kỳ năm. */
        periodId: components['parameters']['PeriodIdPath'];
        /** @description Mã đơn vị (`org.org_unit.id`). */
        orgUnitId: components['parameters']['OrgUnitIdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ProspectGroupBoardInput'];
      };
    };
    responses: {
      /** @description Đã lưu nháp. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ProspectGroupBoard'];
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
  submitProspectGroupBoard: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã kỳ năm. */
        periodId: components['parameters']['PeriodIdPath'];
        /** @description Mã đơn vị (`org.org_unit.id`). */
        orgUnitId: components['parameters']['OrgUnitIdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đã trình. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ProspectGroupBoard'];
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
}
