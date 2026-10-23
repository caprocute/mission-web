<#--
  Khung chung của mọi trang trong luồng đăng nhập Mission (QT-00, SCR-0001, TL-02 hình 6.3):
  vùng trái giới thiệu nền tảng, vùng phải là thẻ của bước hiện tại. Trang riêng của theme
  (login.ftl, login-otp.ftl) và trang kế thừa từ base đều gọi macro này; giữ nguyên các
  section mà base dùng (header, show-username, form, socialProviders, info) và các mã
  phần tử Keycloak cần (kc-page-title, kc-content, try-another-way, reset-login).
  Không có script nội tuyến của theme: hành vi nằm ở resources/js/mission-login.js.
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false step=0>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang!'vi'}">
<head>
    <meta charset="utf-8">
    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/logo-mission.svg" />
    <#list (properties.styles!'')?split(' ') as style>
        <#if style?has_content><link href="${url.resourcesPath}/${style}" rel="stylesheet" /></#if>
    </#list>
    <#list (properties.scripts!'')?split(' ') as script>
        <#if script?has_content><script src="${url.resourcesPath}/${script}"></script></#if>
    </#list>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="importmap">
        { "imports": { "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js" } }
    </script>
    <script type="module">
        import { startSessionPolling } from "${url.resourcesPath}/js/authChecker.js";
        startSessionPolling("${url.ssoLoginInOtherTabsUrl?no_esc}");
    </script>
    <#if authenticationSession??>
        <script type="module">
            import { checkAuthSession } from "${url.resourcesPath}/js/authChecker.js";
            checkAuthSession("${authenticationSession.authSessionIdHash}");
        </script>
    </#if>
</head>

<body class="${properties.kcBodyClass!} ${bodyClass}" data-scr="SCR-0001" data-page-id="login-${pageId!''}">
<a class="mission-skip" href="#kc-page-title">${msg("missionSkipLink")}</a>
<div class="mission-shell">
    <aside class="mission-hero" aria-label="${msg("missionHeroAria")}">
        <div class="mission-hero__inner">
            <div class="mission-brand">
                <img src="${url.resourcesPath}/img/logo-mission.svg" alt="" width="48" height="48" />
                <div>
                    <p class="mission-brand__name">Mission</p>
                    <p class="mission-brand__tagline">${msg("missionProductName")}</p>
                </div>
            </div>
            <h2 class="mission-hero__title">${msg("missionHeroTitle")}</h2>
            <p class="mission-hero__lead">${msg("missionHeroLead")}</p>
            <ul class="mission-hero__points">
                <li>${msg("missionHeroPoint1")}</li>
                <li>${msg("missionHeroPoint2")}</li>
                <li>${msg("missionHeroPoint3")}</li>
            </ul>
            <p class="mission-hero__footer">${msg("missionHeroFooter")}</p>
        </div>
    </aside>

    <main class="mission-main" id="kc-main">
        <div class="mission-topbar">
            <div class="mission-context">
                <p class="mission-context__title">${realm.displayName!msg("missionProductName")}</p>
                <#-- địa chỉ lấy từ thanh địa chỉ của trình duyệt (mission-login.js) để người dùng nhận ra trang giả mạo -->
                <p class="mission-context__domain" id="mission-access-domain" hidden>
                    ${msg("missionAccessDomainLabel")} <strong data-access-host></strong>. ${msg("missionAccessDomainHint")}
                </p>
            </div>
            <div class="mission-tools">
                <#if realm.internationalizationEnabled && locale?? && locale.supported?size gt 1>
                    <nav class="mission-lang" aria-label="${msg("missionLanguage")}">
                        <#list locale.supported as l>
                            <a href="${l.url}" class="mission-lang__item" lang="${l.languageTag}"<#if l.languageTag == locale.currentLanguageTag> aria-current="true"</#if>>${l.label}</a>
                        </#list>
                    </nav>
                </#if>
                <button type="button" class="mission-tool-btn" id="mission-elderly-toggle" aria-pressed="false"
                        aria-label="${msg("missionElderlyAria")}"
                        data-label-on="${msg("missionElderlyOn")}" data-label-off="${msg("missionElderlyOff")}">${msg("missionElderlyOn")}</button>
            </div>
        </div>

        <section class="mission-card" id="kc-content">
            <div class="mission-card__brand" aria-hidden="true">
                <img src="${url.resourcesPath}/img/logo-mission.svg" alt="" width="36" height="36" />
                <span>Mission</span>
            </div>

            <#if step == 1>
                <p class="mission-step"><span class="mission-step__num" aria-hidden="true">1</span>${msg("missionStepIdentity")}</p>
            <#elseif step == 2>
                <p class="mission-step"><span class="mission-step__num" aria-hidden="true">2</span>${msg("missionStepMfa")}</p>
            </#if>

            <h1 id="kc-page-title" class="mission-card__title" tabindex="-1"><#nested "header"></h1>

            <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
                <#nested "show-username">
                <div id="kc-username" class="mission-user">
                    <span class="mission-user__label">${msg("missionSignedInAs")}</span>
                    <span id="kc-attempted-username" class="mission-user__name">${auth.attemptedUsername}</span>
                    <a id="reset-login" class="mission-link" href="${url.loginRestartFlowUrl}">${msg("missionSwitchAccount")}</a>
                </div>
            </#if>

            <#if displayRequiredFields>
                <p class="mission-required-note"><span class="mission-required" aria-hidden="true">*</span> ${msg("requiredFields")}</p>
            </#if>

            <#-- Việc do ứng dụng khởi tạo không cần thấy cảnh báo nhắc phải hoàn tất việc đó -->
            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <div class="mission-alert mission-alert--${message.type}" role="<#if message.type == 'error'>alert<#else>status</#if>">
                    <span class="mission-alert__dot" aria-hidden="true"></span>
                    <span class="mission-alert__text">${kcSanitize(message.summary)?no_esc}</span>
                </div>
            </#if>

            <div class="mission-card__body">
                <#nested "form">
            </div>

            <#if auth?has_content && auth.showTryAnotherWayLink()>
                <form id="kc-select-try-another-way-form" class="mission-another" action="${url.loginAction}" method="post">
                    <input type="hidden" name="tryAnotherWay" value="on"/>
                    <button type="submit" id="try-another-way" class="mission-link-btn">${msg("doTryAnotherWay")}</button>
                </form>
            </#if>

            <#nested "socialProviders">

            <#if displayInfo>
                <div id="kc-info" class="mission-info">
                    <div id="kc-info-wrapper" class="mission-info__inner">
                        <#nested "info">
                    </div>
                </div>
            </#if>

            <p class="mission-card__support">${msg("missionSupport")}</p>
        </section>
    </main>
</div>
</body>
</html>
</#macro>
