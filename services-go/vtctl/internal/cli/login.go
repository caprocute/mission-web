package cli

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"vn.viettel.okrkpi/services-go/vtctl/internal/auth"
)

func loginCmd(a *App) *cobra.Command {
	var stepUp bool
	cmd := &cobra.Command{
		Use:   "login",
		Short: "Đăng nhập Keycloak vận hành (platform-ops) bằng mã thiết bị",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(false)
			if err != nil {
				return err
			}
			s, err := a.session(t)
			if err != nil {
				return err
			}
			s.Interactive = true
			if err := s.Login(cmd.Context(), stepUp); err != nil {
				return err
			}
			c, err := s.Claims()
			if err != nil {
				return err
			}
			fmt.Fprintf(a.Out, "Đã đăng nhập môi trường %s: %s (bậc xác thực %s)\n", t.EnvName, c.Actor(), c.ACR)
			return nil
		},
	}
	cmd.Flags().BoolVar(&stepUp, "step-up", false, "xin xác thực bước lên aal2 (MFA) cho thao tác cần aal: 2")
	return cmd
}

func logoutCmd(a *App) *cobra.Command {
	return &cobra.Command{
		Use:   "logout",
		Short: "Xoá token đã lưu của môi trường",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(false)
			if err != nil {
				return err
			}
			s, err := a.session(t)
			if err != nil {
				return err
			}
			if err := s.Logout(); err != nil {
				return err
			}
			fmt.Fprintf(a.Out, "Đã đăng xuất môi trường %s\n", t.EnvName)
			return nil
		},
	}
}

func whoamiCmd(a *App) *cobra.Command {
	return &cobra.Command{
		Use:   "whoami",
		Short: "Xem tài khoản, vai trò và bậc xác thực của token đang dùng",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			t, err := a.target(false)
			if err != nil {
				return err
			}
			s, err := a.session(t)
			if err != nil {
				return err
			}
			c, err := s.Claims()
			if err != nil {
				return err
			}
			a.print(map[string]any{
				"taiKhoan":      c.Actor(),
				"vaiTro":        c.RealmAccess.Roles,
				"bacXacThuc":    c.ACR,
				"buocLenConHan": c.AAL2Fresh(a.Now(), auth.StepUpMaxAge),
				"hetHan":        time.Unix(c.Exp, 0).UTC().Format(time.RFC3339),
			})
			return nil
		},
	}
}
