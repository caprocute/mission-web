/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/org.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/org/me': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Người dùng hiện tại và các phân công còn hiệu lực
     * @description Dùng ở QT-00 bước 7 để chọn phân công khi kiêm nhiệm (SCR-0001) và đổi phân công (SCR-0002).
     */
    get: operations['getMyOrgProfile'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/me/context': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Ghi ngữ cảnh phiên (phân công đang làm việc)
     * @description Ghi phân công được chọn vào phiên; client làm mới token để nhận claim `org`. Vai trò chỉ dùng trong phiên AAL2 (`appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin`) không chọn được ở phiên AAL1 (BR-00-01, MSG-00-05) và trả 401 yêu cầu bước lên.
     */
    put: operations['setMyContext'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/persons': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Cán bộ trong phạm vi
     * @description Cán bộ để chọn chủ trì, phối hợp, người nghiệm thu (SCR-0501). Đọc từ `org_replica` trong mission-core theo cơ chế Event-carried State Transfer; không gọi đồng bộ sang SVC-04 trên đường găng (baseline mục 8). `scope=unit` là đơn vị của ngữ cảnh, `scope=subtree` gồm đơn vị cấp dưới.
     */
    get: operations['listOrgPersons'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/persons/{id}/assignments': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Phân công của một cán bộ theo thời gian */
    get: operations['listPersonAssignments'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Đơn vị trong phạm vi
     * @description `scope=unit` là đơn vị của người dùng và đơn vị ngang cấp, `scope=children` là đơn vị cấp dưới trực tiếp (giao đồng loạt ở SCR-0501).
     */
    get: operations['listOrgUnits'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units/tree': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Cây tổ chức tại một thời điểm
     * @description Cây đơn vị theo hai trục thời gian. Khi `asOf` hoặc `recordedAsOf` khác hiện tại, giao diện ở chế độ chỉ đọc. Mặc định trả tối đa ba tầng từ gốc; tầng sâu hơn nạp lười bằng `/org/units/{id}/subtree`.
     */
    get: operations['getOrgTree'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units/search': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tìm đơn vị trong cây tại một thời điểm
     * @description (suy luận đường dẫn) Ô tìm đơn vị ở vùng 1 của SCR-0103: tìm theo mã hoặc tên (không phân biệt dấu) trên phiên bản đơn vị có hiệu lực tại `asOf`, ghi nhận tại `recordedAsOf`, trả kèm chuỗi tổ tiên từ gốc để mở cây tới đúng nút. Tách khỏi `GET /org/units` vì thao tác đó phục vụ chọn đơn vị theo phạm vi người dùng (SCR-0501), không có trục thời gian.
     */
    get: operations['searchOrgUnits'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Đơn vị tại thời điểm, hai trục thời gian */
    get: operations['getOrgUnit'];
    /**
     * Sửa đơn vị
     * @description Tạo phiên bản mới của đơn vị từ ngày hiệu lực, không ghi đè phiên bản cũ; phát EVT-14.
     */
    put: operations['updateOrgUnit'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units/{id}/subtree': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Cây đơn vị cấp dưới
     * @description Thay cho `/org/units/{id}/tree` của TL-02; giữ tên `subtree` của TL-01 Phụ lục C.
     */
    get: operations['getOrgSubtree'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units/{id}/changes': {
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
     * Thay đổi tổ chức theo quyết định
     * @description Thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể kèm ngày hiệu lực và văn bản căn cứ. Máy chủ ghi `org.org_change_order` và sinh phiên bản mới cho các đơn vị bị tác động; phân công chuyển theo phát EVT-15. Thay đổi có hiệu lực tương lai được giữ ở `NHAP` tới ngày hiệu lực.
     */
    post: operations['changeOrgUnit'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/change-orders/{id}/corrections': {
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
     * Đính chính hai trục thời gian cho quyết định thay đổi tổ chức
     * @description Đính chính sai sót nhập liệu trên trục ghi nhận (bitemporal, BR-01-06): đóng `recorded_to` của các phiên bản ghi nhận sai và chèn phiên bản đã đính chính với `recorded_from = now()`. Không ghi đè vật lý lịch sử; bắt buộc có lý do đính chính và văn bản căn cứ.
     */
    post: operations['correctOrgChangeOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units/{id}/history': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Lịch sử phiên bản của đơn vị
     * @description (suy luận đường dẫn) Vùng 4 "Lịch sử thay đổi" của SCR-0103: các phiên bản của đơn vị trên trục hiệu lực, mới nhất trước, kèm quyết định thay đổi và thời điểm ghi nhận (BR-01-06, không ghi đè lịch sử). `recordedAsOf` giới hạn các phiên bản đã ghi nhận tới thời điểm đó; bỏ trống là hiện tại. Phiên bản đã bị thay thế trên trục ghi nhận (đính chính) có `recordedTo`.
     */
    get: operations['listOrgUnitHistory'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/units/{id}/assignments': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Cán bộ của đơn vị tại thời điểm
     * @description (suy luận đường dẫn) Vùng 3 của SCR-0103 (trường 13): phân công `org.assignment` thuộc đơn vị có hiệu lực tại `asOf`, ghi nhận tại `recordedAsOf`. Không chứa dữ liệu C2.
     */
    get: operations['listOrgUnitAssignments'];
    put?: never;
    /**
     * Thêm phân công cán bộ vào đơn vị
     * @description Tạo bản ghi phân công `org.assignment` có hiệu lực từ `validFrom` (QT-01 bước 5, BR-01-06).
     */
    post: operations['createOrgUnitAssignment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/assignments': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Tạo phân công chính thức, kiêm nhiệm hoặc biệt phái theo đơn vị trong thân yêu cầu
     * @description Điểm vào chuẩn `POST /api/v1/org/assignments` theo TL-01 mục SVC-04 (cùng nghiệp vụ với `POST /org/units/{id}/assignments`, thuận tiện cho lời gọi nội bộ và đồng bộ). Kiểm tra tổng tỷ lệ thời gian không vượt 100% khi kiêm nhiệm (BR-01-06) và sinh bản ghi mới trên trục hiệu lực `[valid_from, valid_to)` (BR-01-08).
     */
    post: operations['createOrgAssignment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/assignments/{id}': {
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
     * Điều chỉnh phân công cán bộ theo trục hiệu lực
     * @description Cập nhật chức danh, loại phân công (chính/kiêm nhiệm), tỷ lệ thời gian hoặc khối đối tượng của phân công tại đơn vị (QT-01 bước 5, BR-01-06).
     */
    patch: operations['updateOrgAssignment'];
    trace?: never;
  };
  '/org/assignments/{id}/end': {
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
     * Kết thúc phân công cán bộ tại đơn vị
     * @description Đóng `valid_to` của phân công `org.assignment` khi điều động, thôi kiêm nhiệm hoặc nghỉ công tác (QT-01 bước 5, BR-01-06).
     */
    post: operations['endOrgAssignment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/subject-groups': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh mục khối đối tượng đánh giá
     * @description (suy luận đường dẫn) Chín khối đối tượng theo PL 2A (`org.subject_group`) cho ô chọn "Khối đối tượng" (SCR-0103 trường 7, BR-01-05). Danh mục nhỏ nên trả một lần; `asOf` lọc khối còn hiệu lực.
     */
    get: operations['listSubjectGroups'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/imports': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Nhập cây tổ chức từ Excel
     * @description Tải tệp, kiểm tra và ghi ở tác vụ nền; trả 202, báo cáo lỗi nằm trong `resultLocation` của job.
     */
    post: operations['importOrgExcel'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/sync-runs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Các lần đồng bộ gần đây theo nguồn */
    get: operations['listOrgSyncRuns'];
    put?: never;
    /**
     * Đồng bộ ngay
     * @description Tạo lần chạy đồng bộ cho một hoặc mọi nguồn; trả 202. Thay cho `/org/sync` của ma trận truy vết TL-02b.
     */
    post: operations['startOrgSyncRun'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/sync-runs/{id}/retry': {
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
     * Chạy lại thông điệp lỗi
     * @description Đưa các thông điệp trong hàng đợi lỗi (`integ.dead_letter`) của lần chạy vào lại luồng xử lý.
     */
    post: operations['retryOrgSyncRun'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/sync-schedules': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Lịch đồng bộ theo nguồn
     * @description Trả `ETag` để gửi lại trong `If-Match` khi lưu lịch (`PUT` bắt buộc `If-Match`).
     */
    get: operations['listOrgSyncSchedules'];
    /** Lưu lịch đồng bộ */
    put: operations['saveOrgSyncSchedules'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/discrepancies': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Danh sách lệch cần đối soát */
    get: operations['listOrgDiscrepancies'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/discrepancies/resolve': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xử lý lệch hàng loạt
     * @description Lấy theo nguồn (ghi phiên bản mới, phát EVT-15 hoặc EVT-16) hoặc giữ dữ liệu nội bộ (bỏ qua cho tới khi nguồn đổi). Áp dụng hàng loạt cần phiên AAL2. Mức Cao còn tồn chặn kích hoạt tenant.
     */
    post: operations['resolveOrgDiscrepancies'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/discrepancies/{id}/map': {
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
     * Ánh xạ thủ công bản ghi người
     * @description Chọn bản ghi người đúng cho dòng không khớp, ghi `integ.mapping_xref`, phát EVT-16.
     */
    post: operations['mapOrgDiscrepancy'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/discrepancies/{id}/assign': {
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
     * Ghi chú, giao xử lý
     * @description Giao cho cán bộ tổ chức đơn vị kiểm tra; phát EVT-21.
     */
    post: operations['assignOrgDiscrepancy'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/persons/me/public-keys': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Đăng ký khoá công khai của thiết bị hoặc chứng thư số vào danh bạ khoá
     * @description Thiết bị sinh cặp khoá ECDH P-256 không trích xuất được và đăng ký khoá công khai khi người dùng đăng nhập có MFA lần đầu trên thiết bị (ADR-27). Máy chủ kiểm dạng SubjectPublicKeyInfo đúng thuật toán, tính vân tay SHA-256 của SPKI, ký bản ghi bằng khoá danh bạ của tenant qua OpenBao Transit (không giữ khoá trong mã) và ghi sổ cái `sys.audit.v1` trong cùng giao dịch. Vân tay đã có trong tenant trả 409 (`ORG-409-PUBLIC-KEY-EXISTS`).
     */
    post: operations['registerMyPublicKey'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/persons/{id}/public-keys': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Khoá công khai của một cán bộ trong danh bạ khoá
     * @description Trình duyệt người gửi lấy khoá công khai của từng người nhận để bọc khoá nội dung C3 (TL-01 "Mô hình mã hoá" bước 1). Mặc định chỉ trả khoá còn hiệu lực; khoá quá `validTo` trả trạng thái `HET_HAN`. Mỗi khoá kèm chữ ký của danh bạ để thiết bị kiểm trước khi dùng.
     */
    get: operations['listPersonPublicKeys'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/public-keys/{id}/revoke': {
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
     * Thu hồi khoá công khai (mất thiết bị, đổi chứng thư)
     * @description Chủ khoá hoặc quản trị tenant thu hồi khoá; khoá chuyển `THU_HOI`, không dùng để bọc khoá nội dung mới. Thu hồi được ghi sổ cái. Thu hồi lại khoá đã thu hồi trả 200 với bản ghi hiện tại.
     */
    post: operations['revokePublicKey'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/e2ee/directory-keys': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Khoá công khai của danh bạ khoá tenant để kiểm chữ ký
     * @description Các phiên bản khoá ký ECDSA P-256 của danh bạ khoá tenant (OpenBao Transit). Thiết bị dùng để kiểm chữ ký `signature` của khoá người nhận và khoá phục hồi trước khi bọc khoá nội dung.
     */
    get: operations['listE2eeDirectoryKeys'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/e2ee/recovery-key': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Khoá phục hồi của tổ chức đang hiệu lực
     * @description Mỗi khoá nội dung C3 luôn được bọc thêm cho khoá phục hồi của tenant (ADR-27). Người gửi lấy khoá công khai phục hồi ở đây; `id` là mã người nhận đại diện dùng trong bản bọc. Chưa khai báo trả 404.
     */
    get: operations['getRecoveryKey'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/e2ee/recovery-keys': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Khai báo hoặc thay khoá phục hồi của tổ chức
     * @description Quản trị tenant ghi khoá công khai phục hồi sinh ở máy trạm cách ly; khoá riêng chia Shamir 3 trên 5 cho năm cán bộ giữ mảnh theo biên bản, không đi qua máy chủ. Khoá mới có hiệu lực ngay, khoá cũ chuyển `THU_HOI` nhưng vẫn mở được bản bọc đã có ở máy trạm. Ghi sổ cái.
     */
    post: operations['rotateRecoveryKey'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/org/e2ee/recoveries': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Nhật ký các lần phục hồi tổ chức */
    get: operations['listE2eeRecoveries'];
    put?: never;
    /**
     * Ghi một lần phục hồi tổ chức
     * @description Ghi lần phục hồi đã làm ở máy trạm cách ly: ít nhất ba người giữ mảnh của khoá phục hồi cùng có mặt, khoá nội dung của đối tượng được bọc lại cho khoá thiết bị mới của người nhận. Máy chủ chỉ lưu nhật ký (không nhận mảnh khoá hay khoá riêng), ký bản ghi và ghi sổ cái.
     */
    post: operations['recordE2eeRecovery'];
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
     * @description Loại phân công (`org.assignment.kind`).
     * @enum {string}
     */
    AssignmentKind: 'CHINH' | 'KIEM_NHIEM' | 'DIEU_DONG' | 'BIET_PHAI' | 'TAM_GIU';
    /** @description Phân công còn hiệu lực (`org_replica.assignment_v`) kèm vai trò (`config.role_binding`). */
    Assignment: {
      id: string;
      orgUnitId: string;
      orgUnitName: string;
      jobTitleName: string;
      kind: components['schemas']['AssignmentKind'];
      branch: components['schemas']['Branch'];
      roles: string[];
      validFrom: components['schemas']['Date'];
      /** Format: date */
      validTo?: string | null;
    };
    /**
     * @example {
     *       "personId": "01928f3e-1111-7000-8000-000000000001",
     *       "fullName": "Nguyễn Văn An",
     *       "lastAssignmentId": "01928f3e-2222-7000-8000-000000000001",
     *       "assignments": [
     *         {
     *           "id": "01928f3e-2222-7000-8000-000000000001",
     *           "orgUnitId": "01928f3e-3333-7000-8000-000000000001",
     *           "orgUnitName": "Ban Tổ chức Đảng uỷ khối",
     *           "jobTitleName": "Chuyên viên",
     *           "kind": "CHINH",
     *           "branch": "DANG",
     *           "roles": [
     *             "member"
     *           ],
     *           "validFrom": "2024-01-01",
     *           "validTo": null
     *         }
     *       ]
     *     }
     */
    MyOrgProfile: {
      personId: string;
      fullName: string;
      assignments: components['schemas']['Assignment'][];
      /** @description Phân công dùng lần trước (chọn sẵn) */
      lastAssignmentId?: string | null;
    };
    WorkContext: {
      assignmentId: string;
      orgUnitId: string;
      roles: string[];
    };
    /** @description Cán bộ trong bản sao cây tổ chức (`org_replica.person_v`); không chứa dữ liệu C2. */
    OrgPerson: {
      id: components['schemas']['Uuid'];
      name: string;
      /** @description Chức danh */
      title: string;
      unitId: components['schemas']['Uuid'];
      unitName: string;
    };
    OrgUnitRef: {
      id: components['schemas']['Uuid'];
      name: string;
      /** Format: uuid */
      parentId: string | null;
    };
    /**
     * @description Loại đơn vị (`org.org_unit.unit_type`).
     * @enum {string}
     */
    OrgUnitType:
      | 'TO_CHUC_DANG'
      | 'CHI_BO'
      | 'CO_QUAN'
      | 'DON_VI_SU_NGHIEP'
      | 'PHONG_BAN'
      | 'XA_PHUONG'
      | 'DOANH_NGHIEP';
    /**
     * @description Cây tổ chức (`org.org_unit_relation.tree`).
     * @enum {string}
     */
    OrgTreeKind: 'DANG' | 'CHUYEN_MON';
    /** @description Nội dung sửa đơn vị (SCR-0103 trường 3–9, 11). */
    OrgUnitInput: {
      /** @description TL-02 giới hạn 30 ký tự, TL-03 cột `varchar(50)` */
      code: string;
      /** @description Mã CSDL CBCCVC (`integ.mapping_xref.external_id`) */
      nationalCode?: string;
      name: string;
      shortName?: string;
      /** @description Cấp (TW, tỉnh, xã); TL-03 chưa có CHECK IN */
      level: string;
      unitType: components['schemas']['OrgUnitType'];
      subjectGroupId: components['schemas']['Uuid'];
      flows: components['schemas']['Flow'][];
      parentId?: components['schemas']['Uuid'];
      validFrom: components['schemas']['Date'];
      /** Format: date */
      validTo?: string | null;
    };
    /** @description Một phiên bản đơn vị (`org.org_unit`) theo hai trục thời gian. */
    OrgUnit: components['schemas']['OrgUnitInput'] & {
      id: components['schemas']['Uuid'];
      versionId: components['schemas']['Uuid'];
      parentName?: string;
      subjectGroupName?: string;
      predecessorIds?: components['schemas']['Uuid'][];
      recordedFrom: components['schemas']['DateTime'];
      recordedByName?: string;
      assignmentCount?: number;
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Đơn vị khớp tìm kiếm kèm chuỗi tổ tiên để mở cây. */
    OrgUnitSearchHit: {
      id: components['schemas']['Uuid'];
      code: string;
      name: string;
      unitType: components['schemas']['OrgUnitType'];
      /** @description Tổ tiên từ gốc tới cha trực tiếp tại thời điểm hỏi; rỗng khi là gốc. */
      ancestors: {
        id: components['schemas']['Uuid'];
        code?: string;
        name: string;
      }[];
    };
    /** @description Một phiên bản đơn vị trên hai trục thời gian, cho vùng "Lịch sử thay đổi". */
    OrgUnitVersion: {
      versionId: components['schemas']['Uuid'];
      code: string;
      name: string;
      parentName?: string;
      subjectGroupName?: string;
      validFrom: components['schemas']['Date'];
      /** Format: date */
      validTo?: string | null;
      recordedFrom: components['schemas']['DateTime'];
      /**
       * Format: date-time
       * @description Có khi phiên bản đã bị đính chính trên trục ghi nhận.
       */
      recordedTo?: string | null;
      recordedByName?: string;
      changeType?: components['schemas']['OrgChangeType'];
      /** @description Quyết định làm phát sinh phiên bản; vắng với sửa thông tin không cần quyết định. */
      changeOrder?: {
        id: components['schemas']['Uuid'];
        orderNo: string;
        orderDate?: components['schemas']['Date'];
        signedDocRef?: string;
      };
      /** @description Tên trường đổi so với phiên bản trước, ví dụ `name`, `parentId` (suy luận) */
      changedFields?: string[];
    };
    /** @description Phân công thuộc đơn vị tại thời điểm hỏi (SCR-0103 trường 13). */
    OrgUnitAssignment: {
      id: components['schemas']['Uuid'];
      personId: components['schemas']['Uuid'];
      personName: string;
      jobTitleName: string;
      kind: components['schemas']['AssignmentKind'];
      branch?: components['schemas']['Branch'];
      validFrom: components['schemas']['Date'];
      /** Format: date */
      validTo?: string | null;
    };
    /** @description Khối đối tượng đánh giá (`org.subject_group`, PL 2A). */
    SubjectGroup: {
      id: components['schemas']['Uuid'];
      /** @example K1 */
      code: string;
      name: string;
      /** @enum {string} */
      flow: 'DANG' | 'NHA_NUOC' | 'CA_HAI';
      description?: string;
      validFrom?: components['schemas']['Date'];
      /** Format: date */
      validTo?: string | null;
    };
    OrgTreeNode: {
      id: components['schemas']['Uuid'];
      code?: string;
      name: string;
      unitType: components['schemas']['OrgUnitType'];
      hasChildren: boolean;
      children?: components['schemas']['OrgTreeNode'][];
    };
    OrgTree: {
      asOf: components['schemas']['Date'];
      recordedAsOf: components['schemas']['DateTime'];
      /** @description Đúng khi xem quá khứ */
      readOnly: boolean;
      roots: components['schemas']['OrgTreeNode'][];
    };
    /**
     * @description Loại thay đổi tổ chức theo hộp chọn SCR-0103 (thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể). Mã giá trị là suy luận; TL-03 `org.org_change_order.kind` gộp chung với quyết định nhân sự và chưa có THANH_LAP, DOI_TEN, HOP_NHAT.
     * @enum {string}
     */
    OrgChangeType: 'THANH_LAP' | 'DOI_TEN' | 'SAP_NHAP' | 'HOP_NHAT' | 'CHIA_TACH' | 'GIAI_THE';
    OrgChangeRequest: {
      changeType: components['schemas']['OrgChangeType'];
      effectiveOn: components['schemas']['Date'];
      /** @description Số quyết định */
      orderNo: string;
      orderDate: components['schemas']['Date'];
      signedDocRef?: string;
      newName?: string;
      /** @description Đơn vị bị sáp nhập, hợp nhất cùng, hoặc đơn vị mới sau chia tách. */
      relatedUnitIds?: components['schemas']['Uuid'][];
      newUnits?: components['schemas']['OrgUnitInput'][];
      moveAssignmentsTo?: components['schemas']['Uuid'];
    };
    /** @description Quyết định thay đổi (`org.org_change_order`). */
    OrgChangeOrder: {
      id: components['schemas']['Uuid'];
      changeType: components['schemas']['OrgChangeType'];
      orderNo: string;
      effectiveOn: components['schemas']['Date'];
      /** @enum {string} */
      status: 'NHAP' | 'DA_AP_DUNG' | 'DA_HUY';
      appliedAt?: components['schemas']['DateTime'];
      affectedUnitIds?: components['schemas']['Uuid'][];
    };
    /**
     * @description Nguồn đồng bộ (`integ.connector.code`, suy luận về mã).
     * @enum {string}
     */
    SyncSource: 'CBCCVC' | 'DANG_VIEN' | 'SCIM' | 'HRM';
    /** @description Một lần đồng bộ (`org.scim_sync_log` tổng hợp, `integ.sync_cursor`). */
    OrgSyncRun: {
      id: components['schemas']['Uuid'];
      connector: components['schemas']['SyncSource'];
      startedAt: components['schemas']['DateTime'];
      finishedAt?: components['schemas']['DateTime'];
      /** @enum {string} */
      status: 'DANG_CHAY' | 'THANH_CONG' | 'THANH_CONG_MOT_PHAN' | 'LOI';
      records: number;
      changes: number;
      errors: number;
      deadLetters?: number;
      /** @description Lý do lỗi tiếng Việt khi `status = LOI` hoặc `THANH_CONG_MOT_PHAN`, điền vào {lý do} của MSG-01-14 (ví dụ "hết thời gian chờ NDXP"); không chứa dữ liệu C2 (suy luận). */
      reason?: string;
    };
    OrgSyncSchedule: {
      connector: components['schemas']['SyncSource'];
      enabled: boolean;
      /** @description Biểu thức cron, ví dụ `0 2 * * *` */
      cron: string;
    };
    /** @enum {string} */
    DiscrepancySeverity: 'CAO' | 'VUA' | 'THAP';
    /**
     * @description Trường lệch (`org.sync_discrepancy.field`), sáu loại của TL-02a SCR-0104 trường 4: chức vụ, đơn vị, không khớp người, đảng tịch, trạng thái, kiêm nhiệm. Mã khoá là suy luận (TL-03 chưa liệt kê).
     * @enum {string}
     */
    OrgDiscrepancyField:
      | 'job_title_id'
      | 'org_unit_id'
      | 'person_match'
      | 'party_membership'
      | 'employment_status'
      | 'concurrent_assignment';
    /**
     * @description Đề xuất xử lý (`org.sync_discrepancy.suggested_action`) theo TL-02a SCR-0104 trường 7. Khác bộ `DiscrepancyResolution` của TL-03: `LAY_THEO_NGUON` ứng `NHAN_NGUON`, `GIU_NOI_BO` ứng `GIU_HIEN_TAI`, `DOI_SOAT_THU_CONG` ứng `SUA_TAY` (ánh xạ thủ công); `TAO_DIEU_DONG_TUONG_LAI` và `KET_THUC_PHAN_CONG` được xử lý bằng `NHAN_NGUON` kèm `effectiveFrom` (suy luận, TL-03 cần bổ sung).
     * @enum {string}
     */
    OrgSuggestedAction:
      | 'LAY_THEO_NGUON'
      | 'GIU_NOI_BO'
      | 'DOI_SOAT_THU_CONG'
      | 'TAO_DIEU_DONG_TUONG_LAI'
      | 'KET_THUC_PHAN_CONG';
    /**
     * @description Cách xử lý (`org.sync_discrepancy.resolution`, TL-03).
     * @enum {string}
     */
    DiscrepancyResolution: 'NHAN_NGUON' | 'GIU_HIEN_TAI' | 'SUA_TAY' | 'BO_QUA';
    /** @description Một dòng lệch (`org.sync_discrepancy`). Giá trị hai phía là C2 và chỉ trả dạng đã che (ví dụ CCCD che 4 số cuối). */
    OrgDiscrepancy: {
      id: components['schemas']['Uuid'];
      /** Format: uuid */
      personId?: string | null;
      personName?: string;
      maskedNationalId?: string;
      field: components['schemas']['OrgDiscrepancyField'];
      sourceConnector: components['schemas']['SyncSource'];
      sourceValueMasked?: string;
      currentValueMasked?: string;
      severity: components['schemas']['DiscrepancySeverity'];
      suggestedAction: components['schemas']['OrgSuggestedAction'];
      resolution?: components['schemas']['DiscrepancyResolution'];
      resolvedByName?: string;
      resolvedAt?: components['schemas']['DateTime'];
      assigneeName?: string;
      externalId?: string;
      /** @description Tiêu chí khớp người hiển thị ở vùng 4 "Khớp theo", ví dụ "Số định danh (băm HMAC) và ngày sinh" (TL-02a QT-01 bước 7; suy luận tên trường). */
      matchedBy?: string;
      /** @description Ngày hiệu lực theo quyết định nguồn (trường 10), dùng làm `org.assignment.valid_from` khi lấy theo nguồn. */
      effectiveFrom?: components['schemas']['Date'];
      syncRunId: components['schemas']['Uuid'];
    };
    OrgAssignmentCreateRequest: {
      personId: components['schemas']['Uuid'];
      jobTitleId: components['schemas']['Uuid'];
      subjectGroupCode?: string;
      /**
       * @default CHINH
       * @enum {string}
       */
      assignmentKind: 'CHINH' | 'KIEM_NHIEM' | 'BIET_PHAI';
      /** @default 1 */
      weightRatio: number;
      validFrom: components['schemas']['Date'];
      /** Format: date */
      validTo?: string | null;
      signedDocRef?: string;
    };
    OrgAssignmentCreateInput: {
      orgUnitId: components['schemas']['Uuid'];
      personId: components['schemas']['Uuid'];
      jobTitleId: components['schemas']['Uuid'];
      subjectGroupCode?: string;
      /**
       * @default CHINH
       * @enum {string}
       */
      assignmentKind: 'CHINH' | 'KIEM_NHIEM' | 'BIET_PHAI';
      /** @default 1 */
      weightRatio: number;
      validFrom: components['schemas']['Date'];
      /** Format: date */
      validTo?: string | null;
      signedDocRef?: string;
    };
    OrgAssignmentUpdateRequest: {
      jobTitleId?: components['schemas']['Uuid'];
      subjectGroupCode?: string;
      /** @enum {string} */
      assignmentKind?: 'CHINH' | 'KIEM_NHIEM' | 'BIET_PHAI';
      weightRatio?: number;
      effectiveFrom?: components['schemas']['Date'];
      signedDocRef?: string;
    };
    OrgAssignmentEndRequest: {
      validTo: components['schemas']['Date'];
      reason: string;
      signedDocRef?: string;
    };
    OrgChangeCorrectionRequest: {
      reason: string;
      signedDocRef: string;
      correctedOrder: components['schemas']['OrgChangeRequest'];
    };
    /**
     * @description Thuật toán khoá công khai (`org.person_public_key.key_alg`).
     * @enum {string}
     */
    PublicKeyAlg: 'ECDH-P256' | 'RSA-OAEP-3072' | 'BCY';
    /** @description Chữ ký của danh bạ khoá tenant (ECDSA P-256, SHA-256, chữ ký DER mã base64) trên chuỗi chuẩn `v1|<tenantId>|<loại>|<id>|<chủ khoá>|<keyAlg>|<fingerprint>|<validFrom>|<validTo>` với thời điểm dạng ISO 8601 UTC; `<loại>` là `PERSON_KEY` hoặc `RECOVERY_KEY`, `<validTo>` rỗng khi không giới hạn. */
    DirectorySignature: {
      /** @description Phiên bản khoá ký trong `listE2eeDirectoryKeys` */
      keyVersion: number;
      /** @description Chữ ký DER dạng base64 */
      value: string;
      signedAt: components['schemas']['DateTime'];
    };
    DirectoryKey: {
      keyVersion: number;
      /** @description Khoá công khai ECDSA P-256 dạng PEM */
      publicKeyPem: string;
    };
    PublicKeyRegistration: {
      keyAlg: components['schemas']['PublicKeyAlg'];
      /** @description SubjectPublicKeyInfo DER dạng base64 */
      publicKeySpki: string;
      /** @enum {string} */
      source: 'THIET_BI' | 'CHUNG_THU';
      /**
       * Format: date-time
       * @description Hết hiệu lực (theo chứng thư); bỏ trống là ba năm kể từ khi đăng ký.
       */
      validTo?: string | null;
    };
    /** @description Khoá công khai trong danh bạ (`org.person_public_key`). */
    PersonPublicKey: {
      id: components['schemas']['Uuid'];
      personId: components['schemas']['Uuid'];
      keyAlg: components['schemas']['PublicKeyAlg'];
      /** @description SubjectPublicKeyInfo DER dạng base64 */
      publicKeySpki: string;
      /** @description SHA-256 của SPKI dạng hex, là `deviceId` của bản bọc */
      fingerprint: string;
      /** @enum {string} */
      source: 'THIET_BI' | 'CHUNG_THU';
      /** @enum {string} */
      status: 'HIEU_LUC' | 'THU_HOI' | 'HET_HAN';
      validFrom: components['schemas']['DateTime'];
      validTo: components['schemas']['DateTime'];
      signature: components['schemas']['DirectorySignature'];
    };
    PublicKeyRevocation: {
      reason: string;
    };
    RecoveryKeyInput: {
      /** @enum {string} */
      keyAlg: 'ECDH-P256' | 'RSA-OAEP-3072';
      /** @description SubjectPublicKeyInfo DER dạng base64 */
      publicKeySpki: string;
      /** @description Năm cán bộ giữ mảnh khoá (Shamir 3 trên 5). */
      custodianIds: components['schemas']['Uuid'][];
      /** @description Số biên bản tạo và giao mảnh khoá */
      ceremonyRef: string;
    };
    /** @description Khoá phục hồi của tổ chức. */
    RecoveryKey: {
      id: components['schemas']['Uuid'];
      /** @enum {string} */
      keyAlg: 'ECDH-P256' | 'RSA-OAEP-3072';
      publicKeySpki: string;
      fingerprint: string;
      /** @description Số mảnh tối thiểu để phục hồi */
      threshold: number;
      custodianIds: components['schemas']['Uuid'][];
      ceremonyRef: string;
      /** @enum {string} */
      status: 'HIEU_LUC' | 'THU_HOI';
      validFrom: components['schemas']['DateTime'];
      /** Format: date-time */
      validTo?: string | null;
      signature: components['schemas']['DirectorySignature'];
    };
    /** @enum {string} */
    RecoveryObjectType: 'TASK_CONTENT' | 'EVIDENCE_FILE';
    RecoveryRecordInput: {
      recoveryKeyId: components['schemas']['Uuid'];
      objectType: components['schemas']['RecoveryObjectType'];
      objectId: components['schemas']['Uuid'];
      newRecipientId: components['schemas']['Uuid'];
      /** @description Vân tay khoá thiết bị mới đã có trong danh bạ */
      newKeyFingerprint: string;
      /** @description Những người giữ mảnh có mặt (ít nhất ngưỡng của khoá phục hồi). */
      custodianIds: components['schemas']['Uuid'][];
      reason: string;
      /** @description Số biên bản phục hồi */
      ceremonyRef: string;
      performedAt: components['schemas']['DateTime'];
    };
    RecoveryRecord: {
      id: components['schemas']['Uuid'];
      recoveryKeyId: components['schemas']['Uuid'];
      objectType: components['schemas']['RecoveryObjectType'];
      objectId: components['schemas']['Uuid'];
      newRecipientId: components['schemas']['Uuid'];
      newKeyFingerprint: string;
      custodianIds: components['schemas']['Uuid'][];
      reason: string;
      ceremonyRef: string;
      performedAt: components['schemas']['DateTime'];
      recordedBy: components['schemas']['Uuid'];
      recordedAt: components['schemas']['DateTime'];
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
     * @description Nhánh của phân công (`org.assignment.branch`, TL-03).
     * @enum {string}
     */
    Branch: 'DANG' | 'CHINH_QUYEN';
    /**
     * Format: date
     * @description Ngày theo lịch (ISO 8601), không có giờ.
     * @example 2026-12-15
     */
    Date: string;
    /**
     * Format: uuid
     * @description Mã định danh UUIDv7 (cột `id` của mọi bảng, baseline mục 9).
     * @example 01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77
     */
    Uuid: string;
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
    /**
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
    /**
     * @description Luồng đánh giá (baseline mục 9.1).
     * @enum {string}
     */
    Flow: 'DANG' | 'NHA_NUOC';
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
    /** @description Yêu cầu sai cú pháp hoặc tham số không hợp lệ. */
    BadRequest: {
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
    /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
    AsOf: components['schemas']['Date'];
    /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
    RecordedAsOf: components['schemas']['DateTime'];
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
    /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
    Q: string;
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
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
  getMyOrgProfile: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Hồ sơ và phân công. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['MyOrgProfile'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  setMyContext: {
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
          assignmentId: string;
        };
      };
    };
    responses: {
      /** @description Ngữ cảnh đã ghi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkContext'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listOrgPersons: {
    parameters: {
      query?: {
        scope?: 'unit' | 'subtree';
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
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
      /** @description Một trang cán bộ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgPerson'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listPersonAssignments: {
    parameters: {
      query?: {
        /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
        asOf?: components['parameters']['AsOf'];
        /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
        recordedAsOf?: components['parameters']['RecordedAsOf'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phân công tại thời điểm hỏi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['Assignment'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listOrgUnits: {
    parameters: {
      query?: {
        scope?: 'unit' | 'children';
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
      /** @description Một trang đơn vị. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgUnitRef'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  getOrgTree: {
    parameters: {
      query?: {
        /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
        asOf?: components['parameters']['AsOf'];
        /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
        recordedAsOf?: components['parameters']['RecordedAsOf'];
        /** @description Cây Đảng hoặc cây chuyên môn (`org.org_unit_relation.tree`). */
        tree?: components['schemas']['OrgTreeKind'];
        depth?: number;
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Cây đơn vị. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgTree'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  searchOrgUnits: {
    parameters: {
      query: {
        /** @description Mã hoặc tên đơn vị, tối thiểu 2 ký tự. */
        q: string;
        /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
        asOf?: components['parameters']['AsOf'];
        /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
        recordedAsOf?: components['parameters']['RecordedAsOf'];
        tree?: components['schemas']['OrgTreeKind'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Các đơn vị khớp, xếp theo độ khớp; không phân trang tiếp. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgUnitSearchHit'][];
          };
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  getOrgUnit: {
    parameters: {
      query?: {
        /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
        asOf?: components['parameters']['AsOf'];
        /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
        recordedAsOf?: components['parameters']['RecordedAsOf'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản đơn vị có hiệu lực tại thời điểm hỏi. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgUnit'];
        };
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateOrgUnit: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
        'application/json': components['schemas']['OrgUnitInput'];
      };
    };
    responses: {
      /** @description Phiên bản mới. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgUnit'];
        };
      };
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  getOrgSubtree: {
    parameters: {
      query?: {
        /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
        asOf?: components['parameters']['AsOf'];
        /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
        recordedAsOf?: components['parameters']['RecordedAsOf'];
        depth?: number;
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Cây con. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgTree'];
        };
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  changeOrgUnit: {
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
        'application/json': components['schemas']['OrgChangeRequest'];
      };
    };
    responses: {
      /** @description Quyết định thay đổi đã ghi. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgChangeOrder'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  correctOrgChangeOrder: {
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
        'application/json': components['schemas']['OrgChangeCorrectionRequest'];
      };
    };
    responses: {
      /** @description Quyết định đính chính đã ghi nhận trên trục ghi nhận. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgChangeOrder'];
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
  listOrgUnitHistory: {
    parameters: {
      query?: {
        /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
        recordedAsOf?: components['parameters']['RecordedAsOf'];
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
      /** @description Một trang phiên bản. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgUnitVersion'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listOrgUnitAssignments: {
    parameters: {
      query?: {
        /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
        asOf?: components['parameters']['AsOf'];
        /** @description Thời điểm trên trục ghi nhận (`recorded_from`/`recorded_to`); bỏ trống là hiện tại. */
        recordedAsOf?: components['parameters']['RecordedAsOf'];
        /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
        q?: components['parameters']['Q'];
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
      /** @description Một trang phân công. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgUnitAssignment'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createOrgUnitAssignment: {
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
        'application/json': components['schemas']['OrgAssignmentCreateRequest'];
      };
    };
    responses: {
      /** @description Phân công mới đã tạo. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgUnitAssignment'];
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
  createOrgAssignment: {
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
        'application/json': components['schemas']['OrgAssignmentCreateInput'];
      };
    };
    responses: {
      /** @description Phân công mới trên trục hiệu lực. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgUnitAssignment'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Trùng giai đoạn phân công chính hoặc vượt tỷ lệ kiêm nhiệm (`ORG-409-ASSIGNMENT-OVERLAP`, BR-01-06). */
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
  updateOrgAssignment: {
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
        'application/json': components['schemas']['OrgAssignmentUpdateRequest'];
      };
    };
    responses: {
      /** @description Phân công sau khi cập nhật. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgUnitAssignment'];
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
  endOrgAssignment: {
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
        'application/json': components['schemas']['OrgAssignmentEndRequest'];
      };
    };
    responses: {
      /** @description Phân công sau khi kết thúc hiệu lực. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgUnitAssignment'];
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
  listSubjectGroups: {
    parameters: {
      query?: {
        /** @description Ngày trên trục hiệu lực (`valid_from`/`valid_to`); bỏ trống là hôm nay. */
        asOf?: components['parameters']['AsOf'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh mục khối đối tượng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['SubjectGroup'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  importOrgExcel: {
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
        'multipart/form-data': {
          file: string;
          effectiveFrom?: components['schemas']['Date'];
        };
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  listOrgSyncRuns: {
    parameters: {
      query?: {
        connector?: components['schemas']['SyncSource'];
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
      /** @description Một trang lần đồng bộ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgSyncRun'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  startOrgSyncRun: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': {
          connector?: components['schemas']['SyncSource'];
        };
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      403: components['responses']['Forbidden'];
      /** @description Đang có lần đồng bộ chạy cho nguồn này (`ORG-409-SYNC-RUNNING`). */
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  retryOrgSyncRun: {
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
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listOrgSyncSchedules: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Lịch đồng bộ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgSyncSchedule'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  saveOrgSyncSchedules: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': {
          items: components['schemas']['OrgSyncSchedule'][];
        };
      };
    };
    responses: {
      /** @description Lịch đã lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgSyncSchedule'][];
          };
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listOrgDiscrepancies: {
    parameters: {
      query?: {
        field?: components['schemas']['OrgDiscrepancyField'];
        severity?: components['schemas']['DiscrepancySeverity'];
        unresolved?: boolean;
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
      /** @description Một trang lệch kèm số đếm theo mức và theo trường lệch. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['OrgDiscrepancy'][];
            nextCursor: components['schemas']['NextCursor'];
            counts: {
              CAO: number;
              VUA: number;
              THAP: number;
            };
            /** @description Số lệch theo trường lệch cho các thẻ lọc của vùng 2 ("Chức vụ 168, Đơn vị 121…"); khoá là giá trị `OrgDiscrepancyField`, tính trên cùng bộ lọc `unresolved` và `severity`, bỏ qua bộ lọc `field`. Trường không có lệch có thể vắng (suy luận). */
            countsByField?: {
              [key: string]: number;
            };
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  resolveOrgDiscrepancies: {
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
          ids: components['schemas']['Uuid'][];
          resolution: components['schemas']['DiscrepancyResolution'];
          effectiveFrom?: components['schemas']['Date'];
          note?: string;
        };
      };
    };
    responses: {
      /** @description Kết quả từng mục. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            results: components['schemas']['BatchItemResult'][];
            /** @description Số phân công đã ghi phiên bản mới với ngày hiệu lực theo quyết định gốc ({m} của MSG-01-13); 0 khi chỉ giữ dữ liệu nội bộ. */
            assignmentsUpdated: number;
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  mapOrgDiscrepancy: {
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
        'application/json': {
          personId: components['schemas']['Uuid'];
        };
      };
    };
    responses: {
      /** @description Dòng lệch sau khi ánh xạ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgDiscrepancy'];
        };
      };
      403: components['responses']['Forbidden'];
      /** @description Mã ngoài đã ánh xạ tới người khác (`ORG-409-XREF-EXISTS`). */
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  assignOrgDiscrepancy: {
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
        'application/json': {
          assigneeId: components['schemas']['Uuid'];
          note?: string;
        };
      };
    };
    responses: {
      /** @description Dòng lệch sau khi giao. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrgDiscrepancy'];
        };
      };
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  registerMyPublicKey: {
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
        'application/json': components['schemas']['PublicKeyRegistration'];
      };
    };
    responses: {
      /** @description Khoá công khai đã đăng ký và ký. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PersonPublicKey'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Vân tay khoá đã có trong danh bạ của tenant (`ORG-409-PUBLIC-KEY-EXISTS`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      503: components['responses']['ServiceUnavailable'];
      default: components['responses']['Problem'];
    };
  };
  listPersonPublicKeys: {
    parameters: {
      query?: {
        /** @description Trả cả khoá đã thu hồi hoặc hết hạn (đối chiếu lịch sử). */
        includeInactive?: boolean;
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách khoá công khai. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['PersonPublicKey'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  revokePublicKey: {
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
        'application/json': components['schemas']['PublicKeyRevocation'];
      };
    };
    responses: {
      /** @description Khoá sau khi thu hồi. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PersonPublicKey'];
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
  listE2eeDirectoryKeys: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Các phiên bản khoá ký. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['DirectoryKey'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      503: components['responses']['ServiceUnavailable'];
      default: components['responses']['Problem'];
    };
  };
  getRecoveryKey: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Khoá phục hồi đang hiệu lực. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RecoveryKey'];
        };
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  rotateRecoveryKey: {
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
        'application/json': components['schemas']['RecoveryKeyInput'];
      };
    };
    responses: {
      /** @description Khoá phục hồi mới. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RecoveryKey'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Vân tay khoá đã có trong danh bạ của tenant (`ORG-409-PUBLIC-KEY-EXISTS`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      503: components['responses']['ServiceUnavailable'];
      default: components['responses']['Problem'];
    };
  };
  listE2eeRecoveries: {
    parameters: {
      query?: {
        objectId?: components['schemas']['Uuid'];
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
      /** @description Một trang nhật ký, mới nhất trước. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['RecoveryRecord'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  recordE2eeRecovery: {
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
        'application/json': components['schemas']['RecoveryRecordInput'];
      };
    };
    responses: {
      /** @description Nhật ký lần phục hồi. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RecoveryRecord'];
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
}
