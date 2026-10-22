/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/scim.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/ServiceProviderConfig': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Cấu hình dịch vụ SCIM
     * @description Tính năng được hỗ trợ (PATCH, lọc, ETag; không có bulk, sort, đổi mật khẩu) theo RFC 7643 mục 5.
     */
    get: operations['getScimServiceProviderConfig'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ResourceTypes': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách loại tài nguyên SCIM
     * @description Hai loại tài nguyên User và Group (RFC 7643 mục 6).
     */
    get: operations['listScimResourceTypes'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ResourceTypes/{name}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Một loại tài nguyên SCIM
     * @description Loại tài nguyên theo tên (User hoặc Group).
     */
    get: operations['getScimResourceType'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/Schemas': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách lược đồ SCIM
     * @description Lược đồ User, Group và phần mở rộng nền tảng `urn:okrkpi:params:scim:schemas:extension:mission:2.0:User` (RFC 7643 mục 7).
     */
    get: operations['listScimSchemas'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/Schemas/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Một lược đồ SCIM
     * @description Lược đồ theo URN.
     */
    get: operations['getScimSchema'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/Users': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tìm người dùng SCIM
     * @description Người dùng của nguồn (client) đang gọi trong tenant của token; lọc một phép `eq` trên `userName` hoặc `externalId` (RFC 7644 mục 3.4.2). Tối đa 200 bản ghi mỗi trang.
     */
    get: operations['listScimUsers'];
    put?: never;
    /**
     * Tạo người dùng SCIM
     * @description Khớp cán bộ đã có bằng chỉ mục mù số định danh cùng ngày sinh (BR-01-12); không khớp thì tạo cán bộ mới (phát EVT-16) hoặc, khi nguồn được cấu hình đối soát trước, đẩy vào bảng lệch và trả 409. Trùng một phần (cùng số định danh khác ngày sinh, cùng họ tên) luôn vào bảng lệch. Số định danh, ngày sinh chỉ lưu dạng mã hoá và không bao giờ trả lại. Lệnh lặp đúng nội dung được ghi `BO_QUA` trong `org.scim_sync_log`.
     */
    post: operations['createScimUser'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/Users/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Đọc người dùng SCIM
     * @description Người dùng theo mã SCIM; tài nguyên đã nhận DELETE trả 404.
     */
    get: operations['getScimUser'];
    /**
     * Thay người dùng SCIM
     * @description Thay toàn bộ thuộc tính nhận được. `active: false` là tạm nghỉ từ hôm nay (phát EVT-16). Thay đổi họ tên, số định danh, ngày sinh không ghi đè hồ sơ cán bộ. `If-Match` (tuỳ chọn) lệch phiên bản trả 412.
     */
    put: operations['replaceScimUser'];
    post?: never;
    /**
     * Vô hiệu hoá người dùng SCIM
     * @description Không xoá vật lý: cán bộ chuyển trạng thái tạm nghỉ từ hôm nay (phát EVT-16), tài nguyên SCIM không còn đọc được.
     */
    delete: operations['deleteScimUser'];
    options?: never;
    head?: never;
    /**
     * Sửa một phần người dùng SCIM
     * @description Lệnh PatchOp (RFC 7644 mục 3.5.2) với `add`, `replace`, `remove` trên `active`, `userName`, `displayName`, `name.formatted`, `title` và thuộc tính `partyMember` của phần mở rộng.
     */
    patch: operations['patchScimUser'];
    trace?: never;
  };
  '/Groups': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tìm nhóm SCIM
     * @description Nhóm của nguồn đang gọi; lọc một phép `eq` trên `displayName` hoặc `externalId`.
     */
    get: operations['listScimGroups'];
    put?: never;
    /**
     * Tạo nhóm SCIM
     * @description Nhóm ánh xạ sang đơn vị đã có trên cây tổ chức có mã đơn vị bằng `externalId` (thiếu thì `displayName`); SCIM không tạo đơn vị. Thành viên là phân công chính nhánh chính quyền tại đơn vị từ hôm nay, chức danh theo `title` của người dùng (phát EVT-15).
     */
    post: operations['createScimGroup'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/Groups/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Đọc nhóm SCIM
     * @description Nhóm và thành viên hiện hành.
     */
    get: operations['getScimGroup'];
    /**
     * Thay nhóm SCIM
     * @description Thay danh sách thành viên: thành viên mới được điều động về đơn vị, thành viên bị bỏ kết thúc phân công từ hôm nay (phát EVT-15).
     */
    put: operations['replaceScimGroup'];
    post?: never;
    /**
     * Xoá nhóm SCIM
     * @description Kết thúc phân công của thành viên tại đơn vị và bỏ ánh xạ; đơn vị giữ nguyên.
     */
    delete: operations['deleteScimGroup'];
    options?: never;
    head?: never;
    /**
     * Sửa một phần nhóm SCIM
     * @description PatchOp trên `members` (`add`, `replace`, `remove`, kể cả `members[value eq "..."]`) và `displayName`.
     */
    patch: operations['patchScimGroup'];
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    Meta: {
      resourceType?: string;
      /** Format: date-time */
      created?: string;
      /** Format: date-time */
      lastModified?: string;
      /** Format: uri */
      location?: string;
      version?: string;
    };
    /** @description Phần mở rộng `urn:okrkpi:params:scim:schemas:extension:mission:2.0:User`. */
    MissionUserExtension: {
      /** @description Số định danh cá nhân (C2); chỉ dùng để khớp, lưu mã hoá, không bao giờ trả lại. */
      nationalId?: string;
      /**
       * Format: date
       * @description Ngày sinh (C2); chỉ dùng để khớp, lưu mã hoá, không bao giờ trả lại.
       */
      birthDate?: string;
      /** @description Là đảng viên. */
      partyMember?: boolean;
      /**
       * @description Trạng thái công tác hiện hành của cán bộ.
       * @enum {string}
       */
      readonly employmentStatus?: 'DANG_CONG_TAC' | 'NGHI_HUU' | 'THOI_VIEC' | 'TAM_NGHI';
    };
    /**
     * @example {
     *       "schemas": [
     *         "urn:ietf:params:scim:schemas:core:2.0:User",
     *         "urn:okrkpi:params:scim:schemas:extension:mission:2.0:User"
     *       ],
     *       "externalId": "NV-001",
     *       "userName": "an.nv",
     *       "displayName": "Nguyễn Văn An",
     *       "title": "CV",
     *       "active": true
     *     }
     */
    User: {
      schemas: string[];
      /** Format: uuid */
      readonly id?: string;
      /** @description Mã ở nguồn; thiếu thì dùng `userName`. */
      externalId?: string;
      userName: string;
      /** @description Họ và tên. */
      displayName?: string;
      name?: {
        formatted?: string;
      };
      /** @description Mã chức danh trong danh mục của tenant. */
      title?: string;
      /** @default true */
      active: boolean;
      'urn:okrkpi:params:scim:schemas:extension:mission:2.0:User'?: components['schemas']['MissionUserExtension'];
      meta?: components['schemas']['Meta'];
    };
    Member: {
      /**
       * Format: uuid
       * @description Mã người dùng SCIM của cùng nguồn.
       */
      value: string;
      /** Format: uri */
      readonly $ref?: string;
      /** @enum {string} */
      readonly type?: 'User';
    };
    Group: {
      schemas: string[];
      /** Format: uuid */
      readonly id?: string;
      /** @description Mã đơn vị trên cây tổ chức. */
      externalId?: string;
      displayName: string;
      members?: components['schemas']['Member'][];
      meta?: components['schemas']['Meta'];
    };
    PatchOperation: {
      /** @enum {string} */
      op: 'add' | 'replace' | 'remove' | 'Add' | 'Replace' | 'Remove';
      path?: string;
      value?: unknown;
    };
    PatchOp: {
      schemas: 'urn:ietf:params:scim:api:messages:2.0:PatchOp'[];
      Operations: components['schemas']['PatchOperation'][];
    };
    ListResponse: {
      schemas: string[];
      totalResults: number;
      startIndex?: number;
      itemsPerPage?: number;
      Resources: Record<string, never>[];
    };
    UserListResponse: components['schemas']['ListResponse'] & {
      Resources?: components['schemas']['User'][];
    };
    GroupListResponse: components['schemas']['ListResponse'] & {
      Resources?: components['schemas']['Group'][];
    };
    ServiceProviderConfig: {
      schemas: string[];
      /** Format: uri */
      documentationUri?: string;
      patch: components['schemas']['Supported'];
      bulk: {
        supported?: boolean;
        maxOperations?: number;
        maxPayloadSize?: number;
      };
      filter: {
        supported?: boolean;
        maxResults?: number;
      };
      changePassword: components['schemas']['Supported'];
      sort: components['schemas']['Supported'];
      etag: components['schemas']['Supported'];
      authenticationSchemes: Record<string, never>[];
      meta?: components['schemas']['Meta'];
    };
    Supported: {
      supported: boolean;
    };
    ResourceType: {
      schemas: string[];
      id: string;
      name: string;
      endpoint: string;
      description?: string;
      schema: string;
      schemaExtensions?: Record<string, never>[];
      meta?: components['schemas']['Meta'];
    };
    Schema: {
      schemas?: string[];
      id: string;
      name?: string;
      description?: string;
      attributes: Record<string, never>[];
      meta?: components['schemas']['Meta'];
    };
    Error: {
      schemas: 'urn:ietf:params:scim:api:messages:2.0:Error'[];
      /** @description Mã HTTP dạng chuỗi. */
      status: string;
      /** @enum {string} */
      scimType?:
        | 'invalidFilter'
        | 'tooMany'
        | 'uniqueness'
        | 'mutability'
        | 'invalidSyntax'
        | 'invalidPath'
        | 'noTarget'
        | 'invalidValue'
        | 'invalidVers'
        | 'sensitive';
      detail?: string;
    };
  };
  responses: {
    /** @description Lỗi theo RFC 7644 mục 3.12. */
    Error: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/scim+json': components['schemas']['Error'];
      };
    };
  };
  parameters: {
    /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
    IdempotencyKey: string;
    /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
    TenantId: string;
    /** @description ETag đọc được trước đó; lệch phiên bản trả 412 (RFC 7644 mục 3.14). */
    IfMatch: string;
    /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
    Id: string;
    /** @description Một phép so khớp chính xác, ví dụ `userName eq "an.nv"`. */
    Filter: string;
    /** @description Vị trí bắt đầu, đếm từ 1. */
    StartIndex: number;
    /** @description Số bản ghi tối đa mỗi trang. */
    Count: number;
  };
  requestBodies: never;
  headers: {
    /** @description Phiên bản yếu của tài nguyên (`meta.version`), ví dụ `W/"3"`. */
    ETag: string;
    /** @description Đường dẫn đầy đủ của tài nguyên vừa tạo (`meta.location`). */
    Location: string;
  };
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  getScimServiceProviderConfig: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Cấu hình dịch vụ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['ServiceProviderConfig'];
        };
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
    };
  };
  listScimResourceTypes: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách loại tài nguyên. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['ListResponse'];
        };
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
    };
  };
  getScimResourceType: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        name: 'User' | 'Group';
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Loại tài nguyên. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['ResourceType'];
        };
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
    };
  };
  listScimSchemas: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách lược đồ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['ListResponse'];
        };
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
    };
  };
  getScimSchema: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Lược đồ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['Schema'];
        };
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
    };
  };
  listScimUsers: {
    parameters: {
      query?: {
        /** @description Một phép so khớp chính xác, ví dụ `userName eq "an.nv"`. */
        filter?: components['parameters']['Filter'];
        /** @description Vị trí bắt đầu, đếm từ 1. */
        startIndex?: components['parameters']['StartIndex'];
        /** @description Số bản ghi tối đa mỗi trang. */
        count?: components['parameters']['Count'];
      };
      header?: {
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang người dùng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['UserListResponse'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
    };
  };
  createScimUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/scim+json': components['schemas']['User'];
      };
    };
    responses: {
      /** @description Đã tạo hoặc đã khớp. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['User'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      409: components['responses']['Error'];
    };
  };
  getScimUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Người dùng. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['User'];
        };
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
    };
  };
  replaceScimUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
        /** @description ETag đọc được trước đó; lệch phiên bản trả 412 (RFC 7644 mục 3.14). */
        'If-Match'?: components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/scim+json': components['schemas']['User'];
      };
    };
    responses: {
      /** @description Người dùng sau khi thay. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['User'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
      409: components['responses']['Error'];
      412: components['responses']['Error'];
    };
  };
  deleteScimUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đã vô hiệu hoá, không xoá vật lý. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
    };
  };
  patchScimUser: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
        /** @description ETag đọc được trước đó; lệch phiên bản trả 412 (RFC 7644 mục 3.14). */
        'If-Match'?: components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/scim+json': components['schemas']['PatchOp'];
      };
    };
    responses: {
      /** @description Người dùng sau khi sửa. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['User'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
      409: components['responses']['Error'];
      412: components['responses']['Error'];
    };
  };
  listScimGroups: {
    parameters: {
      query?: {
        /** @description Một phép so khớp chính xác, ví dụ `userName eq "an.nv"`. */
        filter?: components['parameters']['Filter'];
        /** @description Vị trí bắt đầu, đếm từ 1. */
        startIndex?: components['parameters']['StartIndex'];
        /** @description Số bản ghi tối đa mỗi trang. */
        count?: components['parameters']['Count'];
      };
      header?: {
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang nhóm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['GroupListResponse'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
    };
  };
  createScimGroup: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/scim+json': components['schemas']['Group'];
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
          'application/scim+json': components['schemas']['Group'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      409: components['responses']['Error'];
    };
  };
  getScimGroup: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Nhóm. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['Group'];
        };
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
    };
  };
  replaceScimGroup: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
        /** @description ETag đọc được trước đó; lệch phiên bản trả 412 (RFC 7644 mục 3.14). */
        'If-Match'?: components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/scim+json': components['schemas']['Group'];
      };
    };
    responses: {
      /** @description Nhóm sau khi thay. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['Group'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
      409: components['responses']['Error'];
      412: components['responses']['Error'];
    };
  };
  deleteScimGroup: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đã vô hiệu hoá, không xoá vật lý. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
    };
  };
  patchScimGroup: {
    parameters: {
      query?: never;
      header?: {
        /** @description Tuỳ chọn, vì client SCIM chuẩn không gửi. Chống ghi trùng của SCIM dựa trên băm nội dung lệnh (`org.scim_sync_log.request_hash`): lệnh lặp đúng nội dung lệnh thành công gần nhất trên cùng tài nguyên được bỏ qua và trả trạng thái hiện hành. */
        'Idempotency-Key'?: components['parameters']['IdempotencyKey'];
        /** @description Tuỳ chọn; khác tenant trong token thì bị từ chối 403. */
        'X-Tenant-Id'?: components['parameters']['TenantId'];
        /** @description ETag đọc được trước đó; lệch phiên bản trả 412 (RFC 7644 mục 3.14). */
        'If-Match'?: components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã tài nguyên SCIM do nền tảng cấp (`org.scim_resource.id`). */
        id: components['parameters']['Id'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/scim+json': components['schemas']['PatchOp'];
      };
    };
    responses: {
      /** @description Nhóm sau khi sửa. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/scim+json': components['schemas']['Group'];
        };
      };
      400: components['responses']['Error'];
      401: components['responses']['Error'];
      403: components['responses']['Error'];
      404: components['responses']['Error'];
      409: components['responses']['Error'];
      412: components['responses']['Error'];
    };
  };
}
