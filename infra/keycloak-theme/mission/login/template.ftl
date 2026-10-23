<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html lang="${(locale.currentLanguageTag)!'vi'}" class="mission-auth-html">
<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="robots" content="noindex, nofollow">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/logo-mission.svg" />
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
</head>
<body class="mission-auth-body ${bodyClass}" data-scr="SCR-0001">
    <main class="mission-page" id="kc-content">
        <div class="mission-card" id="kc-content-wrapper">
            <header class="mission-card__header">
                <div class="mission-brand">
                    <img src="${url.resourcesPath}/img/logo-mission.svg" alt="" class="mission-brand__logo" width="44" height="44" />
                    <span class="mission-brand__name">Mission</span>
                </div>
                <h1 class="mission-card__title"><#nested "header"></h1>
            </header>

            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <div class="mission-alert mission-alert--${message.type}" role="alert" aria-live="polite">
                    ${kcSanitize(message.summary)?no_esc}
                </div>
            </#if>

            <#nested "form">

            <#if displayInfo>
                <div class="mission-card__info">
                    <#nested "info">
                </div>
            </#if>

            <footer class="mission-card__footer">
                <button type="button" class="mission-text-btn" id="btn-elderly-mode" onclick="toggleElderlyMode()" aria-pressed="false">
                    Chữ lớn
                </button>
            </footer>
        </div>
    </main>

    <script>
        // Chế độ "Đảng viên cao tuổi" (NFR-11): data-mode="elderly", nhớ theo thiết bị
        function setElderlyMode(on) {
            var btn = document.getElementById('btn-elderly-mode');
            if (on) document.documentElement.setAttribute('data-mode', 'elderly');
            else document.documentElement.removeAttribute('data-mode');
            if (btn) {
                btn.setAttribute('aria-pressed', on ? 'true' : 'false');
                btn.textContent = on ? 'Chữ thường' : 'Chữ lớn';
            }
        }
        function toggleElderlyMode() {
            var on = document.documentElement.getAttribute('data-mode') !== 'elderly';
            setElderlyMode(on);
            try { localStorage.setItem('mission_elderly_mode', on ? '1' : '0'); } catch (e) {}
        }
        try { if (localStorage.getItem('mission_elderly_mode') === '1') setElderlyMode(true); } catch (e) {}
    </script>
</body>
</html>
</#macro>
