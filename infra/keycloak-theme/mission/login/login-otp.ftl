<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp') displayInfo=false; section>
    <#if section = "header">
        ${msg("loginOtpTitle")}
    <#elseif section = "form">
        <form id="kc-otp-login-form" class="mission-form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
            <p class="mission-hint">Nhập mã 6 chữ số trong ứng dụng xác thực.</p>

            <#if otpLogin.userOtpCredentials?size gt 1>
                <div class="mission-field">
                    <label for="kc-otp-credential" class="mission-label">Thiết bị xác thực</label>
                    <select id="kc-otp-credential" name="selectedCredentialId" class="mission-input">
                        <#list otpLogin.userOtpCredentials as otpCredential>
                            <option value="${otpCredential.id}" <#if otpCredential.id == otpLogin.selectedCredentialId>selected="selected"</#if>>
                                ${otpCredential.userLabel}
                            </option>
                        </#list>
                    </select>
                </div>
            </#if>

            <div class="mission-field">
                <label for="otp" class="mission-label">${msg("loginTotpOneTime")}</label>
                <input
                    id="otp"
                    name="otp"
                    autocomplete="one-time-code"
                    type="text"
                    inputmode="numeric"
                    pattern="[0-9]*"
                    maxlength="6"
                    class="mission-input mission-input--otp <#if messagesPerField.existsError('totp')>mission-input--error</#if>"
                    autofocus
                    placeholder="• • • • • •"
                    aria-invalid="<#if messagesPerField.existsError('totp')>true<#else>false</#if>"
                />
                <#if messagesPerField.existsError('totp')>
                    <span id="input-error-otp-code" class="mission-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('totp'))?no_esc}
                    </span>
                </#if>
            </div>

            <button class="mission-btn mission-btn--primary" name="login" id="kc-login" type="submit">${msg("doSubmit")}</button>
        </form>
    </#if>
</@layout.registrationLayout>
