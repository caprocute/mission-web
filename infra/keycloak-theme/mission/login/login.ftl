<#-- SCR-0001: đăng nhập bằng tài khoản cơ quan; nút nguồn định danh (VNeID,
     chứng thư số) chỉ hiện khi realm/tenant bật nguồn đó (TL-02 QT-00 bước 3).
     Giữ mã phần tử username, password, kc-login vì kiểm thử e2e dùng (frontend/e2e/dang-nhap-keycloak.ts). -->
<#import "template.ftl" as layout>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <#if realm.password>
            <form id="kc-form-login" class="mission-form" action="${url.loginAction}" method="post" data-once-submit>
                <#assign hasError = messagesPerField.existsError('username','password')>
                <#if !usernameHidden??>
                    <div class="mission-field">
                        <label for="username" class="mission-label">
                            <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                        </label>
                        <input id="username" name="username" type="text" class="mission-input" value="${(login.username!'')}"
                               autofocus autocomplete="username" required maxlength="254" dir="ltr"
                               aria-invalid="${hasError?then('true','false')}"<#if hasError> aria-describedby="input-error"</#if> />
                        <#if hasError>
                            <span id="input-error" class="mission-field-error" aria-live="polite">${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}</span>
                        </#if>
                    </div>
                </#if>

                <div class="mission-field">
                    <label for="password" class="mission-label">${msg("password")}</label>
                    <div class="mission-input-group" dir="ltr">
                        <input id="password" name="password" type="password" class="mission-input" autocomplete="current-password" required
                               <#if usernameHidden??>autofocus</#if> aria-invalid="${hasError?then('true','false')}"
                               <#if usernameHidden?? && hasError>aria-describedby="input-error"</#if> />
                        <button class="mission-pw-toggle" type="button" aria-label="${msg('showPassword')}" aria-controls="password" data-password-toggle
                                data-icon-show="mission-icon-eye" data-icon-hide="mission-icon-eye-off"
                                data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}"><i class="mission-icon-eye" aria-hidden="true"></i></button>
                    </div>
                    <#if usernameHidden?? && hasError>
                        <span id="input-error" class="mission-field-error" aria-live="polite">${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}</span>
                    </#if>
                </div>

                <#if (realm.rememberMe && !usernameHidden??) || realm.resetPasswordAllowed>
                    <div class="mission-options">
                        <#if realm.rememberMe && !usernameHidden??>
                            <label class="mission-choice">
                                <input class="mission-choice__input" id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
                                <span class="mission-choice__label">${msg("rememberMe")}</span>
                            </label>
                        <#else>
                            <span></span>
                        </#if>
                        <#if realm.resetPasswordAllowed>
                            <a href="${url.loginResetCredentialsUrl}" class="mission-link">${msg("doForgotPassword")}</a>
                        </#if>
                    </div>
                </#if>

                <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                <button class="mission-btn mission-btn--primary mission-btn--block" name="login" id="kc-login" type="submit">${msg("doLogIn")}</button>
            </form>
        </#if>
        <@passkeys.conditionalUIData />
        <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <p>${msg("noAccount")} <a class="mission-link" href="${url.registrationUrl}">${msg("doRegister")}</a></p>
        </#if>
    <#elseif section = "socialProviders">
        <#if social?? && social.providers?has_content>
            <div id="kc-social-providers" class="mission-idp">
                <#if realm.password><p class="mission-divider"><span>${msg("missionOr")}</span></p></#if>
                <ul class="mission-idp-list">
                    <#list social.providers as p>
                        <li>
                            <a data-once-link data-disabled-class="mission-btn--disabled" id="social-${p.alias}"
                               class="mission-btn mission-btn--outline mission-btn--block" href="${p.loginUrl}">${msg("missionIdpLogin", p.displayName!p.alias)}</a>
                        </li>
                    </#list>
                </ul>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
