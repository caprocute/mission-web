/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/okr.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/okr/objectives': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách mục tiêu theo kỳ và đơn vị
     * @description Trả các mục tiêu của đơn vị (hoặc của cá nhân) trong kỳ, kèm KR và tiến độ, phục vụ ba chế độ xem cây, bảng và bản đồ liên kết của SCR-0201. Máy chủ lọc theo phạm vi ABAC: thành viên đơn vị xem mục tiêu của đơn vị mình; `leader` xem cả cây cấp dưới. Khi `view=tree` mỗi mục tiêu kèm `keyResults` và `peerLinks`; khi `view=table` chỉ kèm `keyResults`; khi `view=map` nên dùng `GET /okr/alignment`. Màn hình SCR-0201 dựng cây bằng `GET /okr/tree` (một lần gọi kèm ngữ cảnh kỳ, căn cứ, mục tiêu cấp trên); thao tác này phục vụ danh sách phân trang và lọc.
     */
    get: operations['listOkrObjectives'];
    put?: never;
    /**
     * Tạo mục tiêu ở trạng thái nháp
     * @description Tạo mục tiêu `NHAP` (có thể kèm KR), liên kết căn cứ và mục tiêu cấp trên. Kỳ phải tồn tại và chưa khoá. Quy tắc chặn trình duyệt (BR-02-02, BR-02-04, BR-02-05, BR-02-07) không chặn lưu nháp; kết quả kiểm tra trả trong `checks`. Khi kỳ đã có từ 3 mục tiêu trở lên, phải có `overLimitReason` (BR-02-01, MSG-02-04). Mã `code` (`O{n}`) và mã KR do máy chủ sinh.
     */
    post: operations['createOkrObjective'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/copy-from-period': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Sao chép mục tiêu kỳ trước làm điểm khởi đầu
     * @description (suy luận) QT-02 bước 1 và MSG-02-02 cho phép sao chép mục tiêu kỳ trước nhưng TL không ghi API. Máy chủ tạo bản sao ở trạng thái `NHAP` trong kỳ đích, giữ căn cứ, bỏ giá trị hiện tại, check-in và liên kết cấp trên (người soạn chọn lại mục tiêu cấp trên của kỳ mới). Lỗi: `OKR-409-PERIOD-LOCKED` khi kỳ đích đã khoá.
     */
    post: operations['copyOkrObjectivesFromPeriod'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/export': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xuất mẫu báo cáo OKR
     * @description Sinh tệp theo mẫu báo cáo OKR của tenant cho đơn vị và kỳ, chạy nền và trả 202. TL-02 không ghi phương thức; hợp đồng chọn POST vì tạo tác vụ. Kết quả lấy qua `resultLocation` của job.
     */
    post: operations['exportOkrObjectives'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/approve-batch': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Duyệt hàng loạt các bộ OKR đủ điều kiện
     * @description Duyệt các bộ OKR "Đạt" (không cảnh báo) đã chọn, mỗi bộ một giao dịch; mục lỗi không làm hỏng mục khác. Trả 202; kết quả từng mục (`BatchItemResult`) nằm trong kết quả job. BR-02-08 áp cho từng bộ. Mỗi mục kèm `rowVersion` thay cho `If-Match`; lệch phiên bản thì mục đó lỗi 409. Mỗi bộ được duyệt phát EVT-01.
     */
    post: operations['approveOkrObjectivesBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Xem chi tiết một mục tiêu
     * @description Trả mục tiêu kèm KR, căn cứ, liên kết, thông tin luồng duyệt và kết quả kiểm tra tự động (`checks`, tính lại mỗi lần đọc, không lưu). Dùng cho SCR-0202 khi sửa và SCR-0203 khi xem chi tiết.
     */
    get: operations['getOkrObjective'];
    /**
     * Lưu nháp mục tiêu và toàn bộ KR
     * @description Ghi đè phần nội dung sửa được của mục tiêu ở trạng thái `NHAP`, kể cả danh sách KR (KR lưu cùng mục tiêu: KR có `id` được cập nhật, KR không có `id` được thêm, KR có trong bản cũ mà vắng mặt bị xoá). Giao diện gọi tự động mỗi 30 giây. Mục tiêu `CHO_DUYET` hoặc `DA_DUYET` không sửa tại chỗ (BR-02-10): trả 409 `OKR-409-INVALID-STATE`. Nội dung do AI gợi ý phải mang `contentSource` là `AI_ACCEPTED` hoặc `AI_EDITED` (BR-02-11). Lỗi: `OKR-409-VERSION-CONFLICT`, `OKR-409-PERIOD-LOCKED`, `OKR-409-INVALID-STATE`, `OKR-422-OVER-LIMIT-REASON-REQUIRED` (MSG-02-04).
     */
    put: operations['updateOkrObjective'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/{id}/submit': {
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
     * Trình phê duyệt mục tiêu
     * @description Kiểm tra lại toàn bộ quy tắc ở máy chủ, chuyển `NHAP` → `CHO_DUYET`, tạo tiến trình `duyet-okr` với người duyệt lấy từ `flowable.wf_binding`. Phản hồi trả người duyệt và hạn duyệt trong `approval` để hiển thị MSG-02-08. Lỗi chặn trả 422 `OKR-422-VALIDATION` với `errors[].rule` (BR-02-02, BR-02-04, BR-02-05, BR-02-07); mục tiêu cấp trên chưa duyệt trả 422 `OKR-422-PARENT-NOT-APPROVED` (MSG-02-03). Trạng thái sai trả 409 `OKR-409-INVALID-STATE`; kỳ khoá trả 409 `OKR-409-PERIOD-LOCKED`.
     */
    post: operations['submitOkrObjective'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/{id}/withdraw': {
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
     * Người soạn rút lại mục tiêu đang chờ duyệt
     * @description (suy luận) Bảng trạng thái QT-02 có chuyển `CHO_DUYET` → `NHAP` khi "người soạn rút lại" nhưng TL không ghi API. Máy chủ huỷ nhiệm vụ duyệt đang mở của tiến trình `duyet-okr` và đưa mục tiêu về `NHAP`. Lỗi: `OKR-409-INVALID-STATE` khi mục tiêu không ở `CHO_DUYET`.
     */
    post: operations['withdrawOkrObjective'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/{id}/approve': {
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
     * Phê duyệt mục tiêu
     * @description Hoàn thành nhiệm vụ duyệt của tiến trình `duyet-okr`, chuyển `CHO_DUYET` → `DA_DUYET`, ghi `okr.objective_review` (`DUYET`), ghi sổ cái, phát EVT-01 và mở check-in (MSG-02-09). Người duyệt phải khác người soạn (BR-02-08): vi phạm trả 403 `OKR-403-SELF-APPROVAL` (MSG-02-11). Lỗi khác: `OKR-409-INVALID-STATE`, `OKR-409-VERSION-CONFLICT`.
     */
    post: operations['approveOkrObjective'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/{id}/return': {
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
     * Trả lại mục tiêu để sửa
     * @description Chuyển `CHO_DUYET` → `NHAP`, ghi `okr.objective_review` (`TRA_LAI`) và thông báo người soạn (EVT-21). Ý kiến là bắt buộc (BR-02-09): thiếu trả 422 `OKR-422-COMMENT-REQUIRED` (MSG-02-10). TL-02 chỉ ghi POST; hợp đồng thêm `Idempotency-Key` theo quy ước ghi.
     */
    post: operations['returnOkrObjective'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/objectives/{id}/history': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Lịch sử phiên bản và luồng duyệt của mục tiêu
     * @description Các lần lưu nháp, trình, rút lại, duyệt, trả lại theo thời gian, kèm nguồn gốc nội dung (người hay gợi ý AI) và ý kiến người duyệt. Phục vụ "Lịch sử nháp" của SCR-1701 và lịch sử phiên bản, lịch sử luồng duyệt của SCR-0203.
     */
    get: operations['listOkrObjectiveHistory'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/key-results': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách KR
     * @description Danh sách KR rút gọn dùng ở nhiều nơi: liên kết nhiệm vụ với KR (SCR-0501, `scope=unit`), khối "KR cần check-in" của trang chủ SCR-0002 (`owner=me&needCheckin=true`), danh sách check-in của SCR-0301 và SCR-0302. Mỗi dòng luôn có `id`, `code`, `title`. Khi `needCheckin=true`, phản hồi có `counts.needCheckin` cho số đếm trên menu và thẻ chỉ số.
     */
    get: operations['listOkrKeyResults'];
    put?: never;
    /**
     * Thêm một KR vào mục tiêu nháp
     * @description Thêm một KR vào mục tiêu ở trạng thái `NHAP` (ví dụ khi người soạn dùng gợi ý của trợ lý AI ở SCR-1701). Thao tác này làm tăng `row_version` của mục tiêu chứa KR; giao diện đang sửa mục tiêu cần đọc lại ETag. Mã KR (`KR {o}.{n}`) do máy chủ sinh. Lỗi: `OKR-409-INVALID-STATE` khi mục tiêu không ở `NHAP`, `OKR-409-PERIOD-LOCKED`.
     */
    post: operations['createOkrKeyResult'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/key-results/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Xem chi tiết một KR
     * @description (suy luận) TL không ghi API riêng; SCR-0301 vùng 2 cần công thức, chỉ tiêu, giá trị lần trước, nguồn số liệu, bản nháp check-in hiện có của người gọi và mục tiêu chứa KR.
     */
    get: operations['getOkrKeyResult'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/key-results/{id}/checkins': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Lịch sử check-in của KR
     * @description Các lần check-in `DA_GUI` của KR, mới nhất trước, để vẽ biểu đồ diễn biến và xem vướng mắc. Bản nháp `NHAP` chỉ trả cho chính người tạo.
     */
    get: operations['listOkrCheckins'];
    put?: never;
    /**
     * Lưu nháp hoặc gửi check-in KR
     * @description Một đường dẫn cho cả lưu nháp (`status=NHAP`) và gửi (`status=DA_GUI`). Mỗi người có tối đa một bản nháp cho mỗi KR; lưu nháp lần sau ghi đè bản nháp đó, gửi thì chuyển bản nháp thành `DA_GUI` (suy luận). Check-in đã gửi bất biến (BR-03-03); đính chính bằng check-in mới. Khi gửi: ghi `okr.kr_checkin`, cập nhật giá trị hiện tại, tiến độ KR và mục tiêu, cờ rủi ro, phát EVT-02, và EVT-21 nếu KR chuyển sang `RUI_RO` (BR-03-06). Ứng dụng di động gửi lại sau mất sóng với cùng `Idempotency-Key` (lưu vào `okr.kr_checkin.idempotency_key`). Lỗi: `OKR-409-OBJECTIVE-NOT-APPROVED` (BR-03-01), `OKR-409-PERIOD-LOCKED` (MSG-03-05), `OKR-422-VALIDATION` (BR-03-02), `OKR-422-OVERRIDE-REASON-REQUIRED` (BR-03-04, MSG-03-04), `OKR-422-LARGE-CHANGE-UNCONFIRMED` (BR-03-05, MSG-03-02; gửi lại với `confirmLargeChange=true`).
     */
    post: operations['createOkrCheckin'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/key-results/{id}/source-value': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Lấy lại số liệu nguồn của KR
     * @description Gọi lại hệ thống nguồn qua integration-gateway (SVC-13) để lấy giá trị gợi ý và thời điểm lấy (≤ 3 giây). Số liệu nguồn chỉ là gợi ý; chủ sở hữu KR xác nhận. TL-02 không ghi phương thức; hợp đồng chọn GET vì không đổi trạng thái. Nguồn lỗi trả 502 `OKR-502-SOURCE-UNAVAILABLE` (MSG-03-03), giao diện cho nhập tay.
     */
    get: operations['getOkrKeyResultSourceValue'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/alignment': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Cây liên kết dọc và ngang
     * @description Đồ thị liên kết (nút và cạnh) từ căn cứ, mục tiêu cấp trên, mục tiêu, KR xuống chương trình và nhiệm vụ của MOD-02, cùng liên kết ngang giữa đơn vị. Dùng cho chế độ "Bản đồ liên kết" của SCR-0201 và cây liên kết ở SCR-0203. Cần `objective`, hoặc cả `unit` và `period`; thiếu trả 400.
     */
    get: operations['getOkrAlignment'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/links': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách liên kết OKR
     * @description Tra liên kết theo đầu nguồn hoặc đầu đích (truy vết hai chiều theo PL 4 III.2), ví dụ các liên kết ngang của một mục tiêu hoặc các nhiệm vụ phục vụ một KR.
     */
    get: operations['listOkrLinks'];
    put?: never;
    /**
     * Tạo liên kết OKR
     * @description Tạo liên kết, chủ yếu là liên kết ngang (`NGANG`) giữa mục tiêu, KR của hai đơn vị cùng kỳ (SCR-0201 trường 12) hoặc liên kết đóng góp. Liên kết căn cứ và mục tiêu cấp trên của một mục tiêu ghi qua `basisIds`, `parentObjectiveId` của mục tiêu. Trùng liên kết trả 409 `OKR-409-LINK-EXISTS`.
     */
    post: operations['createOkrLink'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/links/{id}': {
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
    /**
     * Xoá liên kết OKR
     * @description (suy luận) Xoá một liên kết ngang hoặc đóng góp khi mục tiêu đầu nguồn còn ở `NHAP`. Liên kết của mục tiêu đã duyệt chỉ đổi qua phiên bản điều chỉnh (BR-02-10).
     */
    delete: operations['deleteOkrLink'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/bases': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Thư viện căn cứ
     * @description (suy luận) Danh sách căn cứ (nghị quyết, kết luận, chương trình, kế hoạch, chỉ tiêu KT-XH) để chọn ở SCR-0202 trường 1. TL không ghi đường dẫn; tên tài nguyên lấy từ bảng `okr.basis`.
     */
    get: operations['listOkrBases'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/tree': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Cây mục tiêu của đơn vị trong kỳ kèm ngữ cảnh màn hình
     * @description Dữ liệu dựng chế độ xem cây của SCR-0201 trong một lần gọi: kỳ đang xem và các kỳ chọn được, căn cứ, mục tiêu cấp trên, ngưỡng khuyến nghị số mục tiêu (BR-02-01, tham số Policy Pack), mọi mục tiêu của đơn vị (phẳng, nối bằng `parentObjective`) kèm KR và các liên kết ngang. Bỏ `unit` thì lấy đơn vị của ngữ cảnh làm việc; bỏ `period` thì lấy kỳ đang `MO`. Tách khỏi `GET /okr/objectives?view=tree` để mỗi thao tác trả đúng một lược đồ (suy luận đường dẫn; TL-02a SCR-0201 ghi chung `/okr/objectives?unit=&period=&view=tree`).
     */
    get: operations['getOkrTree'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/compose-context': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Ngữ cảnh soạn mục tiêu (SCR-0202)
     * @description (suy luận) Dữ liệu SCR-0202 cần khi mở: kỳ và các kỳ chưa khoá soạn được (trường 5), căn cứ (trường 1), mục tiêu cấp trên chọn được (trường 2, BR-02-02), số mục tiêu đã có của chủ thể trong kỳ không tính mục tiêu đang sửa (BR-02-01), người xác nhận KR chọn được (trường 14), cờ bật trợ lý AI và bộ quy tắc soát CEL của Policy Pack đang áp dụng. Trình duyệt chạy các quy tắc này chỉ để phản hồi tức thì; máy chủ chạy lại khi lưu và khi trình (AGENTS.md quy tắc 6).
     */
    get: operations['getOkrComposeContext'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/okr/reviews': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Hàng chờ phê duyệt OKR của người duyệt trong kỳ
     * @description (suy luận) Dữ liệu vùng 1 và vùng 2 của SCR-0203: các bộ OKR đơn vị cấp dưới đã trình và đang chờ người gọi duyệt (kể cả duyệt thay theo uỷ quyền), mỗi bộ kèm mục tiêu, KR và kết quả kiểm tra tự động tính lại khi đọc (không lưu). Số bộ bị chặn bởi số đơn vị trực thuộc nên trả một lần, không phân trang. `viewerIsAuthor = true` báo trước BR-02-08 (người soạn không tự duyệt); máy chủ vẫn kiểm lại khi gọi `approve`.
     */
    get: operations['listOkrReviews'];
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
     * @description Trạng thái mục tiêu (`okr.objective.status`, baseline mục 9.1). KR theo trạng thái của mục tiêu.
     * @enum {string}
     */
    OkrObjectiveStatus: 'NHAP' | 'CHO_DUYET' | 'DA_DUYET' | 'DONG';
    /**
     * @description Chủ thể sở hữu mục tiêu (`okr.objective.owner_type`).
     * @enum {string}
     */
    OkrOwnerType: 'TAP_THE' | 'CA_NHAN';
    /**
     * @description Loại đo của KR (`okr.key_result.metric_type`).
     * @enum {string}
     */
    OkrMetricType: 'SO_LUONG' | 'TY_LE' | 'CO_KHONG' | 'CHI_SO';
    /**
     * @description Chiều tốt của KR (`okr.key_result.direction`).
     * @enum {string}
     */
    OkrDirection: 'TANG' | 'GIAM' | 'DAT';
    /**
     * @description Mức tự tin (`okr.kr_checkin.confidence`, `okr.key_result.confidence`).
     * @enum {string}
     */
    OkrConfidence: 'CAO' | 'TRUNG_BINH' | 'THAP';
    /**
     * @description Cờ rủi ro của KR (`okr.key_result.risk_flag`, TL-02a QT-03).
     * @enum {string}
     */
    OkrRiskFlag: 'BINH_THUONG' | 'THEO_DOI' | 'RUI_RO';
    /**
     * @description Nguồn nội dung (`okr.key_result.content_source`, BR-02-11).
     * @enum {string}
     */
    OkrContentSource: 'HUMAN' | 'AI_ACCEPTED' | 'AI_EDITED';
    /**
     * @description Tần suất check-in (`okr.key_result.checkin_frequency`). TL-03 không có CHECK IN; mã lấy từ nhãn "Tuần, Hai tuần, Tháng" của SCR-0202 (suy luận).
     * @enum {string}
     */
    OkrCheckinFrequency: 'TUAN' | 'HAI_TUAN' | 'THANG';
    /**
     * @description Trạng thái lần check-in (`okr.kr_checkin.status`).
     * @enum {string}
     */
    OkrCheckinStatus: 'NHAP' | 'DA_GUI';
    /**
     * @description Kênh nhập check-in (`okr.kr_checkin.channel`).
     * @enum {string}
     */
    OkrCheckinChannel: 'WEB' | 'MOBILE' | 'TICH_HOP';
    /**
     * @description Loại căn cứ (`okr.basis.basis_kind`).
     * @enum {string}
     */
    OkrBasisKind:
      'NGHI_QUYET' | 'KET_LUAN' | 'CHI_THI' | 'CHUONG_TRINH' | 'KE_HOACH' | 'CHI_TIEU_KTXH';
    /**
     * @description Loại đầu nguồn của liên kết (`okr.okr_link.from_type`).
     * @enum {string}
     */
    OkrLinkFromType: 'BASIS' | 'OBJECTIVE' | 'KEY_RESULT';
    /**
     * @description Loại đầu đích (`okr.okr_link.to_type`); `PROGRAM`, `TASK` là mã logic của schema `task`.
     * @enum {string}
     */
    OkrLinkToType: 'OBJECTIVE' | 'KEY_RESULT' | 'PROGRAM' | 'TASK';
    /**
     * @description Loại liên kết (`okr.okr_link.link_kind`).
     * @enum {string}
     */
    OkrLinkKind: 'PHAN_RA' | 'DONG_GOP' | 'NGANG' | 'CAN_CU';
    /**
     * @description Quyết định của người duyệt (`okr.objective_review.decision`).
     * @enum {string}
     */
    OkrReviewDecision: 'DUYET' | 'TRA_LAI';
    /** @description Mục tiêu rút gọn. */
    OkrObjectiveRef: {
      id: components['schemas']['Uuid'];
      code: string;
      title: string;
      status?: components['schemas']['OkrObjectiveStatus'];
      /** @description Tên đơn vị sở hữu */
      unitName?: string;
    };
    /** @description Căn cứ rút gọn. */
    OkrBasisRef: {
      id: components['schemas']['Uuid'];
      basisKind?: components['schemas']['OkrBasisKind'];
      docNumber?: string;
      title: string;
    };
    /** @description Một kết quả của bộ kiểm tra tự động MOD-01 (tính lại mỗi lần đọc, không lưu; SCR-0203 trường 3). */
    OkrCheckResult: {
      level: components['schemas']['OkrCheckLevel'];
      /** @description Mã quy tắc, ví dụ `BR-02-05` */
      rule: string;
      /** @description Mã thông điệp TL-02, ví dụ `MSG-02-05` */
      msg?: string;
      /** @description Thông điệp tiếng Việt đã điền tham số */
      message: string;
      /** @description KR liên quan; vắng khi quy tắc áp cho cả mục tiêu. */
      keyResultId?: components['schemas']['Uuid'];
      /** @description Mã nhiệm vụ có nội dung gần giống KR (BR-02-06), ví dụ `NV-2027-Q1-0012`. */
      similarTaskCode?: string;
    };
    /** @description Thông tin luồng duyệt `duyet-okr` đang mở (`flowable.wf_task_projection`). */
    OkrApprovalInfo: {
      /** @description `okr.objective.wf_process_instance_id` */
      processInstanceId?: string;
      reviewer?: components['schemas']['Ref'];
      /** @description Người duyệt chính khi đang duyệt thay theo uỷ quyền. */
      onBehalfOf?: components['schemas']['Ref'];
      dueDate?: components['schemas']['Date'];
      submittedAt?: components['schemas']['DateTime'];
      /** @description Ý kiến gần nhất của người duyệt */
      lastComment?: string;
    };
    /** @description Mục tiêu O (`okr.objective`) kèm KR, căn cứ, liên kết và kết quả kiểm tra. */
    OkrObjective: {
      id: components['schemas']['Uuid'];
      periodId: components['schemas']['Uuid'];
      period?: components['schemas']['PeriodRef'];
      ownerType: components['schemas']['OkrOwnerType'];
      ownerUnit: components['schemas']['Ref'];
      /** @description Có khi `ownerType = CA_NHAN`. */
      ownerPerson?: components['schemas']['Ref'];
      /** @description Mã hiển thị `O{n}`, duy nhất trong đơn vị và kỳ */
      code: string;
      title: string;
      /** @description Diễn giải chuyển biến cần đạt */
      description?: string;
      /** @description Tầng phân rã */
      tier?: number;
      parentObjective?: components['schemas']['OkrObjectiveRef'];
      bases?: components['schemas']['OkrBasisRef'][];
      status: components['schemas']['OkrObjectiveStatus'];
      /** @description Tiến độ, trung bình có trọng số tiến độ các KR, từ 0 đến 1 (`okr.objective.progress`). */
      progress: number;
      /** @description Lý do vượt 3 mục tiêu (BR-02-01) */
      overLimitReason?: string;
      /** @description Nhãn "Vượt 3 O" cho người duyệt (suy luận) */
      overLimit?: boolean;
      /** @description Lần trình duyệt */
      versionNo: number;
      aiAssisted?: boolean;
      aiSuggestionId?: components['schemas']['Uuid'];
      approvedBy?: components['schemas']['Ref'];
      approvedAt?: components['schemas']['DateTime'];
      approval?: components['schemas']['OkrApprovalInfo'];
      keyResults?: components['schemas']['OkrKeyResult'][];
      /** @description Liên kết ngang (`link_kind = NGANG`). */
      peerLinks?: components['schemas']['OkrLink'][];
      /** @description Kết quả kiểm tra tự động; có trong phản hồi đọc một mục tiêu và phản hồi ghi. */
      checks?: components['schemas']['OkrCheckResult'][];
      createdBy?: components['schemas']['Ref'];
      createdAt?: components['schemas']['DateTime'];
      updatedAt?: components['schemas']['DateTime'];
      rowVersion: components['schemas']['RowVersion'];
    };
    OkrObjectivePage: {
      items: components['schemas']['OkrObjective'][];
      nextCursor: components['schemas']['NextCursor'];
      /** @description Số mục tiêu của đơn vị trong kỳ so với khuyến nghị (SCR-0201 vùng 1). */
      counts?: {
        objectives?: number;
        recommendedMax?: number;
      };
    };
    /** @description Phần sửa được của mục tiêu nháp. */
    OkrObjectiveWritable: {
      title: string;
      description?: string;
      /** @default 1 */
      tier: number;
      /** @description Mục tiêu cấp trên cùng kỳ; bắt buộc khi trình, trừ cấp cao nhất (BR-02-02). Ghi vào `okr.okr_link` loại `PHAN_RA`. */
      parentObjectiveId?: components['schemas']['Uuid'];
      /** @description Căn cứ (`okr.objective_basis`); ít nhất một khi trình (BR-02-02). */
      basisIds?: components['schemas']['Uuid'][];
      overLimitReason?: string;
      /** @description Gợi ý AI đã được người soạn chấp nhận cho tên mục tiêu (BR-02-11). */
      aiSuggestionId?: components['schemas']['Uuid'];
      /** @description Danh sách KR đầy đủ của mục tiêu; tổng `weight` bằng 1 khi trình (BR-02-07). */
      keyResults?: components['schemas']['OkrKeyResultInput'][];
    };
    OkrObjectiveCreate: components['schemas']['OkrObjectiveWritable'] & {
      periodId: components['schemas']['Uuid'];
      ownerType: components['schemas']['OkrOwnerType'];
      ownerUnitId: components['schemas']['Uuid'];
      /** @description Bắt buộc khi `ownerType = CA_NHAN`. */
      ownerPersonId?: components['schemas']['Uuid'];
    };
    OkrObjectiveUpdate: components['schemas']['OkrObjectiveWritable'];
    OkrCopyFromPeriodRequest: {
      fromPeriodId: components['schemas']['Uuid'];
      toPeriodId: components['schemas']['Uuid'];
      ownerUnitId: components['schemas']['Uuid'];
      /** @description Chỉ sao chép các mục tiêu này; vắng thì sao chép tất cả. */
      objectiveIds?: components['schemas']['Uuid'][];
    };
    OkrExportRequest: {
      periodId: components['schemas']['Uuid'];
      unitId: components['schemas']['Uuid'];
      /**
       * @description Định dạng tệp (suy luận).
       * @default DOCX
       * @enum {string}
       */
      format: 'DOCX' | 'PDF' | 'XLSX';
    };
    OkrReviewComment: {
      comment?: string;
    };
    OkrApproveRequest: {
      /** @description Ý kiến phê duyệt, không bắt buộc */
      comment?: string;
      /**
       * @description Thông báo cho các đơn vị liên kết ngang
       * @default true
       */
      notifyPeers: boolean;
    };
    OkrReturnRequest: {
      /** @description Ý kiến bắt buộc (BR-02-09) */
      comment: string;
      /** @default true */
      notifyPeers: boolean;
    };
    OkrApproveBatchRequest: {
      items: {
        id: components['schemas']['Uuid'];
        rowVersion: components['schemas']['RowVersion'];
      }[];
      comment?: string;
      /** @default true */
      notifyPeers: boolean;
    };
    /** @description Một mốc trong lịch sử mục tiêu (`okr.objective_review` và lịch sử luồng duyệt). */
    OkrObjectiveHistoryEntry: {
      at: components['schemas']['DateTime'];
      /**
       * @description Hành động (mã suy luận từ bảng trạng thái QT-02).
       * @enum {string}
       */
      action: 'TAO' | 'LUU_NHAP' | 'TRINH' | 'RUT_LAI' | 'DUYET' | 'TRA_LAI' | 'DONG';
      versionNo: number;
      status?: components['schemas']['OkrObjectiveStatus'];
      actor: components['schemas']['Ref'];
      /** @description Người duyệt chính khi duyệt thay. */
      onBehalfOf?: components['schemas']['Ref'];
      decision?: components['schemas']['OkrReviewDecision'];
      comment?: string;
      /** @description Lần lưu có nội dung chèn từ gợi ý AI */
      aiAssisted?: boolean;
    };
    OkrObjectiveHistoryPage: {
      items: components['schemas']['OkrObjectiveHistoryEntry'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Check-in `DA_GUI` gần nhất của KR. */
    OkrLastCheckin: {
      value: number;
      confidence: components['schemas']['OkrConfidence'];
      checkinAt: components['schemas']['DateTime'];
    };
    /** @description Kết quả then chốt (`okr.key_result`). */
    OkrKeyResult: {
      id: components['schemas']['Uuid'];
      objectiveId: components['schemas']['Uuid'];
      periodId?: components['schemas']['Uuid'];
      /** @description Mã `KR {o}.{n}` */
      code: string;
      title: string;
      metricType?: components['schemas']['OkrMetricType'];
      /** @description Đơn vị đo: %, người, hồ sơ, ngày, lần, mốc */
      unit: string;
      baselineValue?: number | null;
      /** @description Chỉ tiêu. Chỉ được `null` khi KR còn `NHAP` (bản nháp lưu tự động của SCR-0202 có thể chưa nhập); máy chủ chặn trình khi thiếu (BR-02-04). */
      targetValue: number | null;
      /** @description Giá trị từ check-in gần nhất */
      currentValue?: number | null;
      direction?: components['schemas']['OkrDirection'];
      /** @description Trọng số trong mục tiêu, từ 0 đến 1 */
      weight: number;
      /** @description Hạn. Chỉ được `null` khi KR còn `NHAP`; máy chủ chặn trình khi thiếu (BR-02-04). */
      dueDate: components['schemas']['Date'] | null;
      verifier?: components['schemas']['Ref'];
      /** @description Chủ sở hữu KR, người check-in (suy luận: TL-02 SCR-0002 nhắc `okr.key_result.owner_id` nhưng TL-03 không có cột này). */
      owner?: components['schemas']['Ref'];
      /** @description Người đang được giao check-in thay (suy luận). */
      checkinDelegate?: components['schemas']['Ref'];
      dataSource?: string;
      formulaText?: string;
      confidence?: components['schemas']['OkrConfidence'];
      progress: number;
      status: components['schemas']['OkrObjectiveStatus'];
      riskFlag: components['schemas']['OkrRiskFlag'];
      checkinFrequency?: components['schemas']['OkrCheckinFrequency'];
      contentSource?: components['schemas']['OkrContentSource'];
      aiAssisted?: boolean;
      aiSuggestionId?: components['schemas']['Uuid'];
      lastCheckin?: components['schemas']['OkrLastCheckin'];
      myDraftCheckin?: components['schemas']['OkrCheckin'];
      /** @description Hạn check-in của chu kỳ hiện tại (suy luận). */
      nextCheckinDue?: components['schemas']['Date'];
      /** @description Mục tiêu chứa KR; có trong `GET /okr/key-results/{id}` (SCR-0301 vùng 2). */
      objective?: components['schemas']['OkrObjectiveRef'];
      /** @description Người gọi là chủ sở hữu hoặc người được giao check-in thay (máy chủ tính; suy luận). */
      viewerIsOwner?: boolean;
      /** @description Ghi chú vướng mắc của check-in gần nhất, hiện ở SCR-0201 trường 6 (suy luận). */
      latestBlocker?: string | null;
      /** @description Một số chương trình, nhiệm vụ liên kết để hiển thị (SCR-0201 trường 6, SCR-0301 vùng 5); có trong `GET /okr/tree` và `GET /okr/key-results/{id}`. */
      linkedWork?: components['schemas']['OkrLinkedWork'][];
      /** @description Tổng số nhiệm vụ liên kết, có thể nhiều hơn số phần tử `linkedWork`. */
      linkedTaskCount?: number;
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description KR khi soạn (SCR-0202 trường 7-16). Khi mục tiêu còn `NHAP`, bản nháp được lưu dù thiếu `targetValue` hoặc `dueDate` (gửi `null` hoặc bỏ trường); khi trình, máy chủ kiểm lại đủ trường theo BR-02-04 và trả 422. Lưu KR thiếu hai trường này khi mục tiêu không còn `NHAP` cũng trả 422. */
    OkrKeyResultInput: {
      /** @description Có khi sửa KR đã có trong `PUT /okr/objectives/{id}`. */
      id?: components['schemas']['Uuid'];
      title: string;
      /** @description Mặc định `SO_LUONG` khi vắng (suy luận; SCR-0202 không có trường này). */
      metricType?: components['schemas']['OkrMetricType'];
      unit: string;
      /** @default 0 */
      baselineValue: number;
      targetValue?: number | null;
      /** @description Mặc định `TANG` khi vắng (suy luận). */
      direction?: components['schemas']['OkrDirection'];
      weight: number;
      /** @description Mặc định ngày cuối kỳ khi vắng (TL-02a SCR-0202 trường 13). */
      dueDate?: components['schemas']['Date'] | null;
      /** @description Người xác nhận, khác chủ sở hữu KR. */
      verifierPersonId: components['schemas']['Uuid'];
      dataSource?: string;
      formulaText?: string;
      checkinFrequency?: components['schemas']['OkrCheckinFrequency'];
      contentSource?: components['schemas']['OkrContentSource'];
      aiSuggestionId?: components['schemas']['Uuid'];
    };
    OkrKeyResultCreate: components['schemas']['OkrKeyResultInput'] & {
      objectiveId: components['schemas']['Uuid'];
    };
    /** @description KR rút gọn trong danh sách. Luôn có `id`, `code`, `title` (tương thích frontend WEB-028). */
    OkrKeyResultListItem: {
      id: components['schemas']['Uuid'];
      code: string;
      title: string;
      objectiveId?: components['schemas']['Uuid'];
      objectiveCode?: string;
      unit?: string;
      targetValue?: number;
      currentValue?: number | null;
      progress?: number;
      confidence?: components['schemas']['OkrConfidence'];
      riskFlag?: components['schemas']['OkrRiskFlag'];
      dueDate?: components['schemas']['Date'];
      nextCheckinDue?: components['schemas']['Date'];
      lastCheckin?: components['schemas']['OkrLastCheckin'];
    };
    OkrKeyResultPage: {
      items: components['schemas']['OkrKeyResultListItem'][];
      nextCursor: components['schemas']['NextCursor'];
      /** @description Kỳ của danh sách, để dựng tiêu đề SCR-0301 (tuần, kỳ) (suy luận). */
      period?: components['schemas']['OkrPeriodInfo'];
      /** @description Có khi `needCheckin=true`. */
      counts?: {
        needCheckin?: number;
      };
    };
    OkrSourceValue: {
      value: number;
      fetchedAt: components['schemas']['DateTime'];
      dataSource?: string;
    };
    /** @description Một lần check-in (`okr.kr_checkin`), chỉ ghi thêm. */
    OkrCheckin: {
      id: components['schemas']['Uuid'];
      keyResultId: components['schemas']['Uuid'];
      periodId?: components['schemas']['Uuid'];
      checkinAt: components['schemas']['DateTime'];
      value: number;
      confidence: components['schemas']['OkrConfidence'];
      highlights?: string;
      blockerNote?: string;
      channel: components['schemas']['OkrCheckinChannel'];
      status: components['schemas']['OkrCheckinStatus'];
      sourceValue?: number | null;
      sourceFetchedAt?: components['schemas']['DateTime'];
      overrideReason?: string;
      verifiedBy?: components['schemas']['Ref'];
      verifiedAt?: components['schemas']['DateTime'];
      createdBy?: components['schemas']['Ref'];
      /** @description Check-in thay chủ sở hữu KR (suy luận) */
      onBehalf?: boolean;
    };
    /** @description Thân yêu cầu lưu nháp hoặc gửi check-in (SCR-0301 trường 5-11, SCR-0302). */
    OkrCheckinCreate: {
      status: components['schemas']['OkrCheckinStatus'];
      /** @description Giá trị hiện tại, tối đa 4 chữ số lẻ (BR-03-02). */
      value: number;
      confidence: components['schemas']['OkrConfidence'];
      /** @description Giá trị nguồn đã lấy (nếu có) */
      sourceValue?: number;
      sourceFetchedAt?: components['schemas']['DateTime'];
      /** @description Bắt buộc khi `value` khác `sourceValue` (BR-03-04). */
      overrideReason?: string;
      highlights?: string;
      /** @description Vướng mắc, đề nghị hỗ trợ; có thể bắt buộc khi tự tin `THAP` theo cấu hình tenant. */
      blockerNote?: string;
      channel?: components['schemas']['OkrCheckinChannel'];
      /**
       * @description Người dùng đã xác nhận cảnh báo chênh lệch lớn (BR-03-05, MSG-03-02).
       * @default false
       */
      confirmLargeChange: boolean;
    };
    /** @description Kết quả ghi check-in kèm tiến độ đã tính lại (MSG-03-01). */
    OkrCheckinResult: {
      checkin: components['schemas']['OkrCheckin'];
      keyResult: {
        id: components['schemas']['Uuid'];
        code: string;
        currentValue?: number | null;
        progress: number;
        confidence?: components['schemas']['OkrConfidence'];
        riskFlag: components['schemas']['OkrRiskFlag'];
      };
      objective: {
        id: components['schemas']['Uuid'];
        code: string;
        progress: number;
      };
    };
    OkrCheckinPage: {
      items: components['schemas']['OkrCheckin'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Căn cứ (`okr.basis`). */
    OkrBasis: {
      id: components['schemas']['Uuid'];
      basisKind: components['schemas']['OkrBasisKind'];
      docNumber?: string;
      title: string;
      issuedByUnit?: components['schemas']['Ref'];
      issuedOn?: components['schemas']['Date'];
      sourceDocRef?: string;
      parentBasisId?: components['schemas']['Uuid'];
      validFrom?: components['schemas']['Date'];
      validTo?: components['schemas']['Date'];
    };
    OkrBasisPage: {
      items: components['schemas']['OkrBasis'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Liên kết đa hình (`okr.okr_link`). */
    OkrLink: {
      id: components['schemas']['Uuid'];
      periodId?: components['schemas']['Uuid'];
      fromType: components['schemas']['OkrLinkFromType'];
      fromId: components['schemas']['Uuid'];
      toType: components['schemas']['OkrLinkToType'];
      toId: components['schemas']['Uuid'];
      linkKind: components['schemas']['OkrLinkKind'];
      contributionWeight?: number | null;
      /** @description Mã, tên đầu nguồn để hiển thị */
      fromLabel?: string;
      /** @description Mã, tên và đơn vị đầu đích để hiển thị */
      toLabel?: string;
      rowVersion?: components['schemas']['RowVersion'];
    };
    OkrLinkCreate: {
      fromType: components['schemas']['OkrLinkFromType'];
      fromId: components['schemas']['Uuid'];
      toType: components['schemas']['OkrLinkToType'];
      toId: components['schemas']['Uuid'];
      linkKind: components['schemas']['OkrLinkKind'];
      contributionWeight?: number;
    };
    OkrLinkPage: {
      items: components['schemas']['OkrLink'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    OkrAlignmentNode: {
      /** @enum {string} */
      type: 'BASIS' | 'OBJECTIVE' | 'KEY_RESULT' | 'PROGRAM' | 'TASK';
      id: components['schemas']['Uuid'];
      code?: string;
      title: string;
      unitName?: string;
      /** @description Trạng thái của đối tượng theo module sở hữu */
      status?: string;
      progress?: number;
      confidence?: components['schemas']['OkrConfidence'];
    };
    OkrAlignmentEdge: {
      fromType: components['schemas']['OkrLinkFromType'];
      fromId: components['schemas']['Uuid'];
      toType: components['schemas']['OkrLinkToType'];
      toId: components['schemas']['Uuid'];
      linkKind: components['schemas']['OkrLinkKind'];
      contributionWeight?: number | null;
    };
    /** @description Đồ thị liên kết dọc và ngang. */
    OkrAlignmentGraph: {
      rootId?: components['schemas']['Uuid'];
      nodes: components['schemas']['OkrAlignmentNode'][];
      edges: components['schemas']['OkrAlignmentEdge'][];
    };
    /**
     * @description Mức của một kết quả kiểm tra tự động MOD-01. Mã `DAT`, `CANH_BAO`, `LOI_CHAN` là suy luận từ nhãn "Đạt, cảnh báo, lỗi" của SCR-0203 trường 3.
     * @enum {string}
     */
    OkrCheckLevel: 'DAT' | 'CANH_BAO' | 'LOI_CHAN';
    /** @description Kỳ kèm trạng thái và vị trí tuần hiện tại để dựng tiêu đề SCR-0201, SCR-0203, SCR-0301 (ví dụ "Quý IV/2026 · Tuần 4/13"). `weekNo`, `weekCount` vắng với kỳ chưa bắt đầu (suy luận). */
    OkrPeriodInfo: components['schemas']['PeriodRef'] & {
      status: components['schemas']['PeriodStatus'];
      weekNo?: number;
      weekCount?: number;
    };
    /** @description Người chọn được kèm chức danh, ví dụ người xác nhận KR (SCR-0202 trường 14) hay người duyệt. */
    OkrPersonOption: {
      id: components['schemas']['Uuid'];
      name: string;
      /** @description Chức danh */
      title?: string;
    };
    /** @description Chương trình hoặc nhiệm vụ gắn với KR qua `okr.okr_link` (SCR-0201 trường 6, SCR-0301 vùng 5), kèm tiến độ do module sở hữu cung cấp (suy luận). */
    OkrLinkedWork: {
      id: components['schemas']['Uuid'];
      /** @enum {string} */
      kind: 'PROGRAM' | 'TASK';
      /** @description Mã hiển thị, ví dụ `NV-2027-Q1-0012` */
      code: string;
      title: string;
      progress?: number;
    };
    /** @description Liên kết ngang của cây mục tiêu, đã ghép nhãn hai đầu để hiển thị (SCR-0201 trường 5). */
    OkrPeerLink: {
      id: components['schemas']['Uuid'];
      /** @description Đầu của đơn vị đang xem, ví dụ "O2 · KR 2.1" */
      fromLabel: string;
      /** @description KR đầu nguồn; `null` khi liên kết đặt ở mức mục tiêu. */
      fromKeyResultId?: components['schemas']['Uuid'] | null;
      peerUnitName: string;
      peerCode: string;
      peerTitle: string;
    };
    /** @description Cây mục tiêu của một đơn vị trong kỳ kèm ngữ cảnh SCR-0201 (`GET /okr/tree`). */
    OkrTree: {
      period: components['schemas']['OkrPeriodInfo'];
      /** @description Các kỳ chọn được ở bộ lọc kỳ. */
      periods: components['schemas']['OkrPeriodInfo'][];
      unit: components['schemas']['Ref'];
      /** @description Đơn vị đang xem là đơn vị trong ngữ cảnh làm việc của người gọi (máy chủ tính theo token). */
      viewerOwnUnit: boolean;
      bases: components['schemas']['OkrBasisRef'][];
      /** @description Mục tiêu cấp trên mà cây này phân rã từ; `null` ở đơn vị cao nhất. */
      parent: components['schemas']['OkrObjectiveRef'] | null;
      /** @description Số mục tiêu khuyến nghị tối đa mỗi kỳ (BR-02-01, tham số Policy Pack). */
      recommendedMax: number;
      /** @description Mọi mục tiêu của cây, phẳng, nối bằng `parentObjective.id`; mỗi mục tiêu kèm `keyResults`. */
      objectives: components['schemas']['OkrObjective'][];
      peerLinks: components['schemas']['OkrPeerLink'][];
    };
    /** @description Quy tắc soát OKR viết bằng CEL trong Policy Pack (QT-02 bước 4). `scope` cho biết biểu thức chạy trên `objective` hay trên từng `kr`; `message` có thể chứa chỗ trống `{tên}` lấy từ biến cùng tên của ngữ cảnh. `serverOnly = true` thì trình duyệt không chạy (cần dữ liệu máy chủ, ví dụ so trùng nhiệm vụ của BR-02-06). */
    OkrRule: {
      id: string;
      /** @description Mã quy tắc nghiệp vụ, ví dụ `BR-02-05` */
      br: string;
      /** @enum {string} */
      scope: 'objective' | 'kr';
      /**
       * @description `CHAN` chặn trình duyệt, `CANH_BAO` chỉ cảnh báo (cột "Xử lý" của bảng BR TL-02a).
       * @enum {string}
       */
      severity: 'CHAN' | 'CANH_BAO';
      /** @description Biểu thức CEL điều kiện áp dụng */
      when: string;
      /** @description Biểu thức CEL phải đúng */
      assert: string;
      message: string;
      /** @description Nhãn khi đạt cho danh sách kiểm tra trước khi trình */
      passLabel?: string;
      /** @default false */
      serverOnly: boolean;
    };
    /** @description Tham số soát OKR của Policy Pack đang áp dụng cho tenant (suy luận cách đóng gói). */
    OkrPolicy: {
      /** @description Phiên bản Policy Pack */
      version: string;
      /** @description BR-02-01 */
      recommendedMax: number;
      /** @description Từ mơ hồ (BR-02-05), ví dụ "tăng cường", "đẩy mạnh". */
      vagueWords: string[];
      /** @description Động từ mở đầu KR giống nhiệm vụ (BR-02-06), ví dụ "tổ chức", "ban hành". */
      taskVerbs: string[];
      /** @description Đơn vị đo gợi ý (SCR-0202 trường 9). */
      units: string[];
      rules: components['schemas']['OkrRule'][];
    };
    /** @description Mục tiêu cấp trên chọn được (SCR-0202 trường 2). */
    OkrParentOption: components['schemas']['OkrObjectiveRef'] & {
      periodId: components['schemas']['Uuid'];
    };
    /** @description Ngữ cảnh soạn mục tiêu của đơn vị trong kỳ (`GET /okr/compose-context`, SCR-0202). */
    OkrComposeContext: {
      period: components['schemas']['OkrPeriodInfo'];
      /** @description Kỳ chưa khoá soạn được (trường 5). */
      periods: components['schemas']['OkrPeriodInfo'][];
      unit: components['schemas']['Ref'];
      /** @description Đơn vị cao nhất của tenant, không bắt buộc mục tiêu cấp trên (BR-02-02). */
      isTopLevel: boolean;
      bases: components['schemas']['OkrBasisRef'][];
      parentOptions: components['schemas']['OkrParentOption'][];
      /** @description Số mục tiêu đã có của chủ thể trong kỳ, không tính mục tiêu đang sửa (BR-02-01). */
      existingCount: number;
      /** @description Người xác nhận KR chọn được (trường 14). */
      verifiers: components['schemas']['OkrPersonOption'][];
      /** @description Người gọi, để mặc định người xác nhận khác chủ sở hữu. */
      me: components['schemas']['Ref'];
      /** @description Tenant bật trợ lý AI soát OKR (AC-12) */
      aiEnabled: boolean;
      policy: components['schemas']['OkrPolicy'];
    };
    /** @description Kết quả kiểm tra rút gọn một dòng của SCR-0203 trường 3 (tính lại khi đọc, không lưu). */
    OkrReviewCheck: {
      level: components['schemas']['OkrCheckLevel'];
      /** @description Nhãn ngắn như "Có căn cứ", "Đo được", "Gần giống NV-2027-Q1-0012", "Vượt 3 O" */
      label: string;
    };
    OkrReviewKr: {
      id: components['schemas']['Uuid'];
      code: string;
      title: string;
      /** @description Chỉ tiêu kèm đơn vị đo, ví dụ "3 %" */
      targetText: string;
      weight: number;
      /** @description Nhãn liên kết lên mục tiêu hoặc KR cấp trên; `null` khi chưa liên kết. */
      link: string | null;
      check: components['schemas']['OkrReviewCheck'];
    };
    OkrReviewObjective: {
      id: components['schemas']['Uuid'];
      code: string;
      title: string;
      /** @description Mã mục tiêu cấp trên */
      parentCode: string | null;
      versionNo: number;
      /** @description Gửi lại trong `If-Match` khi phê duyệt hoặc trả lại. */
      rowVersion: components['schemas']['RowVersion'];
      check: components['schemas']['OkrReviewCheck'];
      /** @description Toàn bộ kết quả kiểm tra chi tiết (tuỳ chọn, như `OkrObjective.checks`). */
      checks?: components['schemas']['OkrCheckResult'][];
      keyResults: components['schemas']['OkrReviewKr'][];
    };
    /** @description Một bộ OKR của đơn vị cấp dưới đã trình, đang chờ người gọi duyệt (SCR-0203 vùng 1). */
    OkrReviewSet: {
      /** @description Mã bộ trình duyệt, ổn định trong kỳ (suy luận; dùng mã phiên `duyet-okr` hoặc tổ hợp đơn vị và kỳ). */
      id: string;
      unit: components['schemas']['Ref'];
      periodId: components['schemas']['Uuid'];
      submittedAt: components['schemas']['DateTime'];
      submittedBy: components['schemas']['Ref'];
      /** @description Lần trình duyệt */
      versionNo: number;
      objectiveCount: number;
      krCount: number;
      summary: components['schemas']['OkrReviewCheck'];
      /** @description Số kết quả `LOI_CHAN` */
      blockingCount: number;
      /** @description Số kết quả `CANH_BAO` */
      warningCount: number;
      /** @description Người gọi là người soạn bộ này nên không được duyệt (BR-02-08, MSG-02-11). */
      viewerIsAuthor: boolean;
      objectives: components['schemas']['OkrReviewObjective'][];
    };
    /** @description Hàng chờ phê duyệt OKR của người gọi trong kỳ (`GET /okr/reviews`, SCR-0203). */
    OkrReviewQueue: {
      period: components['schemas']['OkrPeriodInfo'];
      periods: components['schemas']['OkrPeriodInfo'][];
      /** @description Hạn duyệt sớm nhất trong hàng chờ; vắng khi hàng chờ rỗng. */
      dueDate?: components['schemas']['Date'];
      reviewer: components['schemas']['OkrPersonOption'];
      /** @description Quy trình `duyet-okr` đang áp dụng (`flowable` process definition). */
      workflow: {
        /** @example duyet-okr */
        code: string;
        version: number;
      };
      items: components['schemas']['OkrReviewSet'][];
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
    /** @description Tham chiếu rút gọn tới một đối tượng kèm tên hiển thị. */
    Ref: {
      id: components['schemas']['Uuid'];
      name: string;
    };
    /**
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
    /**
     * @description Giá trị `row_version` (khoá lạc quan). Cùng giá trị với ETag, gửi lại trong `If-Match`.
     * @example 7
     */
    RowVersion: number;
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
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
    /** @description Lỗi khác theo RFC 9457. */
    Problem: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Yêu cầu sai cú pháp hoặc tham số không hợp lệ. */
    BadRequest: {
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
    /** @description Không tìm thấy, hoặc đối tượng nằm ngoài phạm vi người gọi (không lộ sự tồn tại). */
    NotFound: {
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
  };
  parameters: {
    /** @description Mã kỳ đánh giá (`config.period.id`). */
    PeriodId: components['schemas']['Uuid'];
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
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
  listOkrObjectives: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Chế độ xem của SCR-0201. Mặc định `tree`. */
        view?: 'tree' | 'table' | 'map';
        /** @description Lọc mục tiêu tập thể hay cá nhân (`okr.objective.owner_type`). */
        ownerType?: components['schemas']['OkrOwnerType'];
        /** @description `me` để chỉ lấy OKR cá nhân của người gọi. */
        owner?: 'me';
        /** @description Lọc theo trạng thái mục tiêu; có thể lặp lại tham số. */
        status?: components['schemas']['OkrObjectiveStatus'][];
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
      /** @description Một trang mục tiêu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjectivePage'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createOkrObjective: {
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
        'application/json': components['schemas']['OkrObjectiveCreate'];
      };
    };
    responses: {
      /** @description Đã tạo mục tiêu nháp. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjective'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  copyOkrObjectivesFromPeriod: {
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
        'application/json': components['schemas']['OkrCopyFromPeriodRequest'];
      };
    };
    responses: {
      /** @description Các mục tiêu nháp đã tạo trong kỳ đích. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OkrObjective'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  exportOkrObjectives: {
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
        'application/json': components['schemas']['OkrExportRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  approveOkrObjectivesBatch: {
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
        'application/json': components['schemas']['OkrApproveBatchRequest'];
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
  getOkrObjective: {
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
      /** @description Mục tiêu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjective'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateOkrObjective: {
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
        'application/json': components['schemas']['OkrObjectiveUpdate'];
      };
    };
    responses: {
      /** @description Đã lưu nháp; trả mục tiêu kèm kết quả kiểm tra mới. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjective'];
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
  submitOkrObjective: {
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
      /** @description Đã trình; mục tiêu ở `CHO_DUYET`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjective'];
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
  withdrawOkrObjective: {
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
        'application/json': components['schemas']['OkrReviewComment'];
      };
    };
    responses: {
      /** @description Đã rút lại; mục tiêu ở `NHAP`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjective'];
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
  approveOkrObjective: {
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
        'application/json': components['schemas']['OkrApproveRequest'];
      };
    };
    responses: {
      /** @description Đã phê duyệt. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjective'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description OPA từ chối, hoặc người gọi là người soạn bộ OKR (`OKR-403-SELF-APPROVAL`, MSG-02-11). */
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
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  returnOkrObjective: {
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
        'application/json': components['schemas']['OkrReturnRequest'];
      };
    };
    responses: {
      /** @description Đã trả lại; mục tiêu ở `NHAP`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjective'];
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
  listOkrObjectiveHistory: {
    parameters: {
      query?: {
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
      /** @description Một trang lịch sử, mới nhất trước. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrObjectiveHistoryPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listOkrKeyResults: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description `unit` lấy KR của đơn vị trong ngữ cảnh đang chọn (hoặc của `unit`); `subtree` lấy cả cây cấp dưới (chỉ `leader`). Mặc định `unit`. */
        scope?: 'unit' | 'subtree';
        /** @description `me` để chỉ lấy KR người gọi sở hữu hoặc được giao check-in thay. */
        owner?: 'me';
        /** @description Chỉ lấy KR thuộc mục tiêu `DA_DUYET` chưa có check-in `DA_GUI` trong chu kỳ hiện tại. */
        needCheckin?: boolean;
        /** @description Chỉ lấy KR của một mục tiêu. */
        objective?: components['schemas']['Uuid'];
        /** @description Lọc theo cờ rủi ro. */
        riskFlag?: components['schemas']['OkrRiskFlag'];
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
      /** @description Một trang KR. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrKeyResultPage'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createOkrKeyResult: {
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
        'application/json': components['schemas']['OkrKeyResultCreate'];
      };
    };
    responses: {
      /** @description Đã thêm KR. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrKeyResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getOkrKeyResult: {
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
      /** @description KR. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrKeyResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listOkrCheckins: {
    parameters: {
      query?: {
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
      /** @description Một trang check-in. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrCheckinPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createOkrCheckin: {
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
        'application/json': components['schemas']['OkrCheckinCreate'];
      };
    };
    responses: {
      /** @description Đã lưu nháp hoặc đã gửi; trả check-in và tiến độ sau khi tính lại. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrCheckinResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getOkrKeyResultSourceValue: {
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
      /** @description Giá trị nguồn. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrSourceValue'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Hệ thống nguồn không phản hồi (`OKR-502-SOURCE-UNAVAILABLE`, MSG-03-03). */
      502: {
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
  getOkrAlignment: {
    parameters: {
      query?: {
        /** @description Mục tiêu gốc của đồ thị. */
        objective?: components['schemas']['Uuid'];
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Số tầng đi lên và đi xuống từ nút gốc (suy luận). Mặc định 3. */
        depth?: number;
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đồ thị liên kết. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrAlignmentGraph'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  listOkrLinks: {
    parameters: {
      query?: {
        fromType?: components['schemas']['OkrLinkFromType'];
        fromId?: components['schemas']['Uuid'];
        toType?: components['schemas']['OkrLinkToType'];
        toId?: components['schemas']['Uuid'];
        linkKind?: components['schemas']['OkrLinkKind'];
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
      /** @description Một trang liên kết. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrLinkPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createOkrLink: {
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
        'application/json': components['schemas']['OkrLinkCreate'];
      };
    };
    responses: {
      /** @description Đã tạo liên kết. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrLink'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  deleteOkrLink: {
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
      /** @description Đã xoá. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listOkrBases: {
    parameters: {
      query?: {
        kind?: components['schemas']['OkrBasisKind'];
        /** @description Chỉ lấy căn cứ còn hiệu lực vào ngày này. */
        validOn?: components['schemas']['Date'];
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
      /** @description Một trang căn cứ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrBasisPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getOkrTree: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Cây mục tiêu và ngữ cảnh. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrTree'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getOkrComposeContext: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Mục tiêu đang sửa; vắng khi soạn mới. */
        objective?: components['schemas']['Uuid'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Ngữ cảnh soạn. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrComposeContext'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listOkrReviews: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Hàng chờ duyệt. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrReviewQueue'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
}
