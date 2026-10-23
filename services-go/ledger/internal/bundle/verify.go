package bundle

import (
	"bytes"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/digitorus/timestamp"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/proof"
)

// Options là tuỳ chọn kiểm chứng: chứng thư gốc tin cậy của TSA và của khoá niêm phong (nếu bỏ trống, công cụ
// dùng chứng thư trong gói và in dấu vân tay để người kiểm đối chiếu), bản neo lấy từ bên độc lập.
type Options struct {
	TSARoots  *x509.CertPool
	SealRoots *x509.CertPool
	Anchors   []proof.AnchorDocument
}

// Report là kết quả kiểm chứng: dòng tóm tắt từng bước, lỗi (vị trí sai) và cảnh báo.
type Report struct {
	Lines    []string
	Errors   []string
	Warnings []string
}

// OK là true khi không có lỗi.
func (r *Report) OK() bool { return len(r.Errors) == 0 }

func (r *Report) errf(format string, a ...any) {
	r.Errors = append(r.Errors, fmt.Sprintf(format, a...))
}
func (r *Report) warnf(format string, a ...any) {
	r.Warnings = append(r.Warnings, fmt.Sprintf(format, a...))
}
func (r *Report) linef(format string, a ...any) { r.Lines = append(r.Lines, fmt.Sprintf(format, a...)) }

// maxErrors giới hạn số lỗi in ra cho mỗi bước để báo cáo đọc được khi gói hỏng nặng.
const maxErrors = 50

// Verify chạy kê khai và năm bước của TL-01 trên gói đã đọc.
func Verify(b *Bundle, opt Options) *Report {
	r := &Report{}
	verifyManifest(b, opt, r)
	tips := verifyChains(b, r)
	cellSeals := verifySeals(b, tips, opt, r)
	verifyAnchors(b, cellSeals, opt, r)
	return r
}

func verifyManifest(b *Bundle, opt Options, r *Report) {
	listed := map[string]bool{}
	bad := 0
	for _, f := range b.Manifest.Files {
		listed[f.Path] = true
		body, ok := b.files[f.Path]
		if !ok {
			r.errf("Kê khai: thiếu tệp %s", f.Path)
			bad++
			continue
		}
		sum := sha256.Sum256(body)
		if hex.EncodeToString(sum[:]) != f.SHA256 {
			r.errf("Kê khai: tệp %s đã bị sửa (SHA-256 không khớp kê khai)", f.Path)
			bad++
		}
	}
	for name := range b.files {
		if name != FileManifest && name != FileSignature && !listed[name] {
			r.warnf("Kê khai: tệp %s không có trong kê khai, đã bỏ qua", name)
		}
	}
	der, ok := b.Certs[b.Manifest.SignerCertSHA256]
	if !ok {
		r.errf("Kê khai: gói thiếu chứng thư của khoá ký kê khai %s", b.Manifest.SignerCertSHA256)
		return
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		r.errf("Kê khai: chứng thư khoá ký không đọc được: %v", err)
		return
	}
	sigOK := proof.VerifySignature(cert, b.files[FileManifest], b.Signature)
	if !sigOK {
		r.errf("Kê khai: chữ ký kê khai không hợp lệ")
	}
	created, _ := time.Parse(time.RFC3339Nano, b.Manifest.CreatedAt)
	trust := checkTrust(cert, opt.SealRoots, x509.ExtKeyUsageAny, r, "khoá ký kê khai", created)
	state := "hợp lệ"
	if !sigOK {
		state = "KHÔNG hợp lệ"
	}
	r.linef("Kê khai: %d tệp, %d tệp sai; chữ ký %s (%s, SHA-256 %s)%s", len(b.Manifest.Files), bad, state,
		cert.Subject.CommonName, b.Manifest.SignerCertSHA256, trust)
}

