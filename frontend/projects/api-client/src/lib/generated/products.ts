/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/products.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/products': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách sản phẩm của một nhiệm vụ
     * @description Trả các sản phẩm khai báo cho nhiệm vụ cùng số lượng kế hoạch, số lượng đã xác nhận và trạng thái tổng hợp. Dùng ở SCR-0701 (chọn sản phẩm cần nộp) và SCR-0703 (tính số lượng A). Thao tác đọc suy luận từ ma trận API `products` (GET cho AC-05, AC-06); TL-01 PL C chỉ ghi thao tác tạo.
     */
    get: operations['listDeliverables'];
    put?: never;
    /**
     * Khai báo sản phẩm của nhiệm vụ
     * @description Người được giao khai báo một sản phẩm cần nộp cho nhiệm vụ (tên, loại, đơn vị tính, số lượng kế hoạch). TL-02 không có SCR riêng cho thao tác này; sản phẩm thường được sinh khi giao việc từ mục danh mục (suy luận).
     */
    post: operations['createDeliverable'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/products/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Chi tiết một sản phẩm
     * @description Trả sản phẩm kèm `ETag`. Thao tác đọc suy luận từ ma trận API `products`.
     */
    get: operations['getDeliverable'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách minh chứng theo nhiệm vụ, sản phẩm hoặc hàng chờ nghiệm thu
     * @description Danh sách minh chứng kèm trạng thái phiên bản mới nhất. Với `reviewer=me` và `status=DA_NOP,DANG_NGHIEM_THU` trả hàng chờ nghiệm thu của người gọi (menu "Đánh giá / Chờ tôi duyệt" dẫn vào SCR-0703); với `submitter=me` trả minh chứng của tôi (tab "Minh chứng" của SCR-0702). Bộ lọc là suy luận từ đường vào của SCR.
     */
    get: operations['listEvidences'];
    put?: never;
    /**
     * Tạo minh chứng cho một sản phẩm
     * @description Tạo minh chứng (`product.evidence`) gắn với sản phẩm của nhiệm vụ mà người gọi chủ trì. Phiên bản đầu tiên tạo bằng `POST /evidences/{id}/versions`. TL-02 không ghi riêng thao tác này; nó cần thiết vì đường dẫn lưu nháp của SCR-0701 đã có `{id}` của minh chứng (suy luận). Thay cho phần tạo của TL-01 PL C `POST /products/{id}/evidence-versions`.
     */
    post: operations['createEvidence'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/external-docs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tìm văn bản đã ban hành của đơn vị để làm minh chứng
     * @description Tìm văn bản đã ban hành của đơn vị trong vOffice qua integration-gateway (SVC-13) để dùng làm minh chứng mà không tải lại tệp (BR-07-13). Trả số ký hiệu, ngày ban hành, trích yếu, người ký và trạng thái chữ ký số.
     */
    get: operations['searchEvidenceExternalDocs'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Chi tiết minh chứng và lịch sử phiên bản
     * @description Trả minh chứng cùng danh sách rút gọn mọi phiên bản (vùng 4 "Lịch sử phiên bản" của SCR-0701). Phiên bản không bao giờ bị xoá (BR-07-01).
     */
    get: operations['getEvidence'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Tạo phiên bản minh chứng mới ở trạng thái nháp
     * @description Tạo phiên bản n+1 ở trạng thái `NHAP` ("Lưu nháp" lần đầu của SCR-0701; bước đầu khi nộp lại sau `YEU_CAU_BO_SUNG` hoặc `TU_CHOI`). Tham chiếu các tệp đã tải lên bằng `files[].objectId` lấy từ `POST /files/uploads/{uploadId}/complete`. Không được tạo phiên bản mới khi phiên bản mới nhất còn `NHAP`, `DA_NOP` hoặc `DANG_NGHIEM_THU`. Thay cho phần lưu nháp của TL-01 PL C `POST /products/{id}/evidence-versions`.
     */
    post: operations['createEvidenceVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Chi tiết một phiên bản minh chứng
     * @description Trả phiên bản kèm thông tin tệp, mã băm, kết quả quét, cờ trùng, giải trình, kết quả nghiệm thu (nếu có) và danh sách tiêu chuẩn chất lượng của mục danh mục để đối chiếu ở SCR-0703. Mở minh chứng ghi nhật ký truy cập (SCR-0902). Với tệp C3 (`hasE2ee`), trả thêm khoá nội dung đã bọc cho người gọi.
     */
    get: operations['getEvidenceVersion'];
    /**
     * Lưu nháp phiên bản minh chứng
     * @description Sửa phiên bản đang `NHAP` (đổi tệp, giải trình dùng lại, trường văn bản, xác nhận ô do AI điền). Phiên bản khác `NHAP` không sửa được (BR-07-01, trigger đóng băng của TL-03).
     */
    put: operations['updateEvidenceVersionDraft'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}/submit': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Nộp phiên bản minh chứng để nghiệm thu
     * @description Chuyển phiên bản `NHAP` → `DA_NOP`, ghi `submittedAt`, `attestedAt`, phát EVT-07. Khi số phiên bản `DA_NOP` đủ số lượng sản phẩm, nhiệm vụ chuyển `DANG_THUC_HIEN` → `CHO_NGHIEM_THU` và phát EVT-06 (ngày nộp là ngày hoàn thành tạm). Máy chủ kiểm: mã băm khớp (BR-07-02), mọi tệp quét sạch (BR-07-04), có giải trình khi trùng mã băm (BR-07-03), có cam kết, ô do AI điền đã được xác nhận (BR-07-09). Dùng chung cho SCR-0701 và SCR-0702 ("Xác nhận và nộp"). Thay cho phần nộp của TL-01 PL C `POST /products/{id}/evidence-versions`.
     */
    post: operations['submitEvidenceVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}/open-review': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Mở nghiệm thu và kiểm toàn vẹn phiên bản
     * @description Người nghiệm thu được chỉ định mở minh chứng: lần đầu chuyển `DA_NOP` → `DANG_NGHIEM_THU`; mọi lần mở đều kiểm lại mã băm của tệp với `evidence.object_version.sha256`. Không khớp thì chặn nghiệm thu, phát cảnh báo an ninh mức cao (MSG-07-13). Gọi lại khi đã `DANG_NGHIEM_THU` chỉ kiểm toàn vẹn, không đổi trạng thái.
     */
    post: operations['openEvidenceReview'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}/accept': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xác nhận nghiệm thu với mức chất lượng năm mức
     * @description Ghi `product.acceptance` với quyết định `DA_XAC_NHAN`, chuyển phiên bản `DANG_NGHIEM_THU` → `DA_XAC_NHAN`, phát EVT-08 (scoring-service tính dần A, B, C). Khi mọi sản phẩm đã xác nhận, nhiệm vụ chuyển `CHO_NGHIEM_THU` → `HOAN_THANH` và phát EVT-06. Cần phiên AAL2 (BR-07-11). Không huỷ xác nhận được; sai sót sau xác nhận xử lý bằng hậu kiểm QT-13. Thay cho TL-01 PL C `POST /evidence-versions/{id}/acceptance`.
     */
    post: operations['acceptEvidenceVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}/request-revision': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Yêu cầu bổ sung minh chứng
     * @description Ghi `product.acceptance` với quyết định `YEU_CAU_BO_SUNG` và nội dung cần bổ sung (bắt buộc, BR-07-08); phiên bản chuyển `DANG_NGHIEM_THU` → `YEU_CAU_BO_SUNG` và kết thúc; nhiệm vụ trở về `DANG_THUC_HIEN` (EVT-06); người nộp được thông báo (EVT-21) và tạo phiên bản mới. Thay cho TL-01 PL C `POST /evidence-versions/{id}/supplement-requests`.
     */
    post: operations['requestEvidenceRevision'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}/reject': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Từ chối minh chứng
     * @description Ghi `product.acceptance` với quyết định `TU_CHOI` và lý do (bắt buộc); phiên bản chuyển `DANG_NGHIEM_THU` → `TU_CHOI`; nhiệm vụ trở về `DANG_THUC_HIEN` (EVT-06); người nộp được thông báo (EVT-21). B = 0 nếu không nộp lại trước hạn nghiệm thu tồn đọng.
     */
    post: operations['rejectEvidenceVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}/key-wraps': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Bổ sung khoá bọc E2EE cho phiên bản minh chứng C3
     * @description Thêm các bản ghi `product.e2ee_key_wrap` cho người thẩm định, thành viên hội đồng hoặc người nhận uỷ quyền cần giải mã minh chứng C3 trên trình duyệt (ADR-06; TL-01 chương 9).
     */
    post: operations['addEvidenceVersionKeyWraps'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/evidences/{id}/versions/{v}/recovery-wraps': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Nạp khoá bọc E2EE của người nhận sau khi khôi phục bằng khoá phục hồi tổ chức
     * @description Sau buổi khôi phục khi người nhận mất khoá thiết bị (TL-01 "Khôi phục khi mất khoá"), quản trị tenant đã ghi biên bản bằng `recordE2eeRecovery` của SVC-04 và bọc lại khoá của tệp C3 cho khoá mới của người đó. Máy chủ đối chiếu biên bản `recoveryId` qua SVC-04: `objectType` là `EVIDENCE_FILE`, `objectId` là mã phiên bản minh chứng, `newRecipientId` khớp `keyWrap.recipientId`, biên bản ghi trong 24 giờ; sai trả 422. Bản bọc mới thay bản bọc cũ của người đó trên phiên bản; người nhận phải đang có hoặc từng có bản bọc.
     */
    post: operations['restoreEvidenceKeyWrap'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/uploads': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Khởi tạo phiên tải lên nhiều phần
     * @description Khởi tạo phiên tải lên (`evidence.upload_session`) với mã băm SHA-256 và kích thước khai báo trước (tính trên trình duyệt hoặc thiết bị). Trả `uploadId`, kích thước phần (5 đến 16 MB) và thời hạn phiên. Giới hạn tệp 50 MB mặc định, tenant cấu hình tối đa 200 MB; ảnh chụp di động chuyển PDF trên thiết bị, ≤ 20 MB (SEC-48). Với tệp C3 (`e2ee: true`), nội dung tải lên là bản mã, mã băm khai báo là băm của bản mã; máy chủ không quét và không khử trùng được (TL-03 `product.e2ee_key_wrap`), việc quét chạy trên thiết bị trước khi mã hoá.
     */
    post: operations['createFileUpload'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/uploads/{uploadId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Trạng thái phiên tải lên để tải tiếp
     * @description Trả số phần đã nhận và trạng thái phiên để trình duyệt tải tiếp sau khi mất kết nối (ngoại lệ QT-07 "Mất kết nối giữa chừng"). Chỉ người khởi tạo đọc được. Thao tác suy luận từ TL-01 ("hỗ trợ tải tiếp khi mất mạng").
     */
    get: operations['getFileUpload'];
    put?: never;
    post?: never;
    /**
     * Huỷ phiên tải lên
     * @description Huỷ phiên đang `MO`, chuyển `HUY` và huỷ multipart dở dang trên Ceph RGW. Thao tác suy luận từ trạng thái `HUY` của `evidence.upload_session`.
     */
    delete: operations['cancelFileUpload'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/uploads/{uploadId}/parts/{n}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Gửi một phần của tệp
     * @description Gửi phần thứ `n` (5 đến 16 MB, phần cuối có thể nhỏ hơn) dạng nhị phân. Gửi lại cùng phần là an toàn (ghi đè phần chưa hoàn tất). `Idempotency-Key` nên là khoá của phiên ghép với số phần. Chỉ người khởi tạo phiên được gửi.
     */
    put: operations['uploadFilePart'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/uploads/{uploadId}/complete': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Hoàn tất tải lên và nhận mã băm
     * @description Ghép các phần, tính lại SHA-256 trên toàn tệp và so với băm khai báo (BR-07-02), kiểm tra định dạng thật của tệp (SEC-48), ghi đối tượng vào kho có Object Lock chế độ compliance, tra `evidence.dedup_index` trong phạm vi tenant, rồi xếp quét mã độc bất đồng bộ (SEC-47, ≤ 2 phút). Trả `objectId`, `sha256`, `scanStatus` (thường `CHO_QUET`) và thông tin trùng mã băm nếu có (MSG-07-01). SVC-08 phát sự kiện kỹ thuật `evidence.object.stored.v1` qua outbox. Trạng thái quét đọc lại bằng `GET /files/{objectId}/scan` hoặc qua luồng SSE thông báo.
     */
    post: operations['completeFileUpload'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/{objectId}/download-url': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Lấy URL tải tệp có hạn 5 phút
     * @description Kiểm quyền qua OPA theo quyền trên bản ghi nghiệp vụ sở hữu tệp (với minh chứng: quyền đọc phiên bản minh chứng), ghi nhật ký truy cập (bắt buộc với tệp C2, SCR-0902, SCR-1603), rồi trả URL ký trước sống 5 phút, phạm vi đúng một khoá đối tượng. Với tệp C3, tệp tải về là bản mã; trình duyệt giải mã bằng khoá nội dung đã bọc trả trong `EvidenceVersion.keyWrap` (ADR-06). Không trả tệp nhiễm mã độc. Đây là đường dẫn duy nhất để mở nội dung tệp; ma trận ghi `GET /evidences/{id}/versions/{v}/content` cho SCR-1603 được ánh xạ về đây.
     */
    get: operations['getFileDownloadUrl'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/{objectId}/preview': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Ảnh xem trước từng trang của tệp
     * @description Phục vụ trình xem minh chứng theo trang ở SCR-0703 vùng 1 ("trang 1 trên 6") và SCR-0902 mà không phải tải thư viện dựng PDF hay Office lớn về trình duyệt. SVC-08 dựng ảnh từng trang (PDF, DOCX, XLSX, ảnh) ở tác vụ nền sau khi tệp quét sạch và trả URL ký trước sống 5 phút cho mỗi trang, cùng quy tắc kiểm quyền và ghi nhật ký truy cập như `getFileDownloadUrl`. Khi ảnh chưa dựng xong, `status` là `DANG_TAO` kèm `Retry-After`. Tệp C3 (E2EE) không dựng được ở máy chủ nên trả `KHONG_HO_TRO`; giao diện tải bản mã qua `getFileDownloadUrl` để giải mã và hiển thị tại trình duyệt. (suy luận: TL-02 vẽ trình xem theo trang nhưng TL chưa đặc tả API.)
     */
    get: operations['getFilePreview'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/{objectId}/scan': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Kết quả quét mã độc của tệp
     * @description Trả trạng thái quét (`CHO_QUET`, `SACH`, `NHIEM`, `LOI`) của phiên bản đối tượng và kết luận chi tiết của bộ quét (`evidence.scan_result`). Trình duyệt hỏi lại có giãn cách (hoặc nghe SSE) cho tới khi khác `CHO_QUET`; cột "Kết quả quét" của SCR-0701 hiện từ đây. Tệp nhiễm bị cách ly (MSG-07-02).
     */
    get: operations['getFileScanResult'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/files/{objectId}/rescan': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã đối tượng logic (`evidence.object_meta.id`). */
        objectId: components['parameters']['ObjectId'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Yêu cầu quét lại mã độc cho tệp bị lỗi quét
     * @description Khi bộ quét mã độc gặp sự cố (`scanStatus = LOI`), người nộp hoặc quản trị yêu cầu đưa tệp vào lại hàng đợi quét mà không phải tải lên lại từ đầu (TL-02a QT-07 ngoại lệ, BR-07-04).
     */
    post: operations['rescanFileObject'];
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
     * @description Loại sản phẩm (`product.deliverable.deliverable_type`, TL-03).
     * @enum {string}
     */
    DeliverableType: 'VAN_BAN' | 'BAO_CAO' | 'SO_LIEU' | 'SU_KIEN' | 'KHAC';
    /**
     * @description Trạng thái tổng hợp của sản phẩm (`product.deliverable.status`, TL-03).
     * @enum {string}
     */
    DeliverableStatus: 'CHUA_CO' | 'DANG_THUC_HIEN' | 'DA_XAC_NHAN_MOT_PHAN' | 'DA_XAC_NHAN';
    /**
     * @description Sản phẩm của nhiệm vụ (`product.deliverable`).
     * @example {
     *       "id": "0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4a01",
     *       "taskId": "0192a0f1-2b3c-7d4e-9f50-6a7b8c9d0e11",
     *       "name": "Báo cáo chuyên đề công tác kiểm tra quý III",
     *       "deliverableType": "BAO_CAO",
     *       "unit": "báo cáo",
     *       "quantityPlanned": 1,
     *       "quantityAccepted": 1,
     *       "status": "DA_XAC_NHAN",
     *       "rowVersion": 4
     *     }
     */
    Deliverable: {
      id: components['schemas']['Uuid'];
      taskId: components['schemas']['Uuid'];
      name: string;
      deliverableType: components['schemas']['DeliverableType'];
      /** @description Đơn vị tính */
      unit: string;
      /** @description Số lượng kế hoạch */
      quantityPlanned: number;
      /** @description Số lượng đã được xác nhận (đầu vào cấu phần A) */
      quantityAccepted: number;
      status: components['schemas']['DeliverableStatus'];
      rowVersion: components['schemas']['RowVersion'];
    };
    DeliverableCreate: {
      taskId: components['schemas']['Uuid'];
      name: string;
      deliverableType: components['schemas']['DeliverableType'];
      unit: string;
      quantityPlanned: number;
    };
    DeliverableList: {
      items: components['schemas']['Deliverable'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Trạng thái nghiệm thu của phiên bản minh chứng (`product.evidence_version.status`, baseline 9.1, TL-02a Hình 8.12). `YEU_CAU_BO_SUNG` và `TU_CHOI` là trạng thái kết thúc; nộp lại tạo phiên bản mới.
     * @enum {string}
     */
    EvidenceVersionStatus:
      'NHAP' | 'DA_NOP' | 'DANG_NGHIEM_THU' | 'DA_XAC_NHAN' | 'YEU_CAU_BO_SUNG' | 'TU_CHOI';
    /**
     * @description Nguồn minh chứng (`product.evidence.source_type`, TL-03). TL-02 SCR-0701 ghi bộ khác (`VAN_BAN`, `TEP`, `LIEN_KET`, `ANH_CHUP`); hợp đồng theo TL-03.
     * @enum {string}
     */
    EvidenceSourceType: 'TAI_LEN' | 'VAN_BAN' | 'HE_THONG';
    /**
     * @description Nguồn của phiên bản (`product.evidence_version.source`, TL-03).
     * @enum {string}
     */
    EvidenceVersionSource: 'TAI_LEN' | 'VAN_BAN_HE_THONG' | 'OCR_DI_DONG' | 'TICH_HOP';
    /**
     * @description Trạng thái quét mã độc (`evidence.object_version.scan_status`, `product.evidence_version.scan_status`).
     * @enum {string}
     */
    FileScanStatus: 'CHO_QUET' | 'SACH' | 'NHIEM' | 'LOI';
    /**
     * @description Kết luận của bộ quét (`evidence.scan_result.verdict`).
     * @enum {string}
     */
    FileScanVerdict: 'SACH' | 'NHIEM' | 'NGHI_NGO' | 'LOI';
    /**
     * @description Mã băm SHA-256 dạng 64 ký tự hex chữ thường (cột `bytea` 32 byte của TL-03). Với tệp C3 là băm của bản mã.
     * @example 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
     */
    Sha256Hex: string;
    /** @description Nhiệm vụ rút gọn (`task.task`). */
    ProductTaskRef: {
      id: components['schemas']['Uuid'];
      /** @example NV-2026-Q4-0142 */
      code: string;
      name: string;
    };
    /** @description Minh chứng khác trong tenant có cùng mã băm (`evidence.dedup_index`); hiện ở vùng 3 SCR-0701 (MSG-07-01) và SCR-0703 (MSG-07-11). Không bao giờ so trùng giữa các tenant. */
    EvidenceDuplicateMatch: {
      /** @description Mã nhiệm vụ có minh chứng trùng */
      taskCode: string;
      /** @description Người nộp minh chứng trùng */
      ownerName?: string;
      submittedAt?: components['schemas']['DateTime'];
    };
    /** @description Một dòng trong lịch sử phiên bản (vùng 4 SCR-0701). */
    EvidenceVersionSummary: {
      versionNo: number;
      status: components['schemas']['EvidenceVersionStatus'];
      submittedAt?: components['schemas']['DateTime'];
      decidedAt?: components['schemas']['DateTime'];
      qualityLevel?: components['schemas']['QualityLevel'];
      /** @description Tóm tắt nhận xét nghiệm thu, chỉ trả cho người có quyền đọc C2. */
      decisionNote?: string;
    };
    /**
     * @description Minh chứng của một sản phẩm (`product.evidence`) kèm lịch sử phiên bản.
     * @example {
     *       "id": "0192a1b2-4d5e-7f60-8b1c-2d3e4f5a6b02",
     *       "deliverableId": "0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4a01",
     *       "task": {
     *         "id": "0192a0f1-2b3c-7d4e-9f50-6a7b8c9d0e11",
     *         "code": "NV-2026-Q4-0142",
     *         "name": "Báo cáo chuyên đề công tác kiểm tra quý III"
     *       },
     *       "title": "Báo cáo chuyên đề công tác kiểm tra quý III",
     *       "sourceType": "VAN_BAN",
     *       "externalDocRef": "47-BC/BTCĐUK",
     *       "currentVersionNo": 2,
     *       "currentStatus": "DA_NOP",
     *       "rowVersion": 4
     *     }
     */
    Evidence: {
      id: components['schemas']['Uuid'];
      deliverableId: components['schemas']['Uuid'];
      task: components['schemas']['ProductTaskRef'];
      title: string;
      sourceType: components['schemas']['EvidenceSourceType'];
      /** @description Văn bản bên ngoài làm minh chứng */
      externalDocRef?: string | null;
      /** @description Số phiên bản mới nhất */
      currentVersionNo?: number | null;
      /** @description Trạng thái của phiên bản mới nhất (`current_status`); null khi chưa có phiên bản. */
      currentStatus?: components['schemas']['EvidenceVersionStatus'] | null;
      /** @description Phiên bản mới nhất nghi trùng mã băm */
      isDuplicateSuspect?: boolean;
      submittedBy?: components['schemas']['Ref'];
      submittedAt?: components['schemas']['DateTime'];
      /** @description Chỉ có ở `GET /evidences/{id}`; danh sách bỏ trống. */
      versions?: components['schemas']['EvidenceVersionSummary'][];
      rowVersion: components['schemas']['RowVersion'];
    };
    EvidenceCreate: {
      deliverableId: components['schemas']['Uuid'];
      title: string;
      sourceType: components['schemas']['EvidenceSourceType'];
      /** @description Bắt buộc khi `sourceType` là `VAN_BAN` */
      externalDocRef?: string;
    };
    EvidenceList: {
      items: components['schemas']['Evidence'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Văn bản đã ban hành lấy từ vOffice qua SVC-13 (`integ.mapping_xref`). */
    EvidenceExternalDoc: {
      /** @description Mã tham chiếu dùng cho `externalDocRef`, `sourceDocRef` */
      ref: string;
      /** @description Số, ký hiệu */
      docNumber: string;
      docDate: components['schemas']['Date'];
      /** @description Trích yếu */
      summary: string;
      /** @description Người ký */
      signerName?: string;
      /** @description Chữ ký số gốc hợp lệ khi nhận (BR-07-13) */
      signatureValid?: boolean;
    };
    EvidenceExternalDocList: {
      items: components['schemas']['EvidenceExternalDoc'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Khoá nội dung của tệp C3 đã bọc bằng khoá công khai của một người nhận (`product.e2ee_key_wrap`, `object_type = EVIDENCE_FILE`, ADR-06). Máy chủ chỉ giữ bản bọc, không bao giờ thấy khoá rõ. Cấu trúc trường theo `task.e2ee_key_wrap` (suy luận). */
    EvidenceKeyWrap: {
      recipientId: components['schemas']['Uuid'];
      /** @description Khoá nội dung đã bọc */
      wrappedKey: string;
      /**
       * @description Thuật toán bọc khoá
       * @enum {string}
       */
      alg: 'ECDH-ES+A256KW' | 'RSA-OAEP-256';
    };
    /** @description Tiêu chuẩn chất lượng của mục danh mục (`task.catalog_item.quality_criteria`). */
    EvidenceQualityCriterion: {
      code: string;
      text: string;
    };
    /** @description Kết quả đối chiếu một tiêu chuẩn (`product.acceptance.criteria_results`). */
    EvidenceCriterionResult: {
      criterionCode: string;
      /** @description Đạt hoặc Chưa đạt */
      passed: boolean;
      note?: string;
    };
    /**
     * @description Phiên bản minh chứng (`product.evidence_version`) gồm một hoặc nhiều tệp (`files`). Sau khi rời `NHAP`, danh sách tệp và mã băm đóng băng (BR-07-01). Trường do AI điền chỉ có hiệu lực khi người nộp xác nhận (BR-07-09).
     * @example {
     *       "id": "0192a1d4-6f70-7b92-8d3e-4f5a6b7c8d04",
     *       "evidenceId": "0192a1b2-4d5e-7f60-8b1c-2d3e4f5a6b02",
     *       "taskId": "0192a0f1-2b3c-7d4e-9f50-6a7b8c9d0e11",
     *       "versionNo": 2,
     *       "status": "DA_NOP",
     *       "source": "OCR_DI_DONG",
     *       "files": [
     *         {
     *           "objectId": "0192a1c3-5e6f-7a81-9c2d-3e4f5a6b7c03",
     *           "contentSha256": "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b",
     *           "sizeBytes": 812000,
     *           "mediaType": "image/jpeg",
     *           "fileName": "bao-cao-trang-1.jpg",
     *           "scanStatus": "SACH",
     *           "isDuplicateSuspect": false
     *         }
     *       ],
     *       "scanStatus": "SACH",
     *       "isDuplicateSuspect": false,
     *       "hasE2ee": false,
     *       "aiPrefill": true,
     *       "userConfirmedAt": "2026-10-19T09:11:00+07:00",
     *       "docNumber": "47-BC/BTCĐUK",
     *       "docDate": "2026-10-19",
     *       "docSummary": "Báo cáo chuyên đề công tác kiểm tra quý III năm 2026",
     *       "rowVersion": 5
     *     }
     */
    EvidenceVersion: {
      id: components['schemas']['Uuid'];
      evidenceId: components['schemas']['Uuid'];
      taskId: components['schemas']['Uuid'];
      versionNo: number;
      status: components['schemas']['EvidenceVersionStatus'];
      source: components['schemas']['EvidenceVersionSource'];
      sourceDocRef?: string | null;
      /** @description Các tệp của phiên bản theo thứ tự hiển thị (vùng 2 SCR-0701). Rỗng khi nháp chưa có tệp. */
      files: components['schemas']['EvidenceVersionFile'][];
      /** @description Trạng thái quét tổng hợp của các tệp: `NHIEM` nếu có tệp nhiễm, `LOI` nếu có tệp lỗi, `CHO_QUET` nếu còn tệp đang quét, `SACH` khi mọi tệp sạch (cột `scan_status` của TL-03). */
      scanStatus: components['schemas']['FileScanStatus'];
      /** @description Đúng khi có ít nhất một tệp trùng mã băm (cột `is_duplicate_suspect`) */
      isDuplicateSuspect: boolean;
      /** @description Tệp C3 mã hoá đầu cuối; máy chủ chỉ giữ bản mã */
      hasE2ee: boolean;
      /** @description Khoá nội dung đã bọc cho chính người gọi khi `hasE2ee` (suy luận). */
      keyWrap?: components['schemas']['EvidenceKeyWrap'] | null;
      /** @description Có trường do AI, OCR điền sẵn (SCR-0702) */
      aiPrefill: boolean;
      aiSuggestionId?: components['schemas']['Uuid'] | null;
      /** @description Thời điểm người nộp xác nhận các ô do AI điền. */
      userConfirmedAt?: components['schemas']['DateTime'] | null;
      /** @description Số, ký hiệu văn bản (NĐ 30/2020) */
      docNumber?: string | null;
      docDate?: components['schemas']['Date'] | null;
      /** @description Trích yếu (SCR-0702 giới hạn 500 ký tự khi nhập) */
      docSummary?: string | null;
      /** @description Ghi chú của người nộp */
      note?: string | null;
      submittedBy?: components['schemas']['Ref'];
      submittedAt?: components['schemas']['DateTime'];
      attestedAt?: components['schemas']['DateTime'];
      acceptedAt?: components['schemas']['DateTime'];
      /** @description Kỳ mà phiên bản không được tính (còn `DA_NOP` khi khoá kỳ, QT-08). */
      excludedPeriodId?: components['schemas']['Uuid'] | null;
      /** @description Kết quả kiểm toàn vẹn mã băm lần mở gần nhất (SCR-0703). */
      integrity?: {
        verified: boolean;
        checkedAt: components['schemas']['DateTime'];
      };
      /** @description Tiêu chuẩn chất lượng của mục danh mục để đối chiếu ở SCR-0703. */
      qualityCriteria?: components['schemas']['EvidenceQualityCriterion'][];
      /** @description Có phiên bản trước bị yêu cầu bổ sung; khi đúng, nghiệm thu phải chọn nguyên nhân chỉnh sửa. */
      hasPriorRevisionRequest?: boolean;
      /** @description Quyết định nghiệm thu của phiên bản này, nếu đã có. */
      acceptance?: components['schemas']['EvidenceAcceptance'] | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Một tệp của phiên bản minh chứng (một dòng ở vùng 2 SCR-0701). TL-03 chưa có bảng này; đề xuất bảng con `product.evidence_version_file` mang các cột tệp hiện đặt trên `product.evidence_version` (suy luận). */
    EvidenceVersionFile: {
      /** @description Đối tượng ở SVC-08 (`evidence.object_meta.id`). */
      objectId: components['schemas']['Uuid'];
      objectVersionId?: components['schemas']['Uuid'] | null;
      contentSha256: components['schemas']['Sha256Hex'];
      sizeBytes: number;
      mediaType: string;
      /** @description Tên tệp gốc. TL-03 xếp `product.evidence_version.file_name` C1 nhưng `evidence.object_meta.file_name_enc` C2; hợp đồng xếp C2 để thống nhất. */
      fileName: string;
      scanStatus: components['schemas']['FileScanStatus'];
      isDuplicateSuspect: boolean;
      duplicateOf?: components['schemas']['EvidenceDuplicateMatch'] | null;
      /** @description Giải trình dùng lại khi tệp này trùng mã băm (BR-07-03); bắt buộc với từng tệp trùng trước khi nộp. */
      reuseJustification?: string | null;
    };
    /** @description Một tệp khi lưu nháp phiên bản. */
    EvidenceVersionFileInput: {
      objectId: components['schemas']['Uuid'];
      /** @description Phiên bản đối tượng; mặc định phiên bản hiện hành */
      objectVersionNo?: number;
      reuseJustification?: string;
    };
    /** @description Nội dung phiên bản khi lưu nháp. `files` thay toàn bộ danh sách tệp của nháp. Khi nộp, phải có ít nhất một tệp với nguồn tải lên, OCR di động; bắt buộc `sourceDocRef` với nguồn văn bản hệ thống (tệp kèm theo là phụ lục). */
    EvidenceVersionDraft: {
      source: components['schemas']['EvidenceVersionSource'];
      sourceDocRef?: string;
      /** @description Tệp của phiên bản theo thứ tự hiển thị; giới hạn 20 tệp là suy luận. */
      files?: components['schemas']['EvidenceVersionFileInput'][];
      /** @default false */
      aiPrefill: boolean;
      aiSuggestionId?: components['schemas']['Uuid'];
      /** @description Tên các ô do AI điền mà người nộp đã sửa (ghi vết BR-07-09). */
      aiFieldsEdited?: ('docNumber' | 'docDate' | 'docSummary' | 'taskId')[];
      docNumber?: string;
      docDate?: components['schemas']['Date'];
      docSummary?: string;
      note?: string;
      /** @description Bắt buộc khi tệp là C3; khoá nội dung bọc cho người nộp và người nghiệm thu (suy luận). */
      keyWraps?: components['schemas']['EvidenceKeyWrap'][];
    };
    EvidenceSubmit: {
      /**
       * @description Cam kết tính xác thực (ô "Cam kết" SCR-0701); máy chủ ghi `attestedAt`.
       * @constant
       */
      attested: true;
      /** @description Người nộp xác nhận các ô do AI điền; bắt buộc đúng khi `aiPrefill` (BR-07-09). */
      aiFieldsConfirmed?: boolean;
    };
    EvidenceSubmitResult: {
      version: components['schemas']['EvidenceVersion'];
      reviewer?: components['schemas']['Ref'];
      /** @description Trạng thái mới của nhiệm vụ nếu đổi (EVT-06), ví dụ `CHO_NGHIEM_THU`. */
      taskStatusChanged?: string | null;
    };
    /**
     * @description Quyết định nghiệm thu (`product.acceptance.decision`, TL-03).
     * @enum {string}
     */
    EvidenceAcceptanceDecision: 'DA_XAC_NHAN' | 'YEU_CAU_BO_SUNG' | 'TU_CHOI';
    EvidenceRevisionCause: components['schemas']['RevisionCause'];
    /**
     * @description Bản ghi nghiệm thu (`product.acceptance`).
     * @example {
     *       "id": "0192a1e5-7081-7ca3-9e4f-5a6b7c8d9e05",
     *       "evidenceVersionId": "0192a1d4-6f70-7b92-8d3e-4f5a6b7c8d04",
     *       "decision": "DA_XAC_NHAN",
     *       "qualityLevel": 0.75,
     *       "revisionCause": "NOI_DUNG_KHONG_COT_LOI",
     *       "isObjectiveRevision": false,
     *       "revisionCount": 1,
     *       "comment": "Đã bổ sung đầy đủ số liệu ở phiên bản 2.",
     *       "reviewer": {
     *         "id": "0192a0bb-2222-7333-8444-555566667777",
     *         "name": "Lê Minh Châu"
     *       },
     *       "decidedAt": "2026-10-19T10:05:00+07:00"
     *     }
     */
    EvidenceAcceptance: {
      id: components['schemas']['Uuid'];
      evidenceVersionId: components['schemas']['Uuid'];
      decision: components['schemas']['EvidenceAcceptanceDecision'];
      qualityLevel?: components['schemas']['QualityLevel'];
      criteriaResults?: components['schemas']['EvidenceCriterionResult'][];
      revisionCause?: components['schemas']['RevisionCause'];
      /** @description Chỉnh sửa do nguyên nhân khách quan (ảnh hưởng cách tính C). */
      isObjectiveRevision?: boolean;
      revisionCount?: number;
      /** @description Nhận xét nghiệm thu (`comment_enc`); chỉ trả cho người có quyền đọc C2. */
      comment?: string;
      reviewer: components['schemas']['Ref'];
      /** @description Người nghiệm thu được chỉ định khi `reviewer` nghiệm thu thay theo uỷ quyền (`product.acceptance.on_behalf_of`, TL-03 bổ sung 23/10/2026); vắng khi nghiệm thu trực tiếp. Màn hình hiện nhãn "nghiệm thu thay" (SCR-0703, CORE-03-08). */
      onBehalfOf?: components['schemas']['Ref'];
      /** @description Uỷ quyền nghiệm thu thay đã dùng (`product.acceptance.delegation_id`); có khi và chỉ khi có `onBehalfOf`. */
      delegationId?: components['schemas']['Uuid'];
      decidedAt: components['schemas']['DateTime'];
    };
    /** @description Xác nhận nghiệm thu. `comment` bắt buộc khi `qualityLevel` < 1 (BR-07-08); `revisionCause` và `isObjectiveRevision` bắt buộc khi có phiên bản trước bị yêu cầu bổ sung. Mọi tiêu chuẩn chất lượng phải có kết quả (MSG-07-09). */
    EvidenceAcceptRequest: {
      qualityLevel: components['schemas']['QualityLevel'];
      criteriaResults: components['schemas']['EvidenceCriterionResult'][];
      revisionCause?: components['schemas']['RevisionCause'];
      isObjectiveRevision?: boolean;
      comment?: string;
    };
    /** @description Yêu cầu bổ sung hoặc từ chối; nhận xét, lý do bắt buộc (BR-07-08). */
    EvidenceReturnRequest: {
      criteriaResults?: components['schemas']['EvidenceCriterionResult'][];
      comment: string;
    };
    EvidenceDecisionResult: {
      acceptance: components['schemas']['EvidenceAcceptance'];
      versionStatus: components['schemas']['EvidenceVersionStatus'];
      /** @description Trạng thái mới của nhiệm vụ nếu đổi (EVT-06), ví dụ `HOAN_THANH`, `DANG_THUC_HIEN`. */
      taskStatusChanged?: string | null;
    };
    /**
     * @description Trạng thái phiên tải lên (`evidence.upload_session.status`, TL-03).
     * @enum {string}
     */
    FileUploadStatus: 'MO' | 'HOAN_TAT' | 'HUY';
    FileUploadCreate: {
      /** @description Tên tệp gốc; SVC-08 lưu mã hoá (`file_name_enc`). */
      fileName: string;
      /**
       * @description Kiểu MIME khai báo; `application/octet-stream` chỉ dùng cho bản mã C3.
       * @enum {string}
       */
      mediaType:
        | 'application/pdf'
        | 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        | 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
        | 'image/jpeg'
        | 'image/png'
        | 'application/octet-stream';
      /** @description Kích thước byte khai báo */
      declaredSize: number;
      declaredSha256: components['schemas']['Sha256Hex'];
      /**
       * @description Module sở hữu (`evidence.object_meta.owner_module`).
       * @enum {string}
       */
      ownerModule: 'product' | 'dossier' | 'oversight';
      /** @description Bản ghi nghiệp vụ sở hữu nếu đã có (ví dụ minh chứng); có thể gắn sau khi nộp (suy luận). */
      ownerRefId?: components['schemas']['Uuid'];
      /**
       * @description Phân loại tệp (`evidence.object_meta.classification`).
       * @enum {string}
       */
      classification: 'C1' | 'C2' | 'C3';
      /**
       * @description Nội dung là bản mã đầu cuối (bắt buộc đúng khi `classification` là C3, ADR-06).
       * @default false
       */
      e2ee: boolean;
    };
    /**
     * @description Phiên tải lên nhiều phần (`evidence.upload_session`).
     * @example {
     *       "uploadId": "0192a1c2-1a2b-7c3d-8e4f-5a6b7c8d9e10",
     *       "status": "MO",
     *       "partSize": 8388608,
     *       "partCount": 1,
     *       "partsDone": 0,
     *       "expiresAt": "2026-10-19T09:20:00+07:00"
     *     }
     */
    FileUploadSession: {
      uploadId: components['schemas']['Uuid'];
      status: components['schemas']['FileUploadStatus'];
      /** @description Kích thước mỗi phần (byte) */
      partSize: number;
      /** @description Số phần cần gửi */
      partCount?: number;
      partsDone: number;
      /** @description Các phần đã nhận, để tải tiếp. */
      receivedParts?: components['schemas']['FileUploadPart'][];
      expiresAt: components['schemas']['DateTime'];
    };
    FileUploadPart: {
      partNo: number;
      size?: number;
      /** @description ETag của phần do kho trả, gửi lại khi hoàn tất */
      etag: string;
    };
    FileUploadComplete: {
      parts: components['schemas']['FileUploadPart'][];
    };
    /** @description Kết quả tra `evidence.dedup_index` trong phạm vi tenant. */
    FileDedup: {
      duplicate: boolean;
      matches?: components['schemas']['EvidenceDuplicateMatch'][];
    };
    /**
     * @description Đối tượng đã lưu ở SVC-08 (`evidence.object_meta` và `evidence.object_version`).
     * @example {
     *       "objectId": "0192a1c3-5e6f-7a81-9c2d-3e4f5a6b7c03",
     *       "objectVersionId": "0192a1c3-5e6f-7a81-9c2d-3e4f5a6b7c13",
     *       "versionNo": 1,
     *       "mediaType": "application/pdf",
     *       "sizeBytes": 2457600,
     *       "sha256": "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b",
     *       "classification": "C1",
     *       "e2ee": false,
     *       "scanStatus": "CHO_QUET",
     *       "dedup": {
     *         "duplicate": false
     *       },
     *       "createdAt": "2026-10-19T09:10:30+07:00"
     *     }
     */
    FileObject: {
      objectId: components['schemas']['Uuid'];
      objectVersionId: components['schemas']['Uuid'];
      versionNo: number;
      fileName?: string;
      /** @description Kiểu MIME đã xác minh */
      mediaType: string;
      sizeBytes: number;
      sha256: components['schemas']['Sha256Hex'];
      classification: components['schemas']['Classification'];
      e2ee: boolean;
      scanStatus: components['schemas']['FileScanStatus'];
      /** @description Không có với tệp C3 (không khử trùng được bản mã). */
      dedup?: components['schemas']['FileDedup'];
      createdAt: components['schemas']['DateTime'];
    };
    /** @description URL ký trước sống 5 phút, phạm vi một khoá đối tượng. */
    FileDownloadUrl: {
      /** Format: uri */
      url: string;
      expiresAt: components['schemas']['DateTime'];
      mediaType?: string;
      sizeBytes?: number;
      sha256: components['schemas']['Sha256Hex'];
      /** @description Tệp tải về là bản mã, giải mã tại trình duyệt */
      e2ee: boolean;
    };
    /**
     * @description Trạng thái ảnh xem trước (suy luận).
     * @enum {string}
     */
    FilePreviewStatus: 'DANG_TAO' | 'SAN_SANG' | 'KHONG_HO_TRO' | 'LOI';
    FilePreviewPage: {
      page: number;
      /**
       * Format: uri
       * @description URL ký trước của ảnh trang (WebP), sống 5 phút
       */
      url: string;
      width?: number;
      height?: number;
    };
    FilePreview: {
      status: components['schemas']['FilePreviewStatus'];
      /** @description Tổng số trang; null khi chưa dựng xong */
      pageCount?: number | null;
      /** @description Mã băm của tệp gốc đã kiểm toàn vẹn */
      sha256: components['schemas']['Sha256Hex'];
      pages: components['schemas']['FilePreviewPage'][];
      expiresAt?: components['schemas']['DateTime'];
    };
    /** @description Kết quả quét (`evidence.scan_result` và `evidence.object_version.scan_status`). */
    FileScanResult: {
      objectId: components['schemas']['Uuid'];
      objectVersionId: components['schemas']['Uuid'];
      scanStatus: components['schemas']['FileScanStatus'];
      /** @description Kết luận của bộ quét; vắng khi còn `CHO_QUET`. */
      verdict?: components['schemas']['FileScanVerdict'];
      engine?: string;
      engineVersion?: string;
      scannedAt?: components['schemas']['DateTime'];
    };
    EvidenceRecoveryWrapRequest: {
      recoveryId: components['schemas']['Uuid'];
      keyWrap: components['schemas']['EvidenceKeyWrap'];
    };
    EvidenceKeyWrapBatchInput: {
      keyWraps: components['schemas']['EvidenceKeyWrap'][];
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
     * @description Giá trị `row_version` (khoá lạc quan). Cùng giá trị với ETag, gửi lại trong `If-Match`.
     * @example 7
     */
    RowVersion: number;
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
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
     * @description Mức chất lượng nghiệm thu năm mức (baseline mục 9.1).
     * @enum {number}
     */
    QualityLevel: 1 | 0.75 | 0.5 | 0.25 | 0;
    /**
     * Format: date
     * @description Ngày theo lịch (ISO 8601), không có giờ.
     * @example 2026-12-15
     */
    Date: string;
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
    /**
     * @description Phân loại dữ liệu theo ADR-06.
     * @enum {string}
     */
    Classification: 'C0' | 'C1' | 'C2' | 'C3';
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
    /** @description Xung đột. Hai nghĩa phân biệt bằng `code`: xung đột ghi đồng thời (`<MODULE>-409-VERSION-CONFLICT`, `type` kết thúc bằng `concurrent-modification`) hoặc vi phạm trạng thái nghiệp vụ (ví dụ `TASK-409-PERIOD-LOCKED`). Với xung đột ghi, giao diện giữ dữ liệu đang nhập và hiện khác biệt. */
    Conflict: {
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
    /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
    VersionNo: number;
    /** @description Mã phiên tải lên (`evidence.upload_session.id`). */
    UploadId: components['schemas']['Uuid'];
    /** @description Mã đối tượng logic (`evidence.object_meta.id`). */
    ObjectId: components['schemas']['Uuid'];
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
    IdPath: components['schemas']['Uuid'];
    /** @description Mã kỳ đánh giá (`config.period.id`). */
    PeriodId: components['schemas']['Uuid'];
    /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
    Q: string;
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
  listDeliverables: {
    parameters: {
      query: {
        /** @description Mã nhiệm vụ (`task.task.id`). */
        task: components['schemas']['Uuid'];
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
      /** @description Danh sách sản phẩm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DeliverableList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createDeliverable: {
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
        'application/json': components['schemas']['DeliverableCreate'];
      };
    };
    responses: {
      /** @description Đã tạo sản phẩm. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Deliverable'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      /** @description Dữ liệu không hợp lệ. Mã lỗi: `COMMON-422-VALIDATION`, `PRODUCT-422-TASK-NOT-OPEN` (nhiệm vụ không ở trạng thái cho phép khai báo sản phẩm). */
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
  getDeliverable: {
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
      /** @description Sản phẩm. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Deliverable'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listEvidences: {
    parameters: {
      query?: {
        /** @description Lọc theo nhiệm vụ. */
        task?: components['schemas']['Uuid'];
        /** @description Lọc theo sản phẩm. */
        deliverable?: components['schemas']['Uuid'];
        /** @description Lọc theo trạng thái phiên bản mới nhất; nhiều giá trị cách nhau bằng dấu phẩy. */
        status?: components['schemas']['EvidenceVersionStatus'][];
        /** @description `me` để lấy minh chứng mà người gọi là người nghiệm thu được chỉ định. */
        reviewer?: 'me';
        /** @description `me` để lấy minh chứng do người gọi nộp hoặc đang soạn. */
        submitter?: 'me';
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
      /** @description Danh sách minh chứng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createEvidence: {
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
        'application/json': components['schemas']['EvidenceCreate'];
      };
    };
    responses: {
      /** @description Đã tạo minh chứng (chưa có phiên bản). */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Evidence'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải người chủ trì nhiệm vụ (`PRODUCT-403-NOT-TASK-OWNER`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Kỳ đã khoá hoặc nhiệm vụ không ở trạng thái nhận minh chứng. Mã lỗi: `PRODUCT-409-PERIOD-LOCKED`, `PRODUCT-409-TASK-STATE`. */
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
  searchEvidenceExternalDocs: {
    parameters: {
      query?: {
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
      /** @description Danh sách văn bản khớp từ khoá. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceExternalDocList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getEvidence: {
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
      /** @description Minh chứng. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Evidence'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createEvidenceVersion: {
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
        'application/json': components['schemas']['EvidenceVersionDraft'];
      };
    };
    responses: {
      /** @description Đã tạo phiên bản nháp. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải người chủ trì nhiệm vụ (`PRODUCT-403-NOT-TASK-OWNER`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Không tạo được phiên bản. Mã lỗi: `PRODUCT-409-OPEN-VERSION-EXISTS` (phiên bản mới nhất chưa kết thúc), `PRODUCT-409-PERIOD-LOCKED` (đã quá thời hạn nghiệm thu tồn đọng của kỳ). */
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
  getEvidenceVersion: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản minh chứng. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateEvidenceVersionDraft: {
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
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceVersionDraft'];
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
          'application/json': components['schemas']['EvidenceVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột. Mã lỗi: `PRODUCT-409-VERSION-CONFLICT` (lệch `If-Match`), `PRODUCT-409-VERSION-FROZEN` (phiên bản không còn `NHAP`). */
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
  submitEvidenceVersion: {
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
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceSubmit'];
      };
    };
    responses: {
      /** @description Đã nộp (MSG-07-04; MSG-07-05 khi `taskStatusChanged` là `CHO_NGHIEM_THU`). */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceSubmitResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải người chủ trì nhiệm vụ (`PRODUCT-403-NOT-TASK-OWNER`). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Xung đột. Mã lỗi: `PRODUCT-409-VERSION-CONFLICT`, `PRODUCT-409-INVALID-STATE` (phiên bản không ở `NHAP`), `PRODUCT-409-PERIOD-LOCKED` (quá thời hạn nghiệm thu tồn đọng của kỳ). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Chưa đủ điều kiện nộp. Mã lỗi: `PRODUCT-422-HASH-MISMATCH` (MSG-07-03, BR-07-02), `PRODUCT-422-NOT-CLEAN` (MSG-07-02, BR-07-04, gồm cả đang quét), `PRODUCT-422-REUSE-JUSTIFICATION-REQUIRED` (MSG-07-01, BR-07-03), `PRODUCT-422-ATTESTATION-REQUIRED`, `PRODUCT-422-AI-NOT-CONFIRMED` (BR-07-09), `PRODUCT-422-NO-OBJECT` (chưa gắn tệp hoặc văn bản). */
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
  openEvidenceReview: {
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
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản đang nghiệm thu, toàn vẹn đã kiểm (MSG-07-11 khi có cờ trùng). */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Không phải người nghiệm thu được chỉ định, hoặc là người nộp, người phối hợp (`PRODUCT-403-SELF-REVIEW`, BR-07-06). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Mã lỗi: `PRODUCT-409-VERSION-CONFLICT`, `PRODUCT-409-INVALID-STATE` (phiên bản không ở `DA_NOP` hoặc `DANG_NGHIEM_THU`), `PRODUCT-409-INTEGRITY-FAILED` (MSG-07-13, toàn vẹn không bảo đảm). */
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
  acceptEvidenceVersion: {
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
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceAcceptRequest'];
      };
    };
    responses: {
      /** @description Đã xác nhận nghiệm thu (MSG-07-12). */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceDecisionResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Không phải người nghiệm thu được chỉ định, hoặc là người nộp, người phối hợp (`PRODUCT-403-SELF-REVIEW`, BR-07-06). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Mã lỗi: `PRODUCT-409-VERSION-CONFLICT`, `PRODUCT-409-INVALID-STATE` (phiên bản không ở `DANG_NGHIEM_THU`; phải gọi `open-review` trước), `PRODUCT-409-INTEGRITY-FAILED` (MSG-07-13), `PRODUCT-409-PERIOD-LOCKED`. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Mã lỗi: `PRODUCT-422-CRITERIA-INCOMPLETE` (MSG-07-09, chưa đánh giá hết tiêu chuẩn), `PRODUCT-422-COMMENT-REQUIRED` (MSG-07-10, BR-07-08, mức dưới 1 không có nhận xét), `PRODUCT-422-REVISION-CAUSE-REQUIRED` (có phiên bản trước bị yêu cầu bổ sung), `COMMON-422-VALIDATION` (mức chất lượng ngoài năm giá trị, BR-07-07). */
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
  requestEvidenceRevision: {
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
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceReturnRequest'];
      };
    };
    responses: {
      /** @description Đã yêu cầu bổ sung. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceDecisionResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Không phải người nghiệm thu được chỉ định (`PRODUCT-403-SELF-REVIEW`, BR-07-06). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Mã lỗi `PRODUCT-409-VERSION-CONFLICT`, `PRODUCT-409-INVALID-STATE`. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Thiếu nội dung cần bổ sung (`PRODUCT-422-COMMENT-REQUIRED`, BR-07-08). */
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
  rejectEvidenceVersion: {
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
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceReturnRequest'];
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
          'application/json': components['schemas']['EvidenceDecisionResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Không phải người nghiệm thu được chỉ định (`PRODUCT-403-SELF-REVIEW`, BR-07-06). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Mã lỗi `PRODUCT-409-VERSION-CONFLICT`, `PRODUCT-409-INVALID-STATE`. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Thiếu lý do từ chối (`PRODUCT-422-COMMENT-REQUIRED`, BR-07-08). */
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
  addEvidenceVersionKeyWraps: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceKeyWrapBatchInput'];
      };
    };
    responses: {
      /** @description Phiên bản minh chứng sau khi bổ sung khoá bọc. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceVersion'];
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
  restoreEvidenceKeyWrap: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản minh chứng (`product.evidence_version.version_no`), bắt đầu từ 1. */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceRecoveryWrapRequest'];
      };
    };
    responses: {
      /** @description Phiên bản minh chứng sau khi nạp khoá bọc khôi phục. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      503: components['responses']['ServiceUnavailable'];
      default: components['responses']['Problem'];
    };
  };
  createFileUpload: {
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
        'application/json': components['schemas']['FileUploadCreate'];
      };
    };
    responses: {
      /** @description Đã mở phiên tải lên. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FileUploadSession'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Tệp vượt giới hạn kích thước của tenant (`FILE-413-TOO-LARGE`, SEC-48). */
      413: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Kiểu tệp không được phép (`FILE-415-UNSUPPORTED-TYPE`, SEC-48); chỉ PDF, DOCX, XLSX, JPG, PNG. */
      415: {
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
  getFileUpload: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên tải lên (`evidence.upload_session.id`). */
        uploadId: components['parameters']['UploadId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên tải lên. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FileUploadSession'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  cancelFileUpload: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã phiên tải lên (`evidence.upload_session.id`). */
        uploadId: components['parameters']['UploadId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đã huỷ. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Phiên đã hoàn tất (`FILE-409-UPLOAD-CLOSED`). */
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
  uploadFilePart: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã phiên tải lên (`evidence.upload_session.id`). */
        uploadId: components['parameters']['UploadId'];
        /** @description Số thứ tự phần, bắt đầu từ 1. */
        n: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/octet-stream': string;
      };
    };
    responses: {
      /** @description Đã nhận phần. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FileUploadPart'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Phiên đã hoàn tất, đã huỷ hoặc hết hạn (`FILE-409-UPLOAD-CLOSED`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Phần vượt kích thước tối đa (`FILE-413-PART-TOO-LARGE`). */
      413: {
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
  completeFileUpload: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã phiên tải lên (`evidence.upload_session.id`). */
        uploadId: components['parameters']['UploadId'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['FileUploadComplete'];
      };
    };
    responses: {
      /** @description Đã lưu đối tượng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FileObject'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Phiên đã huỷ hoặc hết hạn (`FILE-409-UPLOAD-CLOSED`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Mã lỗi: `FILE-422-HASH-MISMATCH` (MSG-07-03, băm tính lại không khớp băm khai báo), `FILE-422-SIZE-MISMATCH`, `FILE-422-MISSING-PARTS`, `FILE-422-CONTENT-TYPE-MISMATCH` (nội dung thật khác kiểu khai báo hoặc có macro, SEC-48). */
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
  getFileDownloadUrl: {
    parameters: {
      query?: {
        /** @description Số phiên bản đối tượng; mặc định phiên bản hiện hành. */
        version?: number;
        /** @description `inline` để xem trong trình duyệt (trình xem SCR-0703), `attachment` để tải về. */
        disposition?: 'inline' | 'attachment';
      };
      header?: never;
      path: {
        /** @description Mã đối tượng logic (`evidence.object_meta.id`). */
        objectId: components['parameters']['ObjectId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description URL tải có hạn. */
      200: {
        headers: {
          /** @description Luôn `no-store`. */
          'Cache-Control'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FileDownloadUrl'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Tệp đang chờ quét hoặc đã bị cách ly (`FILE-409-NOT-CLEAN`, MSG-07-02). */
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
  getFilePreview: {
    parameters: {
      query?: {
        /** @description Số phiên bản đối tượng; mặc định phiên bản hiện hành. */
        version?: number;
        /** @description Trang đầu (tính từ 1). */
        fromPage?: number;
        /** @description Số trang trả về; giao diện nạp lười theo cửa sổ trang. */
        count?: number;
      };
      header?: never;
      path: {
        /** @description Mã đối tượng logic (`evidence.object_meta.id`). */
        objectId: components['parameters']['ObjectId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Thông tin xem trước và URL các trang trong cửa sổ yêu cầu. */
      200: {
        headers: {
          /** @description Luôn `no-store`. */
          'Cache-Control'?: string;
          'Retry-After': components['headers']['RetryAfter'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FilePreview'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Tệp đang chờ quét hoặc đã bị cách ly (`FILE-409-NOT-CLEAN`, MSG-07-02). */
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
  getFileScanResult: {
    parameters: {
      query?: {
        /** @description Số phiên bản đối tượng; mặc định phiên bản hiện hành. */
        version?: number;
      };
      header?: never;
      path: {
        /** @description Mã đối tượng logic (`evidence.object_meta.id`). */
        objectId: components['parameters']['ObjectId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Kết quả quét. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FileScanResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  rescanFileObject: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã đối tượng logic (`evidence.object_meta.id`). */
        objectId: components['parameters']['ObjectId'];
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
}
