import { TaskGroup } from './task';

/** Mục danh mục công việc theo Bảng 3 (`task.catalog_item`, TL-03). */
export interface CatalogItem {
  id: string;
  code: string;
  name: string;
  productName: string;
  unit: string;
  /** Mức khó năm mức. */
  difficulty: number;
  standardScore: number;
  taskGroup: TaskGroup;
  qualityStandard: string;
}
