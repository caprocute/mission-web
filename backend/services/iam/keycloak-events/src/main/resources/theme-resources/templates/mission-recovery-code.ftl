<#-- DV-02-08 · SCR-0001 "Phương thức khác": nhập mã khôi phục một lần do quản trị đơn vị cấp. -->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
  <#if section = "header">
    Nhập mã khôi phục
  <#elseif section = "form">
    <form id="kc-recovery-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
      <div class="${properties.kcFormGroupClass!}">
        <label for="recoveryCode" class="${properties.kcLabelClass!}">Mã khôi phục do quản trị đơn vị cấp</label>
        <input id="recoveryCode" name="recoveryCode" type="text" class="${properties.kcInputClass!}"
               autocomplete="one-time-code" autofocus aria-describedby="recoveryCodeHelp"/>
        <p id="recoveryCodeHelp">Mã chỉ dùng được một lần. Sau khi đăng nhập, đồng chí cài lại SmartOTP trên thiết bị mới.</p>
      </div>
      <div class="${properties.kcFormGroupClass!}">
        <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
               name="login" type="submit" value="Xác nhận"/>
      </div>
    </form>
  </#if>
</@layout.registrationLayout>
