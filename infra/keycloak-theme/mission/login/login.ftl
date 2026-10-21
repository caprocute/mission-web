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
                        <div class="mission-input-wrap">
                            <input
                                tabindex="1"
                                id="username"
                                class="mission-input <#if messagesPerField.existsError('username','password')>mission-input--error</#if>"
                                name="username"
                                value="${(login.username!'')}"
                                type="text"
                                autofocus
                                autocomplete="username"
                                placeholder="Nhập mã cán bộ (ví dụ: cb_chuyenvien)"
                                aria-invalid="<#if messagesPerField.existsError('username','password')>true<#else>false</#if>"
                            />
                        </div>
                        <#if messagesPerField.existsError('username','password')>
                            <span id="input-error" class="mission-field-error" aria-live="polite">
                                ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                            </span>
                        </#if>
                    </div>
                </#if>

                <div class="mission-field">
                    <div class="mission-label-row">
                        <label for="password" class="mission-label">${msg("password")}</label>
                        <#if realm.resetPasswordAllowed>
                            <a tabindex="5" href="${url.loginResetCredentialsUrl}" class="mission-link">${msg("doForgotPassword")}</a>
                        </#if>
                    </div>
                    <div class="mission-input-wrap mission-input-wrap--password">
                        <input
                            tabindex="2"
                            id="password"
                            class="mission-input <#if messagesPerField.existsError('username','password')>mission-input--error</#if>"
                            name="password"
                            type="password"
                            autocomplete="current-password"
                            placeholder="Nhập mật khẩu của đồng chí"
                            aria-invalid="<#if messagesPerField.existsError('username','password')>true<#else>false</#if>"
                        />
                        <button
                            type="button"
                            class="mission-pw-toggle"
                            aria-label="Hiện hoặc ẩn mật khẩu"
                            onclick="var p=document.getElementById('password'); p.type=(p.type==='password'?'text':'password'); this.textContent=(p.type==='password'?'Hiện':'Ẩn');"
                        >Hiện</button>
                    </div>
                </div>

                <#if realm.rememberMe && !usernameHidden??>
                    <div class="mission-remember">
                        <label class="mission-checkbox">
                            <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
                            <span>${msg("rememberMe")}</span>
                        </label>
                    </div>
                </#if>

                <div class="mission-actions">
                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <button tabindex="4" class="mission-btn mission-btn--primary" name="login" id="kc-login" type="submit">
                        <span>${msg("doLogIn")}</span>
                        <span aria-hidden="true">→</span>
                    </button>
                </div>
            </form>
        </#if>

        <div class="mission-divider" role="separator">
            <span>Hoặc xác thực định danh điện tử</span>
        </div>

        <div class="mission-idp-group">
            <#if realm.password && social.providers?? && social.providers?has_content>
                <#list social.providers as p>
                    <a id="social-${p.alias}" class="mission-btn mission-btn--vneid" href="${p.loginUrl}">
                        <span class="mission-vneid-badge">VNeID</span>
                        <span>Đăng nhập bằng ${p.displayName!}</span>
                    </a>
                </#list>
            <#else>
                <button
                    type="button"
                    id="social-vneid"
                    class="mission-btn mission-btn--vneid"
                    onclick="var n=document.getElementById('vneid-dev-notice'); if(n){n.hidden=false; n.focus();}"
                >
                    <span class="mission-vneid-badge">VNeID</span>
                    <span>Đăng nhập bằng VNeID (Định danh mức 2)</span>
                </button>
                <div id="vneid-dev-notice" class="mission-alert mission-alert--info" role="status" tabindex="-1" hidden>
                    <span class="mission-alert__dot" aria-hidden="true"></span>
                    <span class="mission-alert__text">
                        Cổng kết nối VNeID Quốc gia (OIDC Mức 2) được kích hoạt trên môi trường tích hợp trục liên thông. Trên môi trường phát triển, vui lòng đăng nhập bằng tài khoản cán bộ bên trên.
                    </span>
                </div>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
