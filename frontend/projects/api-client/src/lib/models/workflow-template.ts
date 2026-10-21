import type { components } from '../generated/platform';

type S = components['schemas'];

/** Trạng thái phiên bản mẫu luồng (`flowable.wf_template.status`, BS-01 "Vòng đời phiên bản"). */
export type WorkflowTemplateStatus = S['WorkflowTemplateStatus'];
export type WorkflowTemplateSummary = S['WorkflowTemplateSummary'];
/** Một phiên bản mẫu luồng: BPMN, khai báo tham số `param_schema`, gói ký số (BS-01 SCR-1505). */
export type WorkflowTemplate = S['WorkflowTemplate'];
export type WorkflowTemplatePage = S['WorkflowTemplatePage'];
export type WorkflowTemplateCreate = S['WorkflowTemplateCreate'];
export type WorkflowTemplateUpdate = S['WorkflowTemplateUpdate'];
export type WorkflowTemplateRolloutList = S['WorkflowTemplateRolloutList'];
export type WorkflowTemplateRollout = WorkflowTemplateRolloutList['items'][number];
export type WorkflowRolloutStatus = WorkflowTemplateRollout['status'];

export const WF_TEMPLATE_STATUS_LABEL: Record<WorkflowTemplateStatus, string> = {
  NHAP: 'Bản nháp',
  DAT_KIEM_THU: 'Đạt kiểm thử',
  CHO_PHE_DUYET: 'Chờ phê duyệt',
  HIEU_LUC: 'Đang hiệu lực',
  NGUNG: 'Ngừng dùng',
};

export const WF_ROLLOUT_STATUS_LABEL: Record<WorkflowRolloutStatus, string> = {
  CHO: 'Chờ triển khai',
  DA_TRIEN_KHAI: 'Đã triển khai',
  LOI: 'Lỗi, đang xử lý',
  DA_BU_TRU: 'Đã đặt lại bản trước',
};
