# cert-manager (SEC-59): ký CSR theo vai trò internal-services của CA trung gian của cell, không làm gì khác.
path "@PKI@/sign/internal-services"  { capabilities = ["update"] }
path "@PKI@/issue/internal-services" { capabilities = ["update"] }
