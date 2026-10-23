<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=false; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <#if realm.password>
            <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" class="mission-form" novalidate>
                <#if !usernameHidden??>
                    <div class="mission-field">
                        <label for="username" class="mission-label">
                            <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                        </label>
                        <input
                            tabindex="1"
                            id="username"
                            class="mission-input <#if messagesPerField.existsError('username','password')>mission-input--error</#if>"
                            name="username"
                            value="${(login.username!'')}"
                            type="text"
                            autofocus
                            autocomplete="username"
                            aria-invalid="<#if messagesPerField.existsError('username','password')>true<#else>false</#if>"
                            <#if messagesPerField.existsError('username','password')>aria-describedby="input-error"</#if>
                        />
                    </div>
                </#if>

                <div class="mission-field">
                    <label for="password" class="mission-label">${msg("password")}</label>
                    <div class="mission-input-wrap">
                        <input
                            tabindex="2"
                            id="password"
                            class="mission-input <#if messagesPerField.existsError('username','password')>mission-input--error</#if>"
                            name="password"
                            type="password"
                            autocomplete="current-password"
                            aria-invalid="<#if messagesPerField.existsError('username','password')>true<#else>false</#if>"
                            <#if messagesPerField.existsError('username','password')>aria-describedby="input-error"</#if>
                        />
                        <button
                            type="button"
                            class="mission-pw-toggle"
                            aria-label="Hiện mật khẩu"
                            aria-pressed="false"
                            onclick="var p=document.getElementById('password'), show=p.type==='password'; p.type=show?'text':'password'; this.textContent=show?'Ẩn':'Hiện'; this.setAttribute('aria-pressed', show?'true':'false');"
                        >Hiện</button>
                    </div>
                    <#if messagesPerField.existsError('username','password')>
                        <span id="input-error" class="mission-field-error" aria-live="polite">
                            ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                        </span>
                    </#if>
                </div>

                <#if (realm.rememberMe && !usernameHidden??) || realm.resetPasswordAllowed>
                    <div class="mission-row">
                        <#if realm.rememberMe && !usernameHidden??>
                            <label class="mission-checkbox">
                                <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
                                <span>${msg("rememberMe")}</span>
                            </label>
                        </#if>
                        <#if realm.resetPasswordAllowed>
                            <a tabindex="5" href="${url.loginResetCredentialsUrl}" class="mission-link">${msg("doForgotPassword")}</a>
                        </#if>
                    </div>
                </#if>

                <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                <button tabindex="4" class="mission-btn mission-btn--primary" name="login" id="kc-login" type="submit">${msg("doLogIn")}</button>
            </form>
        </#if>

        <#if realm.password && social.providers?? && social.providers?has_content>
            <div class="mission-divider" role="separator"><span>hoặc</span></div>
            <div class="mission-idp-group">
                <#list social.providers as p>
                    <a id="social-${p.alias}" class="mission-btn mission-btn--secondary" href="${p.loginUrl}">${msg("doLogIn")} bằng ${p.displayName!}</a>
                </#list>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
