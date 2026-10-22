# ZAP by Checkmarx Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 1 |
| Low | 1 |
| Informational | 2 |




## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Cross-Domain Misconfiguration | Medium | 12 |
| Timestamp Disclosure - Unix | Low | 13 |
| Information Disclosure - Suspicious Comments | Informational | 12 |
| Modern Web Application | Informational | 9 |




## Alert Detail



### [ Cross-Domain Misconfiguration ](https://www.zaproxy.org/docs/alerts/10098/)



##### Medium (Medium)

### Description

Web browser data loading may be possible, due to a Cross Origin Resource Sharing (CORS) misconfiguration on the web server.

* URL: http://localhost:4330/chunk-2NFLSA4Y.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/chunk-5XSVH53F.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/chunk-BSFPCFB7.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/chunk-V6MZVBQ3.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/evidence.mock-CP3VGQGD.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/favicon.ico
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/federation.manifest.json
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/ledger.mock-YKAD7L2Y.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/main-LVCRC4BI.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/polyfills-44CKUDXI.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/remoteEntry.json
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`
* URL: http://localhost:4330/styles-3EI6GN2K.css
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `Access-Control-Allow-Origin: *`
  * Other Info: `The CORS misconfiguration on the web server permits cross-domain read requests from arbitrary third party domains, using unauthenticated APIs on this domain. Web browser implementations do not permit arbitrary third parties to read the response from authenticated APIs, however. This reduces the risk somewhat. This misconfiguration could be used by an attacker to access data that is available in an unauthenticated manner, but which uses some other form of security, such as IP address white-listing.`

Instances: 12

### Solution

Ensure that sensitive data is not available in an unauthenticated manner (using IP address white-listing, for instance).
Configure the "Access-Control-Allow-Origin" HTTP header to a more restrictive set of domains, or remove all CORS headers entirely, to allow the web browser to enforce the Same Origin Policy (SOP) in a more restrictive manner.

### Reference


* [ https://vulncat.fortify.com/en/detail?id=desc.config.dotnet.html5_overly_permissive_cors_policy ](https://vulncat.fortify.com/en/detail?id=desc.config.dotnet.html5_overly_permissive_cors_policy)


#### CWE Id: [ 264 ](https://cwe.mitre.org/data/definitions/264.html)


#### WASC Id: 14

#### Source ID: 3

### [ Timestamp Disclosure - Unix ](https://www.zaproxy.org/docs/alerts/10096/)



##### Low (Low)

### Description

A timestamp was disclosed by the application/web server. - Unix

* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1508970993`
  * Other Info: `1508970993, which evaluates to: 2017-10-25 22:36:33.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1537002063`
  * Other Info: `1537002063, which evaluates to: 2018-09-15 09:01:03.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1541459225`
  * Other Info: `1541459225, which evaluates to: 2018-11-05 23:07:05.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1555081692`
  * Other Info: `1555081692, which evaluates to: 2019-04-12 15:08:12.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1695183700`
  * Other Info: `1695183700, which evaluates to: 2023-09-20 04:21:40.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1747873779`
  * Other Info: `1747873779, which evaluates to: 2025-05-22 00:29:39.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1779033703`
  * Other Info: `1779033703, which evaluates to: 2026-05-17 16:01:43.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1899447441`
  * Other Info: `1899447441, which evaluates to: 2030-03-11 08:17:21.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1925078388`
  * Other Info: `1925078388, which evaluates to: 2031-01-01 23:59:48.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1955562222`
  * Other Info: `1955562222, which evaluates to: 2031-12-20 19:43:42.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1986661051`
  * Other Info: `1986661051, which evaluates to: 2032-12-14 18:17:31.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `1996064986`
  * Other Info: `1996064986, which evaluates to: 2033-04-02 14:29:46.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `2024104815`
  * Other Info: `2024104815, which evaluates to: 2034-02-21 03:20:15.`

Instances: 13

### Solution

Manually confirm that the timestamp data is not sensitive, and that the data cannot be aggregated to disclose exploitable patterns.

### Reference


