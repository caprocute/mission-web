<#--
  Khung chung của mọi trang trong luồng đăng nhập Mission (QT-00, SCR-0001): một thẻ gọn giữa
  trang gồm logo, tiêu đề, thông báo và biểu mẫu của bước hiện tại. Trang riêng của theme
  (login.ftl, login-otp.ftl, info.ftl) và trang kế thừa từ base đều gọi macro này; giữ nguyên các
  section mà base dùng (header, show-username, form, socialProviders, info) và các mã phần tử
  Keycloak cần (kc-page-title, kc-content, try-another-way, reset-login).
  Không có script nội tuyến của theme: hành vi nằm ở resources/js/mission-login.js.
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
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
<main class="mission-page">
    <section class="mission-card" id="kc-content">
        <header class="mission-card__header">
            <div class="mission-brand">
                <img src="${url.resourcesPath}/img/logo-mission.svg" alt="" class="mission-brand__logo" width="44" height="44" />
                <span class="mission-brand__name">Mission</span>
            </div>
            <h1 id="kc-page-title" class="mission-card__title"><#nested "header"></h1>
        </header>

        <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
            <#nested "show-username">
            <div id="kc-username" class="mission-user">
                <span id="kc-attempted-username" class="mission-user__name">${auth.attemptedUsername}</span>
                <a id="reset-login" class="mission-link" href="${url.loginRestartFlowUrl}">${msg("missionSwitchAccount")}</a>
            </div>
        </#if>

        <#-- Việc do ứng dụng khởi tạo không cần thấy cảnh báo nhắc phải hoàn tất việc đó -->
        <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div class="mission-alert mission-alert--${message.type}" role="<#if message.type == 'error'>alert<#else>status</#if>">
                ${kcSanitize(message.summary)?no_esc}
            </div>
        </#if>

        <div class="mission-card__body">
            <#nested "form">
        </div>

        <#if auth?has_content && auth.showTryAnotherWayLink()>
            <form id="kc-select-try-another-way-form" class="mission-another" action="${url.loginAction}" method="post">
                <input type="hidden" name="tryAnotherWay" value="on"/>
                <button type="submit" id="try-another-way" class="mission-text-btn">${msg("doTryAnotherWay")}</button>
            </form>
        </#if>

        <#nested "socialProviders">

        <#if displayInfo>
            <div id="kc-info" class="mission-info">
                <#nested "info">
            </div>
        </#if>

        <footer class="mission-card__footer">
            <button type="button" class="mission-text-btn" id="mission-elderly-toggle" aria-pressed="false"
                    data-label-on="${msg("missionElderlyOn")}" data-label-off="${msg("missionElderlyOff")}">${msg("missionElderlyOn")}</button>
        </footer>
    </section>
</main>
</body>
</html>
</#macro>
