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
    <div class="mission-shell">
        <!-- Cột trái: Nhận diện thương hiệu & Điểm nhấn nền tảng OKR/KPI -->
        <aside class="mission-hero" aria-label="Giới thiệu hệ thống Mission">
            <div class="mission-hero__backdrop"></div>
            <div class="mission-hero__content">
                <div class="mission-brand">
                    <img src="${url.resourcesPath}/img/logo-mission.svg" alt="" class="mission-brand__logo" width="52" height="52" />
                    <div class="mission-brand__text">
                        <span class="mission-brand__badge">NỀN TẢNG SỐ QUỐC GIA · QĐ 366-QĐ/TW</span>
                        <h1 class="mission-brand__title">MISSION OKR / KPI</h1>
                    </div>
                </div>

                <div class="mission-hero__headline">
                    <h2>Điều hành nhiệm vụ thông minh, đánh giá cán bộ minh bạch &amp; thực chất</h2>
                    <p>
                        Hệ thống quản trị mục tiêu (OKR), chỉ số hiệu suất (KPI) và giao việc hợp nhất theo
                        Quyết định 366-QĐ/TW, Nghị định 335/2025 và Nghị định 233/2026.
                    </p>
                </div>

                <div class="mission-pillars" role="list">
                    <div class="mission-pillar" role="listitem">
                        <div class="mission-pillar__icon" aria-hidden="true">01</div>
                        <div class="mission-pillar__body">
                            <strong>Khung nền tảng cấu hình động</strong>
                            <span>Luồng quy trình BPMN 2.0, gói chính sách điểm và biểu mẫu động theo từng loại hình cơ quan, đơn vị.</span>
                        </div>
                    </div>
                    <div class="mission-pillar" role="listitem">
                        <div class="mission-pillar__icon" aria-hidden="true">02</div>
                        <div class="mission-pillar__body">
                            <strong>Liên thông Giao việc → OKR → KPI tự động</strong>
                            <span>Tự động kế thừa kết quả nghiệm thu nhiệm vụ hằng ngày vào chỉ số KPI và đánh giá cán bộ cuối kỳ.</span>
                        </div>
                    </div>
                    <div class="mission-pillar" role="listitem">
                        <div class="mission-pillar__icon" aria-hidden="true">03</div>
                        <div class="mission-pillar__body">
                            <strong>An toàn thông tin &amp; Ký số chuyên dùng</strong>
                            <span>Định danh tập trung SSO, xác thực VNeID mức 2, phân quyền đa đơn vị và ký số Ban Cơ yếu.</span>
                        </div>
                    </div>
                </div>

                <footer class="mission-hero__footer">
                    <div class="mission-hero__stats">
                        <div>
                            <strong>≤ 3 chạm</strong>
                            <span>Thao tác thường ngày</span>
                        </div>
                        <div>
                            <strong>100%</strong>
                            <span>Truy vết minh chứng</span>
                        </div>
                        <div>
                            <strong>WCAG AA</strong>
                            <span>Hỗ trợ chế độ chữ lớn</span>
                        </div>
                    </div>
                </footer>
            </div>
        </aside>

        <!-- Cột phải: Khung đăng nhập / xác thực -->
        <main class="mission-panel" id="kc-content">
            <div class="mission-panel__top">
                <span class="mission-env-pill">Cổng xác thực tập trung (SSO)</span>
                <button type="button" class="mission-elderly-toggle" id="btn-elderly-mode" onclick="toggleElderlyMode()" aria-pressed="false">
                    Chế độ chữ lớn
                </button>
            </div>

            <div class="mission-card" id="kc-content-wrapper">
                <header class="mission-card__header">
                    <div class="mission-card__mobile-brand">
                        <img src="${url.resourcesPath}/img/logo-mission.svg" alt="" width="40" height="40" />
                        <span>MISSION OKR/KPI</span>
                    </div>
                    <h2 class="mission-card__title"><#nested "header"></h2>
                    <p class="mission-card__subtitle">
                        Sử dụng tài khoản cán bộ, công chức, viên chức hoặc định danh điện tử để tiếp tục.
                    </p>
                </header>

                <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                    <div class="mission-alert mission-alert--${message.type}" role="alert" aria-live="polite">
                        <span class="mission-alert__dot" aria-hidden="true"></span>
                        <span class="mission-alert__text">${kcSanitize(message.summary)?no_esc}</span>
                    </div>
                </#if>

                <div class="mission-card__form">
                    <#nested "form">
                </div>

                <#if displayInfo>
                    <div class="mission-card__info">
                        <#nested "info">
                    </div>
                </#if>

                <footer class="mission-card__footer">
                    <span>Bảo mật theo tiêu chuẩn ATTT Cấp độ 3 · Hỗ trợ kỹ thuật 24/7</span>
                </footer>
            </div>
        </main>
    </div>

    <script>
        function toggleElderlyMode() {
            var html = document.documentElement;
            var btn = document.getElementById('btn-elderly-mode');
            var isElderly = html.getAttribute('data-mode') === 'elderly';
            if (isElderly) {
                html.removeAttribute('data-mode');
                localStorage.setItem('mission_elderly_mode', '0');
                if (btn) {
                    btn.setAttribute('aria-pressed', 'false');
                    btn.textContent = 'Chế độ chữ lớn';
                }
            } else {
                html.setAttribute('data-mode', 'elderly');
                localStorage.setItem('mission_elderly_mode', '1');
                if (btn) {
                    btn.setAttribute('aria-pressed', 'true');
                    btn.textContent = 'Chế độ chữ thường';
                }
            }
        }
        (function initElderlyMode() {
            try {
                if (localStorage.getItem('mission_elderly_mode') === '1') {
                    document.documentElement.setAttribute('data-mode', 'elderly');
                    var btn = document.getElementById('btn-elderly-mode');
                    if (btn) {
                        btn.setAttribute('aria-pressed', 'true');
                        btn.textContent = 'Chế độ chữ thường';
                    }
                }
            } catch (e) {}
        })();
    </script>
</body>
</html>
</#macro>
