package api

// routes đăng ký các thao tác nghiệp vụ (đường dẫn theo hợp đồng, tương đối với /api/v1).
func (s *Server) routes() {
	// platform.yaml, tag Registry (SVC-03).
	s.mux.HandleFunc("GET /api/v1/registry/resolve", s.resolveTenantCell)
	s.mux.HandleFunc("GET /api/v1/registry/cells", s.listCells)
	s.mux.HandleFunc("POST /api/v1/registry/tenants", s.createRegistryTenant)
	s.mux.HandleFunc("GET /api/v1/registry/tenants/{id}", s.getRegistryTenant)
	// DV-03-07: chuyển tenant giữa các cell (ADR-29, bốn mắt).
	s.mux.HandleFunc("POST /api/v1/registry/tenants/{id}/move", s.requestTenantMove)
	s.mux.HandleFunc("POST /api/v1/registry/tenants/{id}/move/{moveId}/approve", s.approveTenantMove)
	s.mux.HandleFunc("GET /api/v1/registry/tenants/{id}/move/{moveId}", s.getTenantMove)
	s.mux.HandleFunc("POST /api/v1/registry/tenants/{id}/move/{moveId}/cancel", s.cancelTenantMove)

	// config.yaml, tag Tenant, phần /config/tenants* (DV-03-03; hợp đồng ghi owner MOD-08, hiện thực tại SVC-03).
	s.mux.HandleFunc("GET /api/v1/config/tenants", s.listConfigTenants)
	s.mux.HandleFunc("POST /api/v1/config/tenants", s.createConfigTenant)
	s.mux.HandleFunc("POST /api/v1/config/tenants/export", s.exportConfigTenants)
	s.mux.HandleFunc("GET /api/v1/config/tenants/{id}", s.getConfigTenant)
	s.mux.HandleFunc("PATCH /api/v1/config/tenants/{id}", s.updateConfigTenant)
	s.mux.HandleFunc("DELETE /api/v1/config/tenants/{id}", s.cancelConfigTenant)
	s.mux.HandleFunc("POST /api/v1/config/tenants/{id}/idp/test", s.testConfigTenantIdp)
	// DV-03-05: saga cấp phát (QT-01 bước 4) và kích hoạt (bước 9).
	s.mux.HandleFunc("POST /api/v1/config/tenants/{id}/provision", s.provisionConfigTenant)
	s.mux.HandleFunc("POST /api/v1/config/tenants/{id}/activate", s.activateConfigTenant)
	s.mux.HandleFunc("POST /api/v1/config/tenants/{id}/suspend", s.suspendConfigTenant)
	s.mux.HandleFunc("POST /api/v1/config/tenants/{id}/terminate", s.terminateConfigTenant)

	// jobs.yaml getJob cho tác vụ do SVC-03 tạo (owner SVC-05; cổng định tuyến theo tác vụ, wiki trang 14).
	s.mux.HandleFunc("GET /api/v1/jobs/{id}", s.getJob)
}
