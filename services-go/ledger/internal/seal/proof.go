package seal

import "mission/services-go/ledger/internal/proof"

// Bí danh tới package proof (phần thuần tính toán, dùng chung với ledger-verify).
type (
	Step           = proof.Step
	ChainRange     = proof.ChainRange
	Token          = proof.Token
	AnchorDocument = proof.AnchorDocument
)

var (
	Leaf            = proof.Leaf
	Node            = proof.Node
	Root            = proof.Root
	Path            = proof.Path
	Verify          = proof.Verify
	TenantRoot      = proof.TenantRoot
	SignedMessage   = proof.SignedMessage
	VerifySignature = proof.VerifySignature
	ParseToken      = proof.ParseToken
)
