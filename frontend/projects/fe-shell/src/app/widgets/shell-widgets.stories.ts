import type { Meta, StoryObj } from '@storybook/angular';
import { AnnouncementWidget } from './announcement.widget';
import { OkrProgressWidget } from './okr-progress.widget';
import { PendingApprovalsWidget } from './pending-approvals.widget';

/** Story của ba widget SDUI shell đăng ký (`vt.widget.announcement`, `okrProgress`, `pendingApprovals`). */
const meta: Meta = { title: 'Widget/Shell', tags: ['hoi-quy'] };
export default meta;

export const ThongBao: StoryObj<AnnouncementWidget> = {
  name: 'vt.widget.announcement',
  render: () => ({
    template: `<vt-announcement-widget i18nKey="home.notice.q4" />`,
    moduleMetadata: { imports: [AnnouncementWidget] },
  }),
};

export const TienDoOkrDonVi: StoryObj<OkrProgressWidget> = {
  name: 'vt.widget.okrProgress',
  render: () => ({
    template: `<vt-okr-progress-widget scope="unit" />`,
    moduleMetadata: { imports: [OkrProgressWidget] },
  }),
};

export const ChoDuyet: StoryObj<PendingApprovalsWidget> = {
  name: 'vt.widget.pendingApprovals',
  render: () => ({
    template: `<vt-pending-approvals-widget />`,
    moduleMetadata: { imports: [PendingApprovalsWidget] },
  }),
};
