package store

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// IdpMeta lưu siêu dữ liệu mở rộng của IdP (tham chiếu bí mật OpenBao, thông số kiểm kết nối, ánh xạ thuộc tính).
// Tuyệt đối không lưu clientSecret hay khoá riêng dạng rõ tại đây.
type IdpMeta struct {
	ClientID         string            `json:"clientId,omitempty"`
	SecretRef        string            `json:"secretRef,omitempty"`
	MetadataURL      string            `json:"metadataUrl,omitempty"`
	MetadataXML      string            `json:"metadataXml,omitempty"`
	LdapURL          string            `json:"ldapUrl,omitempty"`
	BindDN           string            `json:"bindDn,omitempty"`
	BaseDN           string            `json:"baseDn,omitempty"`
	UserFilter       string            `json:"userFilter,omitempty"`
	AttributeMapping map[string]string `json:"attributeMapping,omitempty"`
	LastTestedAt     *time.Time        `json:"lastTestedAt,omitempty"`
	LastTestOk       *bool             `json:"lastTestOk,omitempty"`
}

const JobTypeIdpMeta = "IDP_META"

func idpMetaRecordID(tenantID uuid.UUID) uuid.UUID {
	return uuid.NewSHA1(uuid.NameSpaceOID, []byte("registry.tenant_idp.meta:"+tenantID.String()))
}

// LoadIdpMeta đọc bản đồ siêu dữ liệu IdP theo idp_alias của đơn vị.
func LoadIdpMeta(ctx context.Context, q Querier, tenantID uuid.UUID) (map[string]IdpMeta, error) {
	var raw []byte
	err := q.QueryRow(ctx, `SELECT params FROM registry.job WHERE id = $1 AND job_type = $2`,
		idpMetaRecordID(tenantID), JobTypeIdpMeta).Scan(&raw)
	if errors.Is(err, pgx.ErrNoRows) {
		return map[string]IdpMeta{}, nil
	}
	if err != nil {
		return nil, mapErr(err)
	}
	out := map[string]IdpMeta{}
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &out); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// SaveIdpMeta ghi bản đồ siêu dữ liệu IdP theo idp_alias của đơn vị trong giao dịch hiện hành.
func SaveIdpMeta(ctx context.Context, q Querier, tenantID uuid.UUID, meta map[string]IdpMeta, actor uuid.UUID) error {
	if meta == nil {
		meta = map[string]IdpMeta{}
	}
	raw, err := json.Marshal(meta)
	if err != nil {
		return err
	}
	if actor == uuid.Nil {
		actor = SystemActor
	}
	_, err = q.Exec(ctx, `INSERT INTO registry.job (id, job_type, status, progress, params, created_by)
		VALUES ($1, $2, 'HOAN_TAT', 100, $3, $4)
		ON CONFLICT (id) DO UPDATE SET params = EXCLUDED.params, updated_at = now()`,
		idpMetaRecordID(tenantID), JobTypeIdpMeta, raw, actor)
	return mapErr(err)
}

// EmailDomainTaken kiểm tra tên miền thư điện tử đã được đơn vị khác (chưa chấm dứt) đăng ký cho SSO chưa.
func EmailDomainTaken(ctx context.Context, q Querier, emailDomain string, exceptTenantID uuid.UUID) (bool, error) {
	d := strings.ToLower(strings.TrimSpace(emailDomain))
	if d == "" {
		return false, nil
	}
	var taken bool
	err := q.QueryRow(ctx, `SELECT EXISTS (
		SELECT 1 FROM registry.tenant_idp i
		JOIN registry.tenant t ON t.id = i.tenant_id
		WHERE i.tenant_id <> $2
		  AND t.status <> 'DA_CHAM_DUT'
		  AND i.status = 'HOAT_DONG'
		  AND $1 = ANY(SELECT lower(unnest(i.email_domains)))
	)`, d, exceptTenantID).Scan(&taken)
	return taken, mapErr(err)
}