* [ https://cwe.mitre.org/data/definitions/200.html ](https://cwe.mitre.org/data/definitions/200.html)


#### CWE Id: [ 497 ](https://cwe.mitre.org/data/definitions/497.html)


#### WASC Id: 13

#### Source ID: 3

### [ Information Disclosure - Suspicious Comments ](https://www.zaproxy.org/docs/alerts/10027/)



##### Informational (Low)

### Description

The response appears to contain suspicious comments which may help an attacker.

* URL: http://localhost:4330/_angular_common.3soJ_2cMOM.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `XXX`
  * Other Info: `The following pattern was used: \bXXX\b and was detected in likely comment: "//").test(e)){let[,t]=e.split(/\/\/[^\/]+/);return t}return e}import*as a from"@angular/core";import{\u0275getLocalePluralCase a", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/_angular_core.eDEiq481qT.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `from`
  * Other Info: `The following pattern was used: \bFROM\b and was detected in likely comment: "//www.w3.org/2000/svg":mm,"http://www.w3.org/1998/Math/MathML":ym},Ti;function Em(){if(Ti===void 0&&(Ti=null,De.trustedTypes))tr", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/_angular_router.8yS8Q6ZKmE.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `from`
  * Other Info: `The following pattern was used: \bFROM\b and was detected in likely comment: "//")})`:e}else{let e=Ri(t,(r,i)=>i===p?[De(t.children[p],!1)]:[`${i}:${De(r,!1)}`]);return Object.keys(t.children).length===1&&t", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/_vt_auth-A6PIGCDJ.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `user`
  * Other Info: `The following pattern was used: \bUSER\b and was detected in likely comment: "//")&&!e.startsWith("/\\"),o=new URL(t?e:location.pathname+location.search,location.origin);t||o.searchParams.delete("iss");let ", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/angular_oauth2_oidc.Ki4nOj0Znt.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `User`
  * Other Info: `The following pattern was used: \bUSER\b and was detected in likely comment: "//g,"_").replace(/=/g,"")}var A=class{constructor(a){this.clientId="",this.redirectUri="",this.postLogoutRedirectUri="",this.red", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/cel_js.p4wd9Ttw-j.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `from`
  * Other Info: `The following pattern was used: \bFROM\b and was detected in likely comment: "//github.com/chevrotain/chevrotain/issues`+n)}}return[]}function ai(t,e,r){switch(t.type){case"Disjunction":for(let o=0;o<t.valu", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/chunk-OH6D3JUX.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `from`
  * Other Info: `The following pattern was used: \bFROM\b and was detected in likely comment: "//datatracker.ietf.org/doc/html/rfc9470",code:"insufficient_user_authentication"});return e.headers=I(A({},e.headers),{"WWW-Auth", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/faro-init-M5Q4CMNM.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `query`
  * Other Info: `The following pattern was used: \bQUERY\b and was detected in likely comment: "//i],[_,[p,"GSA"]],[/musical_ly(?:.+app_?version\/|_)([\w\.]+)/i],[_,[p,"TikTok"]],[/headlesschrome(?:\/([\w\.]+)| )/i],[_,[p,Le", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/policies.mock-KE3KGEF6.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `user`
  * Other Info: `The following pattern was used: \bUSER\b and was detected in likely comment: "//datatracker.ietf.org/doc/html/rfc9470",code:"insufficient_user_authentication"});return s.headers=v(h({},s.headers),{"WWW-Auth", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/polyfills-44CKUDXI.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `from`
  * Other Info: `The following pattern was used: \bFROM\b and was detected in likely comment: "//${location.host}${location.pathname.includes("/")?location.pathname.slice(0,location.pathname.lastIndexOf("/")+1):location.pat", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/silent-refresh.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `query`
  * Other Info: `The following pattern was used: \bQUERY\b and was detected in likely comment: "// Gửi mã ủy quyền (query của luồng code) về cửa sổ chính cùng nguồn; tệp riêng để hợp CSP (WEB-014).", see evidence field for the suspicious comment/snippet.`
* URL: http://localhost:4330/ui-packs.mock-5ZS3OMGE.js
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `select`
  * Other Info: `The following pattern was used: \bSELECT\b and was detected in likely comment: "//json-schema.org/draft/2020-12/schema",type:"object",required:["khoa_phong","so_ca_truc","ty_le_hsba_dat"],properties:{khoa_pho", see evidence field for the suspicious comment/snippet.`

Instances: 12

### Solution

Remove all comments that return information that may help an attacker and fix any underlying problems they refer to.

### Reference



#### CWE Id: [ 615 ](https://cwe.mitre.org/data/definitions/615.html)


#### WASC Id: 13

#### Source ID: 3

### [ Modern Web Application ](https://www.zaproxy.org/docs/alerts/10109/)



##### Informational (Medium)

### Description

The application appears to be a modern web application. If you need to explore it automatically then the Ajax Spider may well be more effective than the standard one.

* URL: http://localhost:4330/index.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script nonce="0badca15adf0a0f19856c3b13aab88bb">
(() => {
  const CSP_MEDIA_ATTR = 'ngCspMedia';
  const documentElement = document.documentElement;

  // Listener for load events on link tags.
  const listener = (e) => {
    const target = e.target;
    if (
      !target ||
      target.tagName !== 'LINK' ||
      !target.hasAttribute(CSP_MEDIA_ATTR)
    ) {
      return;
    }

    target.media = target.getAttribute(CSP_MEDIA_ATTR);
    target.removeAttribute(CSP_MEDIA_ATTR);

    if (!document.head.querySelector(`link[${CSP_MEDIA_ATTR}]`)) {
      documentElement.removeEventListener('load', listener);
    }
  };

  documentElement.addEventListener('load', listener, true);
})();</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/remotes/fe-admin/index.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script type="esms-options">{"shimMode":true}</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/remotes/fe-appraisal/index.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script type="esms-options">{"shimMode":true}</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/remotes/fe-config-studio/index.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script type="esms-options">{"shimMode":true}</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/remotes/fe-dashboard/index.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script type="esms-options">{"shimMode":true}</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/remotes/fe-dossier/index.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script type="esms-options">{"shimMode":true}</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/remotes/fe-okr/index.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script type="esms-options">{"shimMode":true}</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/silent-refresh.html
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script src="silent-refresh.js"></script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`
* URL: http://localhost:4330/sitemap.xml
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `<script nonce="4eb4cadc68f898327dc94a28c2cbe9ac">
(() => {
  const CSP_MEDIA_ATTR = 'ngCspMedia';
  const documentElement = document.documentElement;

  // Listener for load events on link tags.
  const listener = (e) => {
    const target = e.target;
    if (
      !target ||
      target.tagName !== 'LINK' ||
      !target.hasAttribute(CSP_MEDIA_ATTR)
    ) {
      return;
    }

    target.media = target.getAttribute(CSP_MEDIA_ATTR);
    target.removeAttribute(CSP_MEDIA_ATTR);

    if (!document.head.querySelector(`link[${CSP_MEDIA_ATTR}]`)) {
      documentElement.removeEventListener('load', listener);
    }
  };

  documentElement.addEventListener('load', listener, true);
})();</script>`
  * Other Info: `No links have been found while there are scripts, which is an indication that this is a modern web application.`

Instances: 9

### Solution

This is an informational alert and so no changes are required.

### Reference




#### Source ID: 3


