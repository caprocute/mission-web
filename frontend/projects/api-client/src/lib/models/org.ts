/** Cán bộ trong bản sao cây tổ chức (`task.org_replica`, đồng bộ từ SVC-13). */
export interface OrgPerson {
  id: string;
  name: string;
  title: string;
  unitId: string;
  unitName: string;
}

/** Đơn vị trong cây tổ chức. */
export interface OrgUnit {
  id: string;
  name: string;
  parentId: string | null;
}
