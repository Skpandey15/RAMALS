<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "social-providers.ftl" as identityProviders>
<#import "passkeys.ftl" as passkeys>
<#--
  RAMALS login page.

  Provenance: this is keycloak.v2/login/login.ftl from Keycloak 26.7.1, with one addition -- the
  "info" section below. When the pinned Keycloak digest in infrastructure/docker/keycloak/Dockerfile
  moves, re-diff this file against the new stock template and re-apply that section.

  Why the override exists: M1-ADR-015 keeps registration in RAMALS, so registrationAllowed is false
  and the stock template renders no route to an account. That leaves an unregistered learner stranded
  on this page. The block below sends them to the RAMALS-owned /register instead, which is the only
  entry point that captures consent evidence, terms/privacy versions and mobile onboarding.

  The destination is NOT hard-coded. It comes from the authenticating client's baseUrl, which each
  environment's bootstrap sets from that environment's RAMALS_WEB_ORIGIN. A client with no baseUrl
  renders no block at all rather than a broken link.
-->
<#-- ?trim before ?has_content: a client configured with a whitespace-only baseUrl would otherwise
     pass the guard below and render href=" /register", a link to nowhere. -->
<#assign ramalsHome = ((client.baseUrl)!'')?trim?remove_ending("/")>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=(realm.password && realm.registrationAllowed && !registrationDisabled??) || ramalsHome?has_content; section>
<!-- template: login.ftl -->

    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <div id="kc-form">
          <div id="kc-form-wrapper">
            <#if realm.password>
                <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <#if !usernameHidden??>
                        <#assign label>
                            <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                        </#assign>
                        <@field.input name="username" label=label error=messagesPerField.getFirstError('username','password')
                            autofocus=true autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" value=login.username!'' />
                        <@field.password name="password" label=msg("password") error="" forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??>
                                <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                            </#if>
                        </@field.password>
                    <#else>
                        <@field.password name="password" label=msg("password") forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??>
                                <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                            </#if>
                        </@field.password>
                    </#if>

                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <@buttons.loginButton />
                </form>
            </#if>
            </div>
        </div>
        <@passkeys.conditionalUIData />
    <#elseif section = "socialProviders" >
        <#if realm.password && social.providers?? && social.providers?has_content>
            <@identityProviders.show social=social/>
        </#if>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration">
                    <span>${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></span>
                </div>
            </div>
        </#if>
        <#if ramalsHome?has_content>
            <div id="kc-registration-container">
                <div id="ramals-registration">
                    <span>${msg("ramalsNoAccount")} <a id="ramals-register-link" href="${ramalsHome}/register">${msg("ramalsDoRegister")}</a></span>
                </div>
                <div id="ramals-back-home">
                    <a id="ramals-home-link" href="${ramalsHome}/">${msg("ramalsBackHome")}</a>
                </div>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