// checkTrust kiểm chuỗi chứng thư khi có gốc tin cậy; không có thì cảnh báo và trả chú thích.
func checkTrust(cert *x509.Certificate, roots *x509.CertPool, usage x509.ExtKeyUsage, r *Report, what string, at time.Time) string {
	if roots == nil {
		r.warnf("Chưa đối chiếu chứng thư %s (%s) với gốc tin cậy; hãy so dấu vân tay với công bố của đơn vị vận hành",
			what, cert.Subject.CommonName)
		return ""
	}
	if _, err := cert.Verify(x509.VerifyOptions{Roots: roots, KeyUsages: []x509.ExtKeyUsage{usage},
		CurrentTime: at}); err != nil {
		r.errf("Chứng thư %s (%s) không thuộc gốc tin cậy đã cho: %v", what, cert.Subject.CommonName, err)
		return ", chuỗi chứng thư KHÔNG hợp lệ"
	}
	return ", chuỗi chứng thư hợp lệ"
}

type chainTip struct {
	chain int16
	seq   int64
}

// verifyChains là bước 1: tính lại metadata_hash, entry_hash của từng bản ghi, kiểm prev_hash nối liền và
// seq_no không thiếu trong mỗi đoạn. Trả entry_hash đã kiểm theo (chuỗi, seq_no) cho bước 2.
func verifyChains(b *Bundle, r *Report) map[chainTip]string {
	tips := map[chainTip]string{}
	var total, inScope int64
	errs := 0
	report := func(format string, a ...any) {
		errs++
		if errs <= maxErrors {
			r.errf(format, a...)
		}
	}
	chains := map[int16]bool{}
	for _, c := range b.Entries.Chains {
		if c.ChainPartition < 0 || c.ChainPartition >= canon.ChainPartitionCount {
			report("Chuỗi %d: phân vùng ngoài khoảng 0..63", c.ChainPartition)
			continue
		}
		chains[c.ChainPartition] = true
		for _, seg := range c.Segments {
			var prev string
			for i, rec := range seg.Records {
				total++
				if rec.InScope {
					inScope++
				}
				where := fmt.Sprintf("Chuỗi %d, seq_no %d", c.ChainPartition, rec.SeqNo)
				if i > 0 {
					if rec.SeqNo != seg.Records[i-1].SeqNo+1 {
						report("Chuỗi %d: thiếu seq_no %d..%d", c.ChainPartition, seg.Records[i-1].SeqNo+1, rec.SeqNo-1)
					}
					if rec.PrevHash != prev {
						report("%s: prev_hash không khớp entry_hash của bản ghi liền trước", where)
					}
				}
				prev = rec.EntryHash
				if rec.InScope {
					d, err := canon.MetadataDigest(rec.Metadata)
					if err != nil || d != rec.MetadataSHA256 {
						report("%s: metadata của bản ghi không khớp băm metadata (siêu dữ liệu bản ghi đã bị sửa)", where)
						continue
					}
				}
				meta, err := metadataHash(b.Manifest.TenantID, c.ChainPartition, rec)
				if err != nil {
					report("%s: %v", where, err)
					continue
				}
				if hex.EncodeToString(meta[:]) != rec.MetadataHash {
					report("%s: metadata_hash tính lại không khớp (siêu dữ liệu bản ghi đã bị sửa)", where)
					continue
				}
				p, err1 := hash32(rec.PayloadHash)
				pv, err2 := hash32(rec.PrevHash)
				if err1 != nil || err2 != nil {
					report("%s: payload_hash hoặc prev_hash không hợp lệ", where)
					continue
				}
				h := canon.EntryHash(pv, p, meta)
				if hex.EncodeToString(h[:]) != rec.EntryHash {
					report("%s: entry_hash tính lại không khớp", where)
					continue
				}
				tips[chainTip{c.ChainPartition, rec.SeqNo}] = rec.EntryHash
			}
		}
	}
	if errs > maxErrors {
		r.errf("... và %d lỗi bản ghi khác", errs-maxErrors)
	}
	state := "liên kết hợp lệ, không thiếu seq"
	if errs > 0 {
		state = fmt.Sprintf("%d lỗi", errs)
	}
	r.linef("Chuỗi %s: %s bản ghi (%s trong phạm vi), %s", chainList(chains), number(total), number(inScope), state)
	if b.Manifest.UnsealedExcluded > 0 {
		r.warnf("%s bản ghi trong phạm vi chưa được niêm phong nên chưa có trong gói", number(b.Manifest.UnsealedExcluded))
	}
	return tips
}

