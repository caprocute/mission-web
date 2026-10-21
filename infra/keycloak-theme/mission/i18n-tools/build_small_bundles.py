# -*- coding: utf-8 -*-
import os
import re

def parse_props(path):
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    entries = []
    i = 0
    while i < len(lines):
        line = lines[i].rstrip("\r\n")
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            i += 1
            continue
        while line.endswith("\\") and not line.endswith("\\\\") and i + 1 < len(lines):
            i += 1
            line = line[:-1] + lines[i].rstrip("\r\n").lstrip()
        m = re.match(r"^((?:\\.|[^=])+?)=(.*)$", line)
        if m:
            entries.append((m.group(1), m.group(2)))
        else:
            if entries:
                entries[-1] = (entries[-1][0], entries[-1][1] + " " + stripped)
        i += 1
    return dict(entries)

# 1. EMAIL (65 keys)
EMAIL_VI = {
    "emailVerificationSubject": "Xác minh thư điện tử",
    "emailVerificationBody": "Ai đó đã tạo tài khoản {2} với địa chỉ thư điện tử này. Nếu là bạn, vui lòng nhấp vào liên kết bên dưới để xác minh địa chỉ thư điện tử của bạn\n\n{0}\n\nLiên kết này sẽ hết hạn sau {1,choice,0#0 phút|1#1 phút|1<{1} phút}.\n\nNếu bạn không tạo tài khoản này, hãy bỏ qua thông báo này.",
    "emailVerificationBodyHtml": "<p>Ai đó đã tạo tài khoản {2} với địa chỉ thư điện tử này. Nếu là bạn, vui lòng nhấp vào liên kết bên dưới để xác minh địa chỉ thư điện tử của bạn</p><p><a href=\"{0}\">Liên kết xác minh địa chỉ thư điện tử</a></p><p>Liên kết này sẽ hết hạn sau {1,choice,0#0 phút|1#1 phút|1<{1} phút}.</p><p>Nếu bạn không tạo tài khoản này, hãy bỏ qua thông báo này.</p>",
    "emailTestSubject": "[KEYCLOAK] - Kiểm tra cấu hình SMTP",
    "emailTestBody": "Đây là thư kiểm tra cấu hình SMTP từ hệ thống {0}",
    "emailTestBodyHtml": "<p>Đây là thư kiểm tra cấu hình SMTP từ hệ thống {0}</p>",
    "identityProviderLinkSubject": "Liên kết với nhà cung cấp định danh {0}",
    "identityProviderLinkBody": "Ai đó muốn liên kết tài khoản '{1}' của bạn với tài khoản '{0}' của người dùng {2}. Nếu là bạn, vui lòng nhấp vào liên kết bên dưới để xác nhận liên kết định danh\n\n{3}\n\nLiên kết này sẽ hết hạn sau {4,choice,0#0 phút|1#1 phút|1<{4} phút}.\n\nNếu bạn không muốn liên kết tài khoản, hãy bỏ qua thông báo này. Sau khi liên kết, bạn có thể đăng nhập vào {1} thông qua {0}.",
    "identityProviderLinkBodyHtml": "<p>Ai đó muốn liên kết tài khoản <b>{1}</b> của bạn với tài khoản <b>{0}</b> của người dùng {2}. Nếu là bạn, vui lòng nhấp vào liên kết bên dưới để xác nhận liên kết định danh</p><p><a href=\"{3}\">Liên kết xác nhận kết nối tài khoản</a></p><p>Liên kết này sẽ hết hạn sau {4,choice,0#0 phút|1#1 phút|1<{4} phút}.</p><p>Nếu bạn không muốn liên kết tài khoản, hãy bỏ qua thông báo này. Sau khi liên kết, bạn có thể đăng nhập vào {1} thông qua {0}.</p>",
    "passwordResetSubject": "Đặt lại mật khẩu",
    "passwordResetBody": "Ai đó vừa yêu cầu đổi thông tin xác thực tài khoản {2} của bạn. Nếu là bạn, vui lòng nhấp vào liên kết bên dưới để đặt lại mật khẩu.\n\n{0}\n\nLiên kết này cùng các thông tin liên quan sẽ hết hạn sau {1,choice,0#0 phút|1#1 phút|1<{1} phút}.\n\nNếu bạn không muốn đặt lại thông tin xác thực, hãy bỏ qua thông báo này và không có gì thay đổi.",
    "passwordResetBodyHtml": "<p>Ai đó vừa yêu cầu đổi thông tin xác thực tài khoản {2} của bạn. Nếu là bạn, vui lòng nhấp vào liên kết bên dưới để đặt lại mật khẩu.</p><p><a href=\"{0}\">Liên kết đặt lại mật khẩu</a></p><p>Liên kết này sẽ hết hạn sau {1,choice,0#0 phút|1#1 phút|1<{1} phút}.</p><p>Nếu bạn không muốn đặt lại thông tin xác thực, hãy bỏ qua thông báo này và không có gì thay đổi.</p>",
    "executeActionsSubject": "Cập nhật tài khoản của bạn",
    "executeActionsBody": "Quản trị viên vừa yêu cầu bạn cập nhật tài khoản {2} bằng cách thực hiện thao tác: {3}. Vui lòng nhấp vào liên kết bên dưới để bắt đầu thực hiện.\n\n{0}\n\nLiên kết này sẽ hết hạn sau {1,choice,0#0 phút|1#1 phút|1<{1} phút}.\n\nNếu bạn không biết về việc quản trị viên yêu cầu, hãy bỏ qua thông báo này và không có gì thay đổi.",
    "executeActionsBodyHtml": "<p>Quản trị viên vừa yêu cầu bạn cập nhật tài khoản {2} bằng cách thực hiện thao tác: {3}. Vui lòng nhấp vào liên kết bên dưới để bắt đầu thực hiện.</p><p><a href=\"{0}\">Liên kết cập nhật tài khoản</a></p><p>Liên kết này sẽ hết hạn sau {1,choice,0#0 phút|1#1 phút|1<{1} phút}.</p><p>Nếu bạn không biết về việc quản trị viên yêu cầu, hãy bỏ qua thông báo này và không có gì thay đổi.</p>",
    "eventLoginErrorSubject": "Lỗi đăng nhập",
    "eventLoginErrorBody": "Hệ thống phát hiện một lần đăng nhập thất bại vào tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn, vui lòng liên hệ với quản trị viên.",
    "eventLoginErrorBodyHtml": "<p>Hệ thống phát hiện một lần đăng nhập thất bại vào tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn, vui lòng liên hệ với quản trị viên.</p>",
    "eventRemoveTotpSubject": "Đã gỡ bỏ OTP",
    "eventRemoveTotpBody": "Thiết bị OTP đã được gỡ bỏ khỏi tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.",
    "eventRemoveTotpBodyHtml": "<p>Thiết bị OTP đã được gỡ bỏ khỏi tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.</p>",
    "eventUpdatePasswordSubject": "Đã cập nhật mật khẩu",
    "eventUpdatePasswordBody": "Mật khẩu của bạn đã được thay đổi vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.",
    "eventUpdatePasswordBodyHtml": "<p>Mật khẩu của bạn đã được thay đổi vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.</p>",
    "eventUpdateTotpSubject": "Đã cập nhật OTP",
    "eventUpdateTotpBody": "Cấu hình OTP đã được cập nhật cho tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.",
    "eventUpdateTotpBodyHtml": "<p>Cấu hình OTP đã được cập nhật cho tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.</p>",
    "eventUpdateCredentialSubject": "Đã cập nhật thông tin xác thực",
    "eventUpdateCredentialBody": "Thông tin xác thực {2} đã được cập nhật cho tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.",
    "eventUpdateCredentialBodyHtml": "<p>Thông tin xác thực {2} đã được cập nhật cho tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.</p>",
    "eventRemoveCredentialSubject": "Đã gỡ bỏ thông tin xác thực",
    "eventRemoveCredentialBody": "Thông tin xác thực {2} đã được gỡ bỏ khỏi tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.",
    "eventRemoveCredentialBodyHtml": "<p>Thông tin xác thực {2} đã được gỡ bỏ khỏi tài khoản của bạn vào lúc {0} từ địa chỉ {1}. Nếu không phải là bạn thực hiện, vui lòng liên hệ với quản trị viên.</p>",
    "eventUserDisabledByPermanentLockoutSubject": "Tài khoản người dùng bị khóa vĩnh viễn",
    "eventUserDisabledByPermanentLockoutBody": "Tài khoản người dùng của bạn đã bị khóa vĩnh viễn vào lúc {0} do vượt quá số lần đăng nhập sai tối đa. Vui lòng liên hệ với quản trị viên.",
    "eventUserDisabledByPermanentLockoutBodyHtml": "<p>Tài khoản người dùng của bạn đã bị khóa vĩnh viễn vào lúc {0} do vượt quá số lần đăng nhập sai tối đa. Vui lòng liên hệ với quản trị viên.</p>",
    "eventUserDisabledByTemporaryLockoutSubject": "Tài khoản người dùng bị tạm khóa",
    "eventUserDisabledByTemporaryLockoutBody": "Tài khoản người dùng của bạn đã bị tạm khóa vào lúc {0} đến {1} do vượt quá số lần đăng nhập sai tối đa. Nếu không phải là bạn, vui lòng liên hệ với quản trị viên.",
    "eventUserDisabledByTemporaryLockoutBodyHtml": "<p>Tài khoản người dùng của bạn đã bị tạm khóa vào lúc {0} đến {1} do vượt quá số lần đăng nhập sai tối đa. Nếu không phải là bạn, vui lòng liên hệ với quản trị viên.</p>",
    "requiredAction.CONFIGURE_TOTP": "Cấu hình OTP",
    "requiredAction.TERMS_AND_CONDITIONS": "Điều khoản và điều kiện",
    "requiredAction.UPDATE_PASSWORD": "Cập nhật mật khẩu",
    "requiredAction.UPDATE_PROFILE": "Cập nhật hồ sơ",
    "requiredAction.VERIFY_EMAIL": "Xác minh thư điện tử",
    "requiredAction.CONFIGURE_RECOVERY_AUTHN_CODES": "Tạo mã khôi phục",
    "linkExpirationFormatter.timePeriodUnit.seconds": "{0,choice,0#0 giây|1#1 giây|1<{0} giây}",
    "linkExpirationFormatter.timePeriodUnit.minutes": "{0,choice,0#0 phút|1#1 phút|1<{0} phút}",
    "linkExpirationFormatter.timePeriodUnit.hours": "{0,choice,0#0 giờ|1#1 giờ|1<{0} giờ}",
    "linkExpirationFormatter.timePeriodUnit.days": "{0,choice,0#0 ngày|1#1 ngày|1<{0} ngày}",
    "orgInviteSubject": "Lời mời tham gia tổ chức {0}",
    "orgInviteBody": "Bạn đã được mời tham gia tổ chức {0}. Nếu bạn muốn tham gia, vui lòng nhấp vào liên kết bên dưới.\n\n{1}\n\nLiên kết này sẽ hết hạn sau {2,choice,0#0 phút|1#1 phút|1<{2} phút}.\n\nNếu bạn không muốn tham gia tổ chức này, hãy bỏ qua thông báo này.",
    "orgInviteBodyHtml": "<p>Bạn đã được mời tham gia tổ chức {0}. Nếu bạn muốn tham gia, vui lòng nhấp vào liên kết bên dưới.</p><p><a href=\"{1}\">Liên kết tham gia tổ chức</a></p><p>Liên kết này sẽ hết hạn sau {2,choice,0#0 phút|1#1 phút|1<{2} phút}.</p><p>Nếu bạn không muốn tham gia tổ chức này, hãy bỏ qua thông báo này.</p>",
    "termsText": "Nội dung điều khoản và điều kiện cần được định nghĩa",
    "kc.mm.Date": "{0,date,dd/MM/yyyy}",
    "kc.mm.DateTime": "{0,date,dd/MM/yyyy} {0,time,HH:mm:ss}",
    "kc.mm.Time": "{0,time,HH:mm:ss}",
    "locale_ar": "Tiếng Ả Rập",
    "locale_ca": "Tiếng Catalan",
    "locale_cs": "Tiếng Séc",
    "locale_da": "Tiếng Đan Mạch",
    "locale_de": "Tiếng Đức",
    "locale_el": "Tiếng Hy Lạp",
    "locale_en": "English",
    "locale_es": "Tiếng Tây Ban Nha",
    "locale_fa": "Tiếng Ba Tư",
    "locale_fi": "Tiếng Phần Lan",
    "locale_fr": "Tiếng Pháp",
    "locale_hr": "Tiếng Croatia",
    "locale_hu": "Tiếng Hungary",
    "locale_it": "Tiếng Ý",
    "locale_ja": "Tiếng Nhật",
    "locale_ka": "Tiếng Gruzia",
    "locale_kk": "Tiếng Kazakh",
    "locale_ko": "Tiếng Hàn",
    "locale_lt": "Tiếng Litva",
    "locale_lv": "Tiếng Latvia",
    "locale_nl": "Tiếng Hà Lan",
    "locale_no": "Tiếng Na Uy",
    "locale_pl": "Tiếng Ba Lan",
    "locale_pt": "Tiếng Bồ Đào Nha",
    "locale_pt-BR": "Tiếng Bồ Đào Nha (Brazil)",
    "locale_ru": "Tiếng Nga",
    "locale_sk": "Tiếng Slovak",
    "locale_sl": "Tiếng Slovenia",
    "locale_sv": "Tiếng Thụy Điển",
    "locale_th": "Tiếng Thái",
    "locale_tr": "Tiếng Thổ Nhĩ Kỳ",
    "locale_uk": "Tiếng Ukraina",
    "locale_vi": "Tiếng Việt",
    "locale_zh-CN": "Tiếng Trung (Giản thể)",
    "locale_zh-TW": "Tiếng Trung (Phồn thể)",
}

src_email = parse_props("/tmp/kc-26.4.7-extracted/theme/base/email/messages/messages_en.properties")
missing = set(src_email.keys()) - set(EMAIL_VI.keys())
print("Email missing:", missing)
