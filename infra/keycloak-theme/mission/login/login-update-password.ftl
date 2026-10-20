<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm') displayInfo=false; section>
    <#if section = "header">
        ${msg("updatePasswordTitle")}
    <#elseif section = "form">
        <form id="kc-passwd-update-form" class="mission-form" action="${url.loginAction}" method="post">
            <p class="mission-otp-hint">
                Để đảm bảo an toàn thông tin theo quy định, vui lòng thiết lập mật khẩu mới trước khi truy cập hệ thống.
            </p>

            <div class="mission-field">
                <label for="password-new" class="mission-label">${msg("passwordNew")}</label>
                <input
                    type="password"
                    id="password-new"
                    name="password-new"
                    class="mission-input <#if messagesPerField.existsError('password','password-confirm')>mission-input--error</#if>"
                    autofocus
                    autocomplete="new-password"
                    placeholder="Tối thiểu 8 ký tự, gồm chữ hoa, chữ thường và số"
                    aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true<#else>false</#if>"
                />
                <#if messagesPerField.existsError('password')>
                    <span id="input-error-password" class="mission-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('password'))?no_esc}
                    </span>
                </#if>
            </div>

            <div class="mission-field">
                <label for="password-confirm" class="mission-label">${msg("passwordConfirm")}</label>
                <input
                    type="password"
                    id="password-confirm"
                    name="password-confirm"
                    class="mission-input <#if messagesPerField.existsError('password-confirm')>mission-input--error</#if>"
                    autocomplete="new-password"
                    placeholder="Nhập lại mật khẩu mới"
                    aria-invalid="<#if messagesPerField.existsError('password-confirm')>true<#else>false</#if>"
                />
                <#if messagesPerField.existsError('password-confirm')>
                    <span id="input-error-password-confirm" class="mission-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
                    </span>
                </#if>
            </div>

            <div class="mission-actions">
                <button class="mission-btn mission-btn--primary" type="submit">
                    <span>Cập nhật mật khẩu &amp; Tiếp tục</span>
                    <span aria-hidden="true">→</span>
                </button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