func metadataHash(tenant string, chain int16, rec Record) ([32]byte, error) {
	h, _, err := canon.MetadataHash(canon.MetadataHeader{
		TenantID: tenant, ChainPartition: chain, SeqNo: rec.SeqNo, EventType: rec.EventType,
		AggregateType: rec.AggregateType, AggregateID: rec.AggregateID, ActorID: rec.ActorID, ActorKind: rec.ActorKind,
		OccurredAt: rec.OccurredAt, RecordedAt: rec.RecordedAt, MetadataSHA256: rec.MetadataSHA256,
	})
	return h, err
}

// verifySeals gồm bước 2 (dựng lại gốc tenant từ đầu chuỗi; đầu chuỗi khớp bản ghi trong gói), bước 3 (đường đi
// Merkle tới gốc cell) và bước 4 (chữ ký gốc cell, token RFC 3161). Trả gốc cell đã kiểm theo mã niêm phong cell.
func verifySeals(b *Bundle, tips map[chainTip]string, opt Options, r *Report) map[string]string {
	cells := map[string]string{}
	var (
		rootErr, sigErr, tsaErr int
		tsaOK                   int
		earliest                time.Time
		signers, tsas           = map[string]bool{}, map[string]bool{}
	)
	covered := map[chainTip]bool{}
	for _, s := range b.Seals {
		where := fmt.Sprintf("Niêm phong %s (%s..%s)", s.ID, s.WindowStart, s.WindowEnd)
		root, err := proof.TenantRoot(s.Ranges)
		if err != nil || hex.EncodeToString(root[:]) != s.TenantRoot {
			rootErr++
			r.errf("%s: gốc tenant dựng lại từ đầu chuỗi không khớp", where)
			continue
		}
		for _, rg := range s.Ranges {
			k := chainTip{rg.Partition, rg.To}
			if got, ok := tips[k]; ok {
				covered[k] = true
				if got != rg.Tip {
					rootErr++
					r.errf("%s: đầu chuỗi %d tại seq_no %d khác entry_hash của bản ghi trong gói", where, rg.Partition, rg.To)
				}
			}
		}
		steps, err := stepsFrom(s.CellPath)
		cellRoot, err2 := hash32(s.Cell.CellRoot)
		if err != nil || err2 != nil || !proof.Verify(proof.Leaf(root[:]), steps, cellRoot) {
			rootErr++
			r.errf("%s: đường đi Merkle mức cell không ra gốc cell %s", where, s.Cell.CellRoot)
			continue
		}
		prevCell, err := hash32(s.Cell.PrevCellRoot)
		windowEnd, err2 := time.Parse(time.RFC3339Nano, s.Cell.WindowEnd)
		der, ok := b.Certs[s.Cell.SignerCertSHA256]
		var cert *x509.Certificate
		if ok {
			cert, _ = x509.ParseCertificate(der)
		}
		if err != nil || err2 != nil || cert == nil ||
			!proof.VerifySignature(cert, proof.SignedMessage(cellRoot, prevCell, windowEnd), s.Cell.Signature) {
			sigErr++
			r.errf("%s: chữ ký gốc cell không hợp lệ hoặc thiếu chứng thư khoá niêm phong", where)
			continue
		}
		signers[cert.Subject.CommonName] = true
		tok, err := proof.ParseToken(s.Cell.TSAToken, cellRoot)
		if err != nil {
			tsaErr++
			r.errf("%s: token RFC 3161 không hợp lệ: %v", where, err)
			continue
		}
		if ts, err := timestamp.ParseResponse(s.Cell.TSAToken); err == nil && len(ts.Certificates) > 0 {
			tsas[ts.Certificates[0].Subject.CommonName] = true
			if opt.TSARoots != nil {
				if _, err := ts.Certificates[0].Verify(x509.VerifyOptions{Roots: opt.TSARoots, Intermediates: pool(ts.Certificates[1:]),
					KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageTimeStamping}, CurrentTime: tok.GenTime}); err != nil {
					tsaErr++
					r.errf("%s: chứng thư TSA không thuộc gốc tin cậy đã cho: %v", where, err)
					continue
				}
			}
		}
		tsaOK++
		if earliest.IsZero() || tok.GenTime.Before(earliest) {
			earliest = tok.GenTime
		}
		cells[s.Cell.ID] = s.Cell.CellRoot
	}
	// Mỗi đoạn chuỗi phải kết thúc ở một đầu chuỗi đã niêm phong, nếu không bản ghi của đoạn không được chứng minh.
	for _, c := range b.Entries.Chains {
		for _, seg := range c.Segments {
			if n := len(seg.Records); n > 0 {
				last := seg.Records[n-1].SeqNo
				if !covered[chainTip{c.ChainPartition, last}] {
					rootErr++
					r.errf("Chuỗi %d, seq_no %d: đoạn chuỗi không kết thúc ở đầu chuỗi của niêm phong nào trong gói", c.ChainPartition, last)
				}
			}
		}
	}
	state := "gốc khớp, chữ ký hợp lệ"
	if rootErr+sigErr > 0 {
		state = fmt.Sprintf("%d lỗi gốc, %d lỗi chữ ký", rootErr, sigErr)
	}
	r.linef("Niêm phong: %s lần, %s (%s)", number(int64(len(b.Seals))), state, names(signers))
	if len(b.Seals) > 0 {
		for _, der := range b.Certs {
			if c, err := x509.ParseCertificate(der); err == nil && signers[c.Subject.CommonName] {
				at, _ := time.Parse(time.RFC3339Nano, b.Seals[0].Cell.WindowEnd)
				checkTrust(c, opt.SealRoots, x509.ExtKeyUsageAny, r, "khoá niêm phong", at)
				break
			}
		}
	}
	line := fmt.Sprintf("Dấu thời gian RFC 3161: %s token hợp lệ (TSA: %s)", number(int64(tsaOK)), names(tsas))
	if !earliest.IsZero() {
		line += ", sớm nhất " + earliest.UTC().Format(time.RFC3339)
	}
	if tsaErr > 0 {
		line += fmt.Sprintf(", %d token lỗi", tsaErr)
	}
	r.Lines = append(r.Lines, line)
	if opt.TSARoots == nil && tsaOK > 0 {
		r.warnf("Chưa đối chiếu chứng thư TSA với gốc tin cậy (dùng --tsa-ca để kiểm chuỗi chứng thư)")
	}
	return cells
}

