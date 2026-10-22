# External Secrets Operator (ADR-17): chỉ đọc KV v2. Phạm vi namespace được ESO giới hạn bằng conditions của
# ClusterSecretStore và bằng đường dẫn khoá trong ExternalSecret của từng chart.
path "kv/data/*"     { capabilities = ["read"] }
path "kv/metadata/*" { capabilities = ["read", "list"] }
