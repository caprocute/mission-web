<#-- Trang thông báo (đăng xuất xong, đã xác minh, việc cần làm...). Khác base: không nhắc lại
     câu thông báo ở thân trang khi câu đó đã làm tiêu đề. -->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <#if messageHeader??>${kcSanitize(msg("${messageHeader}"))?no_esc}<#else>${message.summary}</#if>
    <#elseif section = "form">
        <div id="kc-info-message">
            <#if messageHeader?? || requiredActions??>
                <p class="instruction">${message.summary}<#if requiredActions??><#list requiredActions>: <b><#items as reqActionItem>${kcSanitize(msg("requiredAction.${reqActionItem}"))?no_esc}<#sep>, </#items></b></#list></#if></p>
            </#if>
            <#if !skipLink??>
                <#if pageRedirectUri?has_content>
                    <a class="mission-btn mission-btn--primary mission-btn--block" href="${pageRedirectUri}">${msg("missionBackToApp")}</a>
                <#elseif actionUri?has_content>
                    <a class="mission-btn mission-btn--primary mission-btn--block" href="${actionUri}">${msg("doContinue")}</a>
                <#elseif (client.baseUrl)?has_content>
                    <a class="mission-btn mission-btn--primary mission-btn--block" href="${client.baseUrl}">${msg("missionBackToApp")}</a>
                </#if>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
