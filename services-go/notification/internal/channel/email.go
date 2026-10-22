package channel

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"mime"
	"net"
	"net/smtp"
	"net/textproto"
	"strings"
	"time"
)

// SMTP gửi email qua máy chủ thư của tenant hoặc cell. TLSMode: "starttls" (mặc định, bắt buộc máy chủ hỗ trợ
// STARTTLS), "tls" (TLS ngay từ đầu, cổng 465), "none" chỉ cho dev và hộp thư thử.
type SMTP struct {
	Addr     string
	Host     string // tên máy chủ để kiểm chứng chỉ; trống thì lấy từ Addr
	From     string
	User     string
	Password string
	TLSMode  string
	Timeout  time.Duration
	// RootCAs riêng cho kiểm thử; nil thì dùng kho chứng chỉ hệ thống.
	TLSConfig *tls.Config
}

func (s SMTP) tlsConfig() *tls.Config {
	host := s.Host
	if host == "" {
		host, _, _ = net.SplitHostPort(s.Addr)
	}
	c := &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12}
	if s.TLSConfig != nil {
		c = s.TLSConfig.Clone()
		if c.ServerName == "" {
			c.ServerName = host
		}
	}
	return c
}

// Send gửi một thư; mã trả về là Message-ID.
func (s SMTP) Send(ctx context.Context, m Message) (string, error) {
	if !strings.Contains(m.To, "@") || strings.ContainsAny(m.To, "\r\n<>,") {
		return "", Permanent("BAD_ADDRESS")
	}
	timeout := s.Timeout
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	d := net.Dialer{}
	var conn net.Conn
	var err error
	if s.TLSMode == "tls" {
		conn, err = (&tls.Dialer{NetDialer: &d, Config: s.tlsConfig()}).DialContext(ctx, "tcp", s.Addr)
	} else {
		conn, err = d.DialContext(ctx, "tcp", s.Addr)
	}
	if err != nil {
		return "", Transient{"CONNECT"}
	}
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}
	host := s.tlsConfig().ServerName
	c, err := smtp.NewClient(conn, host)
	if err != nil {
		return "", Transient{"HELLO"}
	}
	defer c.Close()
	if s.TLSMode != "tls" && s.TLSMode != "none" {
		if ok, _ := c.Extension("STARTTLS"); !ok {
			return "", Transient{"NO_STARTTLS"}
		}
		if err := c.StartTLS(s.tlsConfig()); err != nil {
			return "", Transient{"STARTTLS"}
		}
	}
	if s.User != "" {
		if err := c.Auth(smtp.PlainAuth("", s.User, s.Password, host)); err != nil {
			return "", Transient{"AUTH"}
		}
	}
	id := messageID(s.From)
	if err := c.Mail(s.From); err != nil {
		return "", smtpErr(err, "MAIL")
	}
	if err := c.Rcpt(m.To); err != nil {
		return "", smtpErr(err, "RCPT")
	}
	w, err := c.Data()
	if err != nil {
		return "", smtpErr(err, "DATA")
	}
	if _, err := w.Write(compose(s.From, m, id)); err != nil {
		return "", Transient{"WRITE"}
	}
	if err := w.Close(); err != nil {
		return "", smtpErr(err, "DATA")
	}
	_ = c.Quit()
	return id, nil
}

// smtpErr: mã 5xx là lỗi vĩnh viễn (hộp thư không tồn tại), còn lại thử lại.
func smtpErr(err error, step string) error {
	var te *textproto.Error
	if errors.As(err, &te) && te.Code >= 500 {
		return Permanent(fmt.Sprintf("SMTP_%d", te.Code))
	}
	return Transient{"SMTP_" + step}
}

func messageID(from string) string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	domain := "mission.local"
	if i := strings.LastIndex(from, "@"); i >= 0 {
		domain = strings.Trim(from[i+1:], "> ")
	}
	return "<" + hex.EncodeToString(b) + "@" + domain + ">"
}

func compose(from string, m Message, id string) []byte {
	body := m.Body
	if m.DeepLink != "" {
		body += "\r\n\r\n" + m.DeepLink
	}
	var b strings.Builder
	h := func(k, v string) { b.WriteString(k + ": " + v + "\r\n") }
	h("From", from)
	h("To", m.To)
	h("Subject", mime.QEncoding.Encode("utf-8", m.Subject))
	h("Message-ID", id)
	h("Date", time.Now().Format(time.RFC1123Z))
	h("MIME-Version", "1.0")
	h("Content-Type", `text/plain; charset="utf-8"`)
	h("Content-Transfer-Encoding", "base64")
	h("Auto-Submitted", "auto-generated")
	b.WriteString("\r\n")
	enc := base64.StdEncoding.EncodeToString([]byte(body))
	for len(enc) > 76 {
		b.WriteString(enc[:76] + "\r\n")
		enc = enc[76:]
	}
	b.WriteString(enc + "\r\n")
	return []byte(b.String())
}
