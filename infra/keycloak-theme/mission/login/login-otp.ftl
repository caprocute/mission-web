<#-- SCR-0001: xác thực nhiều lớp (AAL2, BR-00-01/BR-00-02, MSG-00-05). Giữ mã phần tử otp,
     kc-login vì kiểm thử e2e và cửa sổ bước lên của web dùng. -->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp'); section>
    <#if section = "header">
        ${msg("loginOtpTitle")}
    <#elseif section = "form">
        <p class="mission-lead">${msg("missionOtpHint")}</p>
        <form id="kc-otp-login-form" class="mission-form" action="${url.loginAction}" method="post" data-once-submit>
            <#if otpLogin.userOtpCredentials?size gt 1>
                <fieldset class="mission-field mission-fieldset">
                    <legend class="mission-label">${msg("missionOtpDevice")}</legend>
                    <div class="mission-tiles">
                        <#list otpLogin.userOtpCredentials as otpCredential>
                            <label class="mission-tile" for="kc-otp-credential-${otpCredential?index}">
                                <input id="kc-otp-credential-${otpCredential?index}" class="mission-tile__input" type="radio" name="selectedCredentialId"
                                       value="${otpCredential.id}" <#if otpCredential.id == otpLogin.selectedCredentialId>checked="checked"</#if>>
                                <span class="mission-tile__title">${otpCredential.userLabel}</span>
                            </label>
                        </#list>
                    </div>
                </fieldset>
            </#if>

            <#assign otpError = messagesPerField.existsError('totp')>
            <div class="mission-field">
                <label for="otp" class="mission-label">${msg("loginOtpOneTime")}</label>
                <input id="otp" name="otp" type="text" class="mission-input mission-input--otp" autocomplete="one-time-code"
                       inputmode="numeric" pattern="[0-9]*" maxlength="6" autofocus required dir="ltr"
                       aria-invalid="${otpError?then('true','false')}"<#if otpError> aria-describedby="input-error-otp-code"</#if> />
                <#if otpError>
                    <span id="input-error-otp-code" class="mission-field-error" aria-live="polite">${kcSanitize(messagesPerField.get('totp'))?no_esc}</span>
                </#if>
            </div>

            <button class="mission-btn mission-btn--primary mission-btn--block" name="login" id="kc-login" type="submit">${msg("doSubmit")}</button>
        </form>
    </#if>
</@layout.registrationLayout>
