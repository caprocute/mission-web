import type { Meta, StoryObj } from '@storybook/angular';
import { MyTasksTodayWidget } from './my-tasks-today.widget';

/** Story của `vt.widget.myTasksToday` (remote fe-task); dữ liệu từ API giả lập `/api/v1/tasks/mine`. */
const meta: Meta<MyTasksTodayWidget> = {
  title: 'Widget/Việc của tôi',
  component: MyTasksTodayWidget,
  tags: ['hoi-quy'],
};
export default meta;

export const BayViec: StoryObj<MyTasksTodayWidget> = {
  name: 'vt.widget.myTasksToday',
  args: { limit: 7 },
};