func pool(certs []*x509.Certificate) *x509.CertPool {
	p := x509.NewCertPool()
	for _, c := range certs {
		p.AddCert(c)
	}
	return p
}

// verifyAnchors là bước 5: đối chiếu gốc cell với bản neo lấy trực tiếp từ bên độc lập.
func verifyAnchors(b *Bundle, cells map[string]string, opt Options, r *Report) {
	if len(opt.Anchors) == 0 {
		if len(b.Anchors) > 0 {
			r.linef("Bản neo: gói có %d biên nhận; chưa đối chiếu với bên độc lập (dùng --anchors)", len(b.Anchors))
		}
		return
	}
	matched, inScope := 0, 0
	for _, a := range opt.Anchors {
		root, ok := cells[a.CellSealID.String()]
		if !ok {
			continue
		}
		inScope++
		if !strings.EqualFold(root, a.CellRoot) {
			r.errf("Bản neo ngày %s: gốc cell của bên độc lập khác gốc trong gói (niêm phong cell %s)", a.Day, a.CellSealID)
			continue
		}
		matched++
	}
	r.linef("Bản neo: %d/%d ngày khớp với bên độc lập (%d bản neo ngoài phạm vi gói)", matched, inScope, len(opt.Anchors)-inScope)
}

func chainList(m map[int16]bool) string {
	if len(m) == 0 {
		return "(không có)"
	}
	var ks []int
	for k := range m {
		ks = append(ks, int(k))
	}
	sort.Ints(ks)
	if len(ks) == int(canon.ChainPartitionCount) {
		return "0..63"
	}
	var parts []string
	for _, k := range ks {
		parts = append(parts, fmt.Sprint(k))
	}
	return strings.Join(parts, ", ")
}

func names(m map[string]bool) string {
	var out []string
	for k := range m {
		out = append(out, "CN="+k)
	}
	sort.Strings(out)
	if len(out) == 0 {
		return "không có"
	}
	return strings.Join(out, ", ")
}

// number in số theo cách viết Việt Nam (dấu chấm phân cách hàng nghìn).
func number(n int64) string {
	s := fmt.Sprint(n)
	var buf bytes.Buffer
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 && s[0] != '-' {
			buf.WriteByte('.')
		}
		buf.WriteRune(c)
	}
	return buf.String()
}
