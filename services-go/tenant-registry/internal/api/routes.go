package api

// routes đăng ký các thao tác nghiệp vụ (đường dẫn theo hợp đồng, tương đối với /api/v1).
func (s *Server) routes() {
	// platform.yaml, tag Registry (SVC-03).
	s.mux.HandleFunc("GET /api/v1/registry/resolve", s.resolveTenantCell)
	s.mux.HandleFunc("GET /api/v1/registry/cells", s.listCells)
}
