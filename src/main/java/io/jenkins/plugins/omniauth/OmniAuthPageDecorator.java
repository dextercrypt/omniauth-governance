package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.AuthorizationStrategy;
import hudson.util.PluginServletFilter;
import jenkins.model.Jenkins;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;

@Extension
public class OmniAuthPageDecorator {

    // ── SVG icon constants (currentColor, used in injected scripts and action page) ────────────
    // All use double-quoted attributes — safe to embed in JS single-quoted strings.

    private static final String SVG_BOLT =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\" fill=\"currentColor\""
        + " width=\"16\" height=\"16\" style=\"vertical-align:middle;flex-shrink:0;\">"
        + "<path fill-rule=\"evenodd\" d=\"M11.3 1.046A1 1 0 0112 2v5h4a1 1 0 01.82 1.573l-7 10"
        + "A1 1 0 018 18v-5H4a1 1 0 01-.82-1.573l7-10a1 1 0 011.12-.38z\" clip-rule=\"evenodd\"/></svg>";

    private static final String SVG_CHECK =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\" fill=\"currentColor\""
        + " width=\"16\" height=\"16\" style=\"vertical-align:middle;flex-shrink:0;\">"
        + "<path fill-rule=\"evenodd\" d=\"M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0"
        + "l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z\" clip-rule=\"evenodd\"/></svg>";

    private static final String SVG_CLOCK =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\" fill=\"currentColor\""
        + " width=\"16\" height=\"16\" style=\"vertical-align:middle;flex-shrink:0;\">"
        + "<path fill-rule=\"evenodd\" d=\"M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4"
        + "a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z\" clip-rule=\"evenodd\"/></svg>";

    // Smaller variants for table cells
    private static final String SVG_CHECK_CIRCLE_SM =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\" fill=\"currentColor\""
        + " width=\"14\" height=\"14\" style=\"vertical-align:middle;flex-shrink:0;\">"
        + "<path fill-rule=\"evenodd\" d=\"M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0"
        + " 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z\""
        + " clip-rule=\"evenodd\"/></svg>";

    private static final String SVG_X_CIRCLE_SM =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\" fill=\"currentColor\""
        + " width=\"14\" height=\"14\" style=\"vertical-align:middle;flex-shrink:0;\">"
        + "<path fill-rule=\"evenodd\" d=\"M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0"
        + " 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0"
        + " 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z\""
        + " clip-rule=\"evenodd\"/></svg>";

    private static final String SVG_CLOCK_SM =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\" fill=\"currentColor\""
        + " width=\"14\" height=\"14\" style=\"vertical-align:middle;flex-shrink:0;\">"
        + "<path fill-rule=\"evenodd\" d=\"M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4"
        + "a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z\" clip-rule=\"evenodd\"/></svg>";

    // Injected on job/folder Configure pages — grays out the per-item matrix section
    private static final String JOB_CONFIGURE_SCRIPT =
        "<script>(function(){" +
        "try{" +
        "if(!window.location.pathname.match(/\\/configure$/))return;" +
        "function run(){" +
        "var cb=document.querySelector('input[name=\"useProjectSecurity\"]');" +
        "if(!cb)return;" +
        "var c=cb.closest('.optionalBlock-container');" +
        "if(!c){c=cb.parentElement;var d=0;while(c){if(c.classList&&(c.classList.contains('jenkins-form-item')||c.classList.contains('optionalBlock-container')))break;c=c.parentElement;if(++d>10){c=null;break;}}}" +
        "if(!c)return;" +
        "var w=document.createElement('div');" +
        "w.style.cssText='position:relative;margin-bottom:4px;';" +
        "c.parentNode.insertBefore(w,c);" +
        "w.appendChild(c);" +
        "c.style.cssText='opacity:0.4;pointer-events:none;user-select:none;';" +
        "var b=document.createElement('div');" +
        "b.style.cssText='padding:10px 14px;background:#fff8e1;border:1px solid #ffcc02;border-radius:6px;margin-bottom:6px;font-size:13px;color:#795600;display:flex;align-items:center;gap:8px;';" +
        "b.innerHTML='<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"flex-shrink:0;\"><path d=\"m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z\"/><path d=\"M12 9v4\"/><path d=\"M12 17h.01\"/></svg><span><strong>Managed by OmniAuth</strong> — Job-level permission matrix is disabled. Configure access via <strong>Manage Jenkins → OmniAuth → Access Management</strong>.</span>';" +
        "w.insertBefore(b,c);}" +
        "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',run);}else{run();}" +
        "}catch(e){}" +
        "})();</script>";

    // Injected on all authenticated pages — JS exits early if not on a job page.
    private static final String JIT_BANNER_SCRIPT =
        "<script>(function(){" +
        "try{" +
        "function extractJobPath(p){" +
          "var m=p.match(/\\/job\\/(.+)/);" +
          "if(!m)return null;" +
          "var parts=m[1].split('/');" +
          "var out=[];" +
          "for(var i=0;i<parts.length;i++){" +
            "var s=parts[i];" +
            "if(!s||s==='job')continue;" +
            "if(/^\\d+$/.test(s)||['build','configure','workspace','changes','lastBuild','api','console','testReport','cobertura','robot'].indexOf(s)>=0)break;" +
            "out.push(s);" +
          "}" +
          "return out.length?out.join('/'):null;" +
        "}" +
        "var jobPath=extractJobPath(window.location.pathname);" +
        "if(!jobPath)return;" +
        "var base=window.location.href.split('/job/')[0];" +
        "var _prevJitStatus=null;" +
        "var _pendingPoll=null;" +
        "function fetchStatus(){" +
          "fetch(base+'/omniauth-jit/status?job='+encodeURIComponent(jobPath),{credentials:'same-origin'})" +
          ".then(function(r){return r.json();})" +
          ".then(function(d){renderBanner(d);})" +
          ".catch(function(){});" +
        "}" +
        "function renderBanner(d){" +
          "if(!d||!d.hasJit)return;" +
          // Transition PENDING → ACTIVE: reload so server re-renders the Build Now button
          "if(d.status==='ACTIVE'&&_prevJitStatus==='PENDING'){window.location.reload();return;}" +
          // Poll every 5s while pending so we catch approval quickly
          "if(d.status==='PENDING'&&!_pendingPoll){_pendingPoll=setInterval(fetchStatus,5000);}" +
          "if(d.status!=='PENDING'&&_pendingPoll){clearInterval(_pendingPoll);_pendingPoll=null;}" +
          "_prevJitStatus=d.status;" +
          "removeBanner();" +
          "var banner=document.createElement('div');" +
          "banner.id='oau-jit-banner';" +
          "var color,icon,msg,actions;" +
          "if(d.status==='ACTIVE'){" +
            "removeGrayBuildNow();" +
            "var mins=Math.ceil(d.secondsRemaining/60);" +
            "color='#16a34a';icon='" + SVG_CHECK + "';" +
            "msg='<strong>JIT Access active</strong> &mdash; approved by <strong>'+escHtml(d.approverId)+'</strong> &middot; expires in <strong id=\"oau-jit-countdown\">'+fmtTime(d.secondsRemaining)+'</strong>';" +
            "actions='<button type=\"button\" onclick=\"oauJitRevoke(\\''+escHtml(d.requestId)+'\\')\" style=\"padding:4px 12px;border:1px solid #16a34a;border-radius:4px;background:#fff;color:#16a34a;cursor:pointer;font-size:12px;font-weight:600;\">End Access</button>';" +
            "startCountdown(d.secondsRemaining);" +
          "}else if(d.status==='PENDING'){" +
            "injectGrayBuildNow();" +
            "color='#d97706';icon='" + SVG_CLOCK + "';" +
            "var approvalInfo=d.totalApprovers>0?' &middot; <strong>'+d.approvedCount+' of '+d.totalApprovers+'</strong> approved':'';" +
            "msg='<strong>JIT request pending</strong>'+approvalInfo+' &middot; requested '+escHtml(d.timeAgo);" +
            "actions='<button type=\"button\" onclick=\"oauJitCancel(\\''+escHtml(d.requestId)+'\\')\" style=\"padding:4px 12px;border:1px solid #d97706;border-radius:4px;background:#fff;color:#d97706;cursor:pointer;font-size:12px;font-weight:600;\">Cancel Request</button>';" +
          "}else{" +
            "injectGrayBuildNow();" +
            "color='#6366f1';icon='" + SVG_BOLT + "';" +
            "var durOpts='';" +
            "for(var h=1;h<=d.maxDurationHours;h++){durOpts+='<option value=\"'+h+'\"'+(h===1?' selected':'')+'>'+h+' hour'+(h>1?'s':'')+'</option>';}" +
            "if(d.status==='DENIED'||d.status==='EXPIRED'||d.status==='TIMED_OUT'){" +
              "msg='<strong>JIT access required</strong> &mdash; previous request was <strong>'+d.status.toLowerCase()+'</strong>. Request again to build.';" +
            "}else{" +
              "msg='<strong>JIT access required</strong> &mdash; building this pipeline requires approval from '+escHtml(d.approverGroup||'an admin')+'.';" +
            "}" +
            "actions='<button type=\"button\" onclick=\"oauJitOpen()\" style=\"display:inline-flex;align-items:center;gap:5px;padding:4px 12px;border:none;border-radius:4px;background:#6366f1;color:#fff;cursor:pointer;font-size:12px;font-weight:600;\">" + SVG_BOLT + " Request JIT Access</button>';" +
          "}" +
          "banner.style.cssText='margin:8px 16px;padding:10px 14px;border-radius:6px;background:#fff;border:1px solid '+color+';display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;';" +
          "banner.innerHTML='<div style=\"display:flex;align-items:center;gap:8px;font-size:13px;\"><span style=\"display:inline-flex;align-items:center;color:'+color+';\">'+icon+'</span><span style=\"color:#333;\">'+msg+'</span></div><div style=\"display:flex;align-items:center;gap:8px;\">'+actions+'</div>';" +
          "var main=document.getElementById('main-panel')||document.querySelector('.jenkins-main-panel')||document.querySelector('[id*=\"main\"]');" +
          "if(main){main.insertBefore(banner,main.firstChild);}else{document.body.insertBefore(banner,document.body.firstChild);}" +
        "}" +
        "function removeBanner(){var b=document.getElementById('oau-jit-banner');if(b)b.remove();}" +
        "function injectGrayBuildNow(){" +
          "if(document.getElementById('oau-gray-build'))return;" +
          "var tasks=document.getElementById('tasks');if(!tasks)return;" +
          // Don't inject if real Build Now already exists
          "var links=tasks.querySelectorAll('a');for(var i=0;i<links.length;i++){if(/build now/i.test(links[i].textContent.trim()))return;}" +
          "var d=document.createElement('div');" +
          "d.id='oau-gray-build';d.className='task';" +
          "d.innerHTML='<a class=\"task-link\" href=\"#\" onclick=\"return false;\" style=\"opacity:0.38;cursor:default;pointer-events:none;\">'+" +
            "'<span class=\"task-icon-link\"><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"currentColor\"><polygon points=\"5,3 19,12 5,21\"/></svg></span>'+" +
            "'Build Now</a>';" +
          "tasks.insertBefore(d,tasks.firstChild);" +
        "}" +
        "function removeGrayBuildNow(){var el=document.getElementById('oau-gray-build');if(el)el.remove();}" +
        "function fmtTime(s){var m=Math.floor(s/60);var ss=s%60;return m+'m '+(ss<10?'0':'')+ss+'s';}" +
        "function escHtml(s){if(!s)return'';return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}" +
        "var _cdInterval=null;" +
        "function startCountdown(secs){" +
          "if(_cdInterval)clearInterval(_cdInterval);" +
          "var rem=secs;" +
          "_cdInterval=setInterval(function(){" +
            "rem--;" +
            "var el=document.getElementById('oau-jit-countdown');" +
            "if(el)el.textContent=fmtTime(rem);" +
            "if(rem<=0){clearInterval(_cdInterval);fetchStatus();}" +
          "},1000);" +
        "}" +
        // Request modal
        "var _jitModal=null;" +
        "window.oauJitOpen=function(){" +
          "if(_jitModal){_jitModal.style.display='flex';return;}" +
          "var m=document.createElement('div');" +
          "_jitModal=m;" +
          "m.style.cssText='display:flex;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.45);z-index:9999;align-items:center;justify-content:center;';" +
          "var durOpts='';" +
          "fetch(base+'/omniauth-jit/status?job='+encodeURIComponent(jobPath),{credentials:'same-origin'})" +
          ".then(function(r){return r.json();})" +
          ".then(function(d){" +
            "for(var h=1;h<=d.maxDurationHours;h++){durOpts+='<option value=\"'+h+'\"'+(h===1?' selected':'')+'>'+h+' hour'+(h>1?'s':'')+'</option>';}" +
            "var scopeLabel=d.scope&&d.scope!==jobPath?'Folder':'Pipeline';" +
          "var scopeVal=d.scope||jobPath;" +
          "var scopeNote=d.scopeIsFolder?'<br/><small style=\"color:#6b7280;\">Approves access to all pipelines in this folder</small>':'';" +
          "m.innerHTML='<div style=\"background:#fff;border-radius:8px;padding:24px;max-width:460px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,0.18);\">'+" +
              "'<div style=\"font-size:15px;font-weight:700;margin-bottom:16px;display:flex;align-items:center;gap:6px;\">" + SVG_BOLT + " Request JIT Access</div>'+" +
              "'<div style=\"font-size:12px;color:#888;margin-bottom:16px;padding:8px 12px;background:#f5f5f5;border-radius:4px;\">'+" +
              "'<strong>'+escHtml(scopeLabel)+':</strong> '+escHtml(scopeVal)+scopeNote+'<br/>'+" +
              "'<strong>Approver:</strong> '+escHtml(d.approverGroup||'Any admin')+'<br/>'+" +
              "'<strong>Auto-deny after:</strong> '+d.approvalTimeoutHours+' hour(s)'+" +
              "'</div>'+" +
              "'<div style=\"margin-bottom:12px;\"><label style=\"display:block;font-size:11px;font-weight:700;text-transform:uppercase;color:#888;margin-bottom:4px;\">Reason *</label>'+" +
              "'<textarea id=\"oau-jit-reason\" placeholder=\"Why do you need access? (e.g. Deploy v2.3.1, hotfix for JIRA-441)\" style=\"width:100%;height:72px;padding:8px;border:1px solid #ccc;border-radius:4px;font-size:13px;resize:none;box-sizing:border-box;\"></textarea></div>'+" +
              "'<div style=\"margin-bottom:16px;\"><label style=\"display:block;font-size:11px;font-weight:700;text-transform:uppercase;color:#888;margin-bottom:4px;\">Duration</label>'+" +
              "'<select id=\"oau-jit-dur\" style=\"width:100%;padding:8px;border:1px solid #ccc;border-radius:4px;font-size:13px;\">'+durOpts+'</select></div>'+" +
              "'<div id=\"oau-jit-err\" style=\"display:none;padding:8px 12px;background:#fee;border:1px solid #fca;border-radius:4px;font-size:12px;color:#c00;margin-bottom:12px;\"></div>'+" +
              "'<div style=\"display:flex;gap:8px;justify-content:flex-end;\">'+" +
              "'<button type=\"button\" onclick=\"oauJitClose()\" style=\"padding:8px 16px;border:1px solid #ccc;border-radius:4px;background:#fff;cursor:pointer;font-size:13px;\">Cancel</button>'+" +
              "'<button type=\"button\" id=\"oau-jit-submit\" onclick=\"oauJitSubmit()\" style=\"padding:8px 16px;border:none;border-radius:4px;background:#6366f1;color:#fff;cursor:pointer;font-size:13px;font-weight:600;\">Submit Request</button>'+" +
              "'</div></div>';" +
          "});" +
          "document.body.appendChild(m);" +
        "};" +
        "window.oauJitClose=function(){if(_jitModal)_jitModal.style.display='none';};" +
        "window.oauJitSubmit=function(){" +
          "var reason=document.getElementById('oau-jit-reason').value.trim();" +
          "var dur=document.getElementById('oau-jit-dur').value;" +
          "var err=document.getElementById('oau-jit-err');" +
          "if(!reason){err.textContent='Reason is required.';err.style.display='block';return;}" +
          "var btn=document.getElementById('oau-jit-submit');" +
          "btn.disabled=true;btn.textContent='Submitting...';" +
          "fetch(base+'/crumbIssuer/api/json',{credentials:'same-origin'})" +
          ".then(function(r){return r.json();})" +
          ".then(function(cd){" +
            "var body='job='+encodeURIComponent(jobPath)+'&reason='+encodeURIComponent(reason)+'&durationHours='+encodeURIComponent(dur);" +
            "var headers={'Content-Type':'application/x-www-form-urlencoded'};" +
            "headers[cd.crumbRequestField]=cd.crumb;" +
            "return fetch(base+'/omniauth-jit/request',{method:'POST',headers:headers,body:body,credentials:'same-origin'});" +
          "})" +
          ".then(function(r){return r.json();})" +
          ".then(function(data){" +
            "btn.disabled=false;btn.textContent='Submit Request';" +
            "if(data.ok){oauJitClose();fetchStatus();}else{err.textContent=data.error||'Request failed.';err.style.display='block';}" +
          "})" +
          ".catch(function(){btn.disabled=false;btn.textContent='Submit Request';err.textContent='Network error.';err.style.display='block';});" +
        "};" +
        "window.oauJitCancel=function(requestId){" +
          "if(!confirm('Cancel your pending JIT request?'))return;" +
          "fetch(base+'/crumbIssuer/api/json',{credentials:'same-origin'})" +
          ".then(function(r){return r.json();})" +
          ".then(function(cd){" +
            "var headers={'Content-Type':'application/x-www-form-urlencoded'};" +
            "headers[cd.crumbRequestField]=cd.crumb;" +
            "return fetch(base+'/omniauth-jit/cancel',{method:'POST',headers:headers,body:'requestId='+encodeURIComponent(requestId),credentials:'same-origin'});" +
          "})" +
          ".then(function(){fetchStatus();})" +
          ".catch(function(){});" +
        "};" +
        "window.oauJitRevoke=function(requestId){" +
          "if(!confirm('Revoke your active JIT access?'))return;" +
          "fetch(base+'/crumbIssuer/api/json',{credentials:'same-origin'})" +
          ".then(function(r){return r.json();})" +
          ".then(function(cd){" +
            "var headers={'Content-Type':'application/x-www-form-urlencoded'};" +
            "headers[cd.crumbRequestField]=cd.crumb;" +
            "return fetch(base+'/omniauth-jit/cancel',{method:'POST',headers:headers,body:'requestId='+encodeURIComponent(requestId),credentials:'same-origin'});" +
          "})" +
          ".then(function(){fetchStatus();})" +
          ".catch(function(){});" +
        "};" +
        "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',fetchStatus);}else{fetchStatus();}" +
        "}catch(e){}" +
        "})();</script>";

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public static void registerFilter() throws Exception {
        PluginServletFilter.addFilter(new MatrixDisableFilter());
        PluginServletFilter.addFilter(new TotpReminderFilter());
        PluginServletFilter.addFilter(new SosEnrollGateFilter());
        PluginServletFilter.addFilter(new JitBannerFilter());
        PluginServletFilter.addFilter(new SosBannerFilter());
        PluginServletFilter.addFilter(new ApproverActionFilter());
    }

    private static class MatrixDisableFilter implements Filter {

        @Override public void init(FilterConfig config) {}
        @Override public void destroy() {}

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {

            if (!(req instanceof HttpServletRequest)) {
                chain.doFilter(req, res);
                return;
            }

            HttpServletRequest httpReq = (HttpServletRequest) req;
            String path = httpReq.getRequestURI();

            String script = resolveScript(httpReq);
            if (script == null) {
                chain.doFilter(req, res);
                return;
            }

            HttpServletResponse httpRes = (HttpServletResponse) res;

            HttpServletRequest noGzipReq = new HttpServletRequestWrapper(httpReq) {
                @Override
                public String getHeader(String name) {
                    if ("Accept-Encoding".equalsIgnoreCase(name)) return null;
                    return super.getHeader(name);
                }
                @Override
                public Enumeration<String> getHeaders(String name) {
                    if ("Accept-Encoding".equalsIgnoreCase(name)) return Collections.emptyEnumeration();
                    return super.getHeaders(name);
                }
            };

            StreamCapture capture = new StreamCapture(httpRes);
            chain.doFilter(noGzipReq, capture);

            String contentType = capture.getContentType();
            byte[] body = capture.toByteArray();

            if (contentType != null && contentType.contains("text/html")) {
                String charset = StandardCharsets.UTF_8.name();
                if (contentType.contains("charset=")) {
                    charset = contentType.replaceAll(".*charset=([^;]+).*", "$1").trim();
                }
                String html = new String(body, charset);
                if (html.contains("</body>")) {
                    html = html.replace("</body>", script + "</body>");
                    body = html.getBytes(charset);
                }
            }

            httpRes.setContentLength(body.length);
            httpRes.getOutputStream().write(body);
        }

        private static String resolveScript(HttpServletRequest req) {
            String path = req.getRequestURI();
            if (path.endsWith("/configure") || path.endsWith("/configure/")) {
                return JOB_CONFIGURE_SCRIPT;
            }
            if (path.endsWith("/configureSecurity") || path.endsWith("/configureSecurity/")) {
                try {
                    AuthorizationStrategy s = Jenkins.get().getAuthorizationStrategy();
                    if (!(s instanceof OmniAuthAuthorizationStrategy)) return null;
                    // Check if break glass is currently active for this session
                    Long activeExpiry = null;
                    jakarta.servlet.http.HttpSession session = req.getSession(false);
                    if (session != null) {
                        Long expiry = (Long) session.getAttribute("omniauth.breakGlass.expiry");
                        if (expiry != null) {
                            if (System.currentTimeMillis() < expiry) {
                                activeExpiry = expiry;
                            } else {
                                // Expired — clean up
                                session.removeAttribute("omniauth.breakGlass.expiry");
                                session.removeAttribute("omniauth.breakGlass.user");
                                session.removeAttribute("omniauth.breakGlass.reason");
                            }
                        }
                    }
                    String mode = activeExpiry != null ? "active"
                            : (isCurrentUserEnrolled() ? "enrolled" : "notEnrolled");
                    return buildBreakGlassGateTags(req.getContextPath(), mode, activeExpiry);
                } catch (Exception ignored) {}
            }
            return null;
        }

        /**
         * Builds the CSP-compliant tags injected on /configureSecurity: a hidden state
         * element carrying the break-glass mode (and expiry when active) via data-*
         * attributes, plus an external same-origin &lt;script src&gt; for the gate logic.
         * Jenkins core's CSP (script-src 'self') blocks inline scripts, so the behaviour
         * lives in /plugin/omniauth-governance/js/breakglass-gate.js instead.
         */
        private static String buildBreakGlassGateTags(String ctx, String mode, Long expiry) {
            String c = ctx == null ? "" : ctx;
            StringBuilder sb = new StringBuilder();
            sb.append("<div id=\"omniauth-bg-state\" style=\"display:none\" data-mode=\"").append(mode).append('"');
            if (expiry != null) {
                sb.append(" data-expiry=\"").append(expiry.longValue()).append('"');
            }
            sb.append("></div>");
            sb.append("<script src=\"").append(c)
              .append("/plugin/omniauth-governance/js/breakglass-gate.js\"></script>");
            return sb.toString();
        }

        private static boolean isCurrentUserEnrolled() {
            try {
                hudson.model.User current = hudson.model.User.current();
                if (current == null) return false;
                OmniAuthUserProperty prop = current.getProperty(OmniAuthUserProperty.class);
                return prop != null && prop.isBreakGlassTotpEnrolled();
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static class TotpReminderFilter implements Filter {

        private static final String SESSION_DONE   = "omniauth.totp.reminderDoneThisSession";
        private static final String SESSION_RETURN = "omniauth.totp.returnTo";

        @Override public void init(FilterConfig c) {}
        @Override public void destroy() {}

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {

            if (!(req instanceof HttpServletRequest)) { chain.doFilter(req, res); return; }
            HttpServletRequest  httpReq = (HttpServletRequest)  req;
            HttpServletResponse httpRes = (HttpServletResponse) res;

            if (!"GET".equalsIgnoreCase(httpReq.getMethod()))                        { chain.doFilter(req, res); return; }
            if ("XMLHttpRequest".equals(httpReq.getHeader("X-Requested-With")))      { chain.doFilter(req, res); return; }
            if (isExcluded(httpReq.getRequestURI()))                                 { chain.doFilter(req, res); return; }

            try {
                Jenkins jenkins = Jenkins.getInstanceOrNull();
                if (jenkins == null)                                                  { chain.doFilter(req, res); return; }
                if (!(jenkins.getSecurityRealm() instanceof OmniAuthSecurityRealm))   { chain.doFilter(req, res); return; }
                if (!(jenkins.getAuthorizationStrategy() instanceof OmniAuthAuthorizationStrategy)) { chain.doFilter(req, res); return; }
                if (!jenkins.hasPermission(Jenkins.ADMINISTER))                      { chain.doFilter(req, res); return; }

                hudson.model.User current = hudson.model.User.current();
                if (current == null)                                                  { chain.doFilter(req, res); return; }

                OmniAuthUserProperty prop = current.getProperty(OmniAuthUserProperty.class);
                if (prop != null && prop.isBreakGlassTotpEnrolled())                 { chain.doFilter(req, res); return; }

                int  skips  = prop != null ? prop.getBreakGlassEnrollSkips() : 0;
                boolean forced = skips >= 3;

                // Always allow through if SESSION_DONE is set — covers the "last reminder dismiss"
                // case where doTotpReminderSkip sets SESSION_DONE just as skips reaches 3.
                // Next login the session is fresh, forced mode will show the wall.
                jakarta.servlet.http.HttpSession sess = httpReq.getSession(false);
                if (sess != null && Boolean.TRUE.equals(sess.getAttribute(SESSION_DONE))) { chain.doFilter(req, res); return; }

                if (!forced) {
                    // Mark shown for this session immediately to prevent redirect loops on sub-requests
                    httpReq.getSession(true).setAttribute(SESSION_DONE, Boolean.TRUE);
                }

                jakarta.servlet.http.HttpSession s = httpReq.getSession(true);
                if (s.getAttribute(SESSION_RETURN) == null) {
                    String uri = httpReq.getRequestURI();
                    String qs  = httpReq.getQueryString();
                    s.setAttribute(SESSION_RETURN, uri + (qs != null ? "?" + qs : ""));
                }

                httpRes.sendRedirect(httpReq.getContextPath() + "/manage/omniauth-management/totpReminder");

            } catch (Exception ignored) {
                chain.doFilter(req, res);
            }
        }

        private static boolean isExcluded(String path) {
            return path.contains("/totpReminder")   ||
                   path.contains("/emergencyAccess") ||
                   path.contains("/breakGlass")     ||
                   path.contains("/crumbIssuer")    ||
                   path.contains("/api/")           ||
                   path.contains("/adjuncts/")      ||
                   path.contains("/static/")        ||
                   path.contains("/plugin/")        ||
                   path.contains("/images/")        ||
                   path.contains("/login")          ||
                   path.contains("/logout")         ||
                   path.contains("/securityRealm")  ||
                   path.endsWith(".js")             ||
                   path.endsWith(".css")            ||
                   path.endsWith(".ico")            ||
                   path.endsWith(".png")            ||
                   path.endsWith(".gif")            ||
                   path.endsWith(".woff2")          ||
                   path.endsWith(".ttf");
        }
    }

    private static class JitBannerFilter implements Filter {

        @Override public void init(FilterConfig c) {}
        @Override public void destroy() {}

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {

            if (!(req instanceof HttpServletRequest)) { chain.doFilter(req, res); return; }
            HttpServletRequest  httpReq = (HttpServletRequest)  req;
            HttpServletResponse httpRes = (HttpServletResponse) res;

            if (!"GET".equalsIgnoreCase(httpReq.getMethod()))                        { chain.doFilter(req, res); return; }
            if ("XMLHttpRequest".equals(httpReq.getHeader("X-Requested-With")))      { chain.doFilter(req, res); return; }

            String path = httpReq.getRequestURI();
            if (!path.contains("/job/"))                                              { chain.doFilter(req, res); return; }
            if (isJitExcluded(path))                                                  { chain.doFilter(req, res); return; }

            try {
                Jenkins jenkins = Jenkins.getInstanceOrNull();
                if (jenkins == null)                                                  { chain.doFilter(req, res); return; }
                if (!(jenkins.getAuthorizationStrategy() instanceof OmniAuthAuthorizationStrategy)) { chain.doFilter(req, res); return; }
            } catch (Exception ignored) { chain.doFilter(req, res); return; }

            HttpServletRequest noGzipReq = new HttpServletRequestWrapper(httpReq) {
                @Override public String getHeader(String name) {
                    if ("Accept-Encoding".equalsIgnoreCase(name)) return null;
                    return super.getHeader(name);
                }
                @Override public java.util.Enumeration<String> getHeaders(String name) {
                    if ("Accept-Encoding".equalsIgnoreCase(name)) return java.util.Collections.emptyEnumeration();
                    return super.getHeaders(name);
                }
            };

            StreamCapture capture = new StreamCapture(httpRes);
            chain.doFilter(noGzipReq, capture);

            String contentType = capture.getContentType();
            byte[] body = capture.toByteArray();

            if (contentType != null && contentType.contains("text/html")) {
                String charset = "UTF-8";
                if (contentType.contains("charset=")) {
                    charset = contentType.replaceAll(".*charset=([^;]+).*", "$1").trim();
                }
                String html = new String(body, charset);
                if (html.contains("</body>")) {
                    html = html.replace("</body>", JIT_BANNER_SCRIPT + "</body>");
                    body = html.getBytes(charset);
                }
            }

            httpRes.setContentLength(body.length);
            httpRes.getOutputStream().write(body);
        }

        private static boolean isJitExcluded(String path) {
            return path.endsWith("/configure")     ||
                   path.endsWith("/configure/")    ||
                   path.contains("/configSubmit")  ||
                   path.contains("/api/")          ||
                   path.contains("/adjuncts/")     ||
                   path.contains("/static/")       ||
                   path.contains("/plugin/")       ||
                   path.endsWith(".js")            ||
                   path.endsWith(".css")           ||
                   path.endsWith(".ico")           ||
                   path.endsWith(".png");
        }
    }

    // ── No-auth approver action page ─────────────────────────────────────────
    // Serves GET  /omniauth-jit/action?token=<uuid>   → action form (no Jenkins login)
    // Serves POST /omniauth-jit/submitAction           → processes decision, shows confirmation

    private static class ApproverActionFilter implements Filter {

        @Override public void init(FilterConfig c) {}
        @Override public void destroy() {}

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            if (!(req instanceof HttpServletRequest)) { chain.doFilter(req, res); return; }
            HttpServletRequest  httpReq = (HttpServletRequest)  req;
            HttpServletResponse httpRes = (HttpServletResponse) res;

            String path = httpReq.getRequestURI();
            boolean isActionGet  = path.endsWith("/omniauth-jit/action")  && "GET".equalsIgnoreCase(httpReq.getMethod());
            boolean isSubmitPost = path.endsWith("/omniauth-jit/submitAction") && "POST".equalsIgnoreCase(httpReq.getMethod());

            if (!isActionGet && !isSubmitPost) { chain.doFilter(req, res); return; }

            try {
                if (isActionGet) {
                    String token = httpReq.getParameter("token");
                    serveActionPage(token, httpRes);
                } else {
                    String body = new String(httpReq.getInputStream().readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.Map<String, String> params = parseFormBody(body);
                    String token    = params.get("token");
                    String decision = params.get("decision");
                    String remarks  = params.get("remarks");
                    if (remarks != null && remarks.length() > 1000) remarks = remarks.substring(0, 1000);
                    serveSubmitAction(token, decision, remarks, httpRes);
                }
            } catch (Exception e) {
                httpRes.setStatus(500);
                httpRes.setContentType("text/plain");
                httpRes.getWriter().write("Internal error");
            }
        }

        private static void serveActionPage(String token, HttpServletResponse rsp) throws IOException {
            if (token == null || token.isBlank()) {
                renderSimplePage(rsp, 400, "Invalid Link", "This approval link is invalid.", "#dc2626");
                return;
            }
            OmniAuthJitRequestStore store = OmniAuthJitRequestStore.get();
            OmniAuthJitRequest jitReq = store != null ? store.findByToken(token) : null;
            if (jitReq == null) {
                renderSimplePage(rsp, 404, "Link Not Found", "This approval link is invalid or has expired.", "#dc2626");
                return;
            }
            if (!jitReq.isPending()) {
                String msg = "ACTIVE".equals(jitReq.getStatus())
                        ? "This request has already been fully approved — access is now active."
                        : "This request has already been processed (" + jitReq.statusLabel() + ").";
                renderSimplePage(rsp, 200, "Already Processed", msg, "#6b7280");
                return;
            }
            OmniAuthJitRequest.ApprovalEntry entry = jitReq.findEntryByToken(token);
            if (entry == null || !entry.isPending()) {
                renderSimplePage(rsp, 200, "Already Responded", "You have already submitted your decision for this request.", "#6b7280");
                return;
            }

            String submitUrl = getSubmitUrl();

            StringBuilder approversHtml = new StringBuilder();
            for (OmniAuthJitRequest.ApprovalEntry e : jitReq.getApprovalEntries()) {
                String statusHtml = e.isPending()
                        ? "<span style='color:#d97706;font-weight:600;display:inline-flex;align-items:center;gap:4px;'>" + SVG_CLOCK_SM + " Pending</span>"
                        : (e.isApproved()
                           ? "<span style='color:#16a34a;font-weight:600;display:inline-flex;align-items:center;gap:4px;'>" + SVG_CHECK_CIRCLE_SM + " Approved</span>"
                           : "<span style='color:#dc2626;font-weight:600;display:inline-flex;align-items:center;gap:4px;'>" + SVG_X_CIRCLE_SM + " Denied</span>");
                boolean isYou = token.equals(e.getToken());
                approversHtml.append("<tr>")
                        .append("<td style='padding:6px 12px;font-size:13px;'>")
                        .append(htmlEsc(e.getApproverIdentity()))
                        .append(isYou ? " <span style='font-size:11px;color:#6b7280;'>(you)</span>" : "")
                        .append("</td>")
                        .append("<td style='padding:6px 12px;'>").append(statusHtml).append("</td>")
                        .append("</tr>");
            }

            String html = actionPageHtml(jitReq, token, submitUrl, approversHtml.toString());
            rsp.setStatus(200);
            rsp.setContentType("text/html;charset=UTF-8");
            rsp.getWriter().write(html);
        }

        private static void serveSubmitAction(String token, String decision, String remarks, HttpServletResponse rsp) throws IOException {
            if (token == null || token.isBlank() || decision == null) {
                renderSimplePage(rsp, 400, "Invalid Request", "Missing token or decision.", "#dc2626");
                return;
            }
            if (!"APPROVED".equals(decision) && !"DENIED".equals(decision)) {
                renderSimplePage(rsp, 400, "Invalid Request", "Invalid decision value.", "#dc2626");
                return;
            }
            OmniAuthJitRequestStore store = OmniAuthJitRequestStore.get();
            if (store == null) { renderSimplePage(rsp, 500, "Error", "Store unavailable.", "#dc2626"); return; }

            OmniAuthJitRequestStore.ActionResult result = store.processApproverAction(token, decision, remarks);
            OmniAuthJitRequest jitReq = store.findByToken(token);

            switch (result) {
                case TOKEN_NOT_FOUND:
                    renderSimplePage(rsp, 404, "Link Not Found", "This approval link is invalid or has expired.", "#dc2626");
                    break;
                case ALREADY_PROCESSED:
                    renderSimplePage(rsp, 200, "Already Processed", "Your decision was already recorded for this request.", "#6b7280");
                    break;
                case DENIED:
                    if (jitReq != null) {
                        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
                        OmniAuthAuditLog audit = OmniAuthAuditLog.get();
                        if (audit != null) audit.logJitDenied(jitReq.getApproverId(), jitReq.getRequesterId(), jitReq.getScope(), remarks);
                        NotificationService.sendJitDenied(cfg, jitReq);
                    }
                    renderSimplePage(rsp, 200, "Request Denied", "You have denied this request. The requester will be notified.", "#dc2626");
                    break;
                case PARTIAL_APPROVED: {
                    String msg = jitReq != null
                            ? "Your approval is recorded (" + jitReq.getApprovedCount() + " of " + jitReq.getTotalApprovers() + " approved). Waiting for remaining approvers."
                            : "Your approval is recorded. Waiting for remaining approvers.";
                    renderSimplePage(rsp, 200, "Approval Recorded", msg, "#d97706");
                    break;
                }
                case ALL_APPROVED:
                    if (jitReq != null) {
                        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
                        OmniAuthAuditLog audit = OmniAuthAuditLog.get();
                        if (audit != null) audit.logJitApproved(jitReq.getApproverId(), jitReq.getRequesterId(), jitReq.getScope(), jitReq.getRequestedDurationHours());
                        NotificationService.sendJitApproved(cfg, jitReq);
                    }
                    renderSimplePage(rsp, 200, "Access Granted", "All approvers have approved. " + (jitReq != null ? jitReq.getRequesterId() : "The requester") + " now has access.", "#16a34a");
                    break;
            }
        }

        private static String getSubmitUrl() {
            try {
                String root = jenkins.model.Jenkins.get().getRootUrl();
                if (root != null && !root.isEmpty()) {
                    root = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
                    return root + "/omniauth-jit/submitAction";
                }
            } catch (Exception ignore) {}
            return "/omniauth-jit/submitAction";
        }

        private static String actionPageHtml(OmniAuthJitRequest req, String token, String submitUrl, String approversHtml) {
            return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>JIT Access Request — Take Action</title>"
                + "<style>*{box-sizing:border-box;}body{margin:0;padding:24px 16px;background:#f3f4f6;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#1f2937;}"
                + ".card{background:#fff;border-radius:10px;box-shadow:0 2px 16px rgba(0,0,0,0.08);max-width:560px;margin:0 auto;overflow:hidden;}"
                + ".hdr{background:#d97706;padding:18px 24px;color:#fff;display:flex;align-items:center;gap:10px;}"
                + ".hdr h1{margin:0;font-size:17px;font-weight:700;}"
                + ".body{padding:24px;}"
                + ".kv{width:100%;border-collapse:collapse;margin-bottom:16px;font-size:13px;}"
                + ".kv td{padding:6px 0;}.kv td:first-child{color:#6b7280;width:120px;font-weight:600;}"
                + ".reason{background:#fffbeb;border:1px solid #fde68a;border-radius:6px;padding:10px 14px;"
                + "font-size:13px;color:#92400e;margin-bottom:16px;}"
                + ".approvers-table{width:100%;border-collapse:collapse;border:1px solid #e5e7eb;border-radius:6px;"
                + "overflow:hidden;margin-bottom:20px;font-size:13px;}"
                + ".approvers-table th{background:#f9fafb;padding:6px 12px;text-align:left;font-size:11px;"
                + "color:#6b7280;text-transform:uppercase;font-weight:700;}"
                + "section{margin-bottom:20px;padding:16px;border:1px solid #e5e7eb;border-radius:8px;}"
                + "section h3{margin:0 0 10px;font-size:14px;font-weight:700;}"
                + "textarea{width:100%;padding:8px;border:1px solid #d1d5db;border-radius:6px;font-size:13px;"
                + "resize:none;height:72px;font-family:inherit;}"
                + ".btn{display:inline-block;padding:10px 20px;border:none;border-radius:6px;font-size:13px;"
                + "font-weight:700;cursor:pointer;margin-top:8px;}"
                + ".btn-approve{background:#16a34a;color:#fff;}.btn-deny{background:#dc2626;color:#fff;}"
                + "</style></head><body>"
                + "<div class='card'>"
                + "<div class='hdr'><span style='display:inline-flex;align-items:center;'>" + SVG_BOLT + "</span><h1>JIT Access Request — Action Required</h1></div>"
                + "<div class='body'>"
                + "<table class='kv'>"
                + "<tr><td>Requested by</td><td><strong>" + htmlEsc(req.getRequesterId()) + "</strong></td></tr>"
                + "<tr><td>Pipeline</td><td><code style='background:#f3f4f6;padding:2px 6px;border-radius:3px;'>" + htmlEsc(req.getScope()) + "</code></td></tr>"
                + "<tr><td>Duration</td><td>" + req.getRequestedDurationHours() + " hour(s)</td></tr>"
                + "<tr><td>Requested</td><td>" + htmlEsc(req.timeAgo()) + "</td></tr>"
                + "</table>"
                + "<div class='reason'><strong>Reason:</strong> " + htmlEsc(req.getReason()) + "</div>"
                + "<table class='approvers-table'>"
                + "<thead><tr><th>Approver</th><th>Status</th></tr></thead>"
                + "<tbody>" + approversHtml + "</tbody></table>"
                + "<form method='POST' action='" + htmlEsc(submitUrl) + "'>"
                + "<input type='hidden' name='token' value='" + htmlEsc(token) + "'/>"
                + "<section>"
                + "<h3 style='color:#16a34a;display:flex;align-items:center;gap:5px;'>" + SVG_CHECK_CIRCLE_SM + " Approve Access</h3>"
                + "<textarea name='approveRemarks' placeholder='Remarks (optional)'></textarea>"
                + "<button type='submit' name='decision' value='APPROVED' class='btn btn-approve' "
                + "style='display:inline-flex;align-items:center;gap:5px;'"
                + " onclick='document.querySelector(\"[name=remarks]\").value=this.form.approveRemarks.value'>"
                + SVG_CHECK_CIRCLE_SM + " Approve Access</button>"
                + "</section>"
                + "<section>"
                + "<h3 style='color:#dc2626;display:flex;align-items:center;gap:5px;'>" + SVG_X_CIRCLE_SM + " Deny Request</h3>"
                + "<textarea name='denyRemarks' placeholder='Reason for denial (recommended)'></textarea>"
                + "<button type='submit' name='decision' value='DENIED' class='btn btn-deny' "
                + "style='display:inline-flex;align-items:center;gap:5px;'"
                + " onclick='document.querySelector(\"[name=remarks]\").value=this.form.denyRemarks.value'>"
                + SVG_X_CIRCLE_SM + " Deny Request</button>"
                + "</section>"
                + "<input type='hidden' name='remarks' value=''/>"
                + "</form>"
                + "</div></div>"
                + "</body></html>";
        }

        private static void renderSimplePage(HttpServletResponse rsp, int status,
                                              String title, String message, String color) throws IOException {
            rsp.setStatus(status);
            rsp.setContentType("text/html;charset=UTF-8");
            rsp.getWriter().write(
                "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<title>" + htmlEsc(title) + "</title>"
                + "<style>body{margin:0;padding:40px 16px;background:#f3f4f6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;}"
                + ".card{background:#fff;border-radius:10px;max-width:480px;margin:0 auto;padding:32px;text-align:center;box-shadow:0 2px 16px rgba(0,0,0,0.08);}"
                + ".icon{font-size:40px;margin-bottom:12px;color:" + color + ";}"
                + "h1{margin:0 0 12px;font-size:18px;color:" + color + ";}"
                + "p{margin:0;font-size:14px;color:#4b5563;line-height:1.6;}</style></head><body>"
                + "<div class='card'><div class='icon'>" + SVG_BOLT + "</div>"
                + "<h1>" + htmlEsc(title) + "</h1>"
                + "<p>" + htmlEsc(message) + "</p>"
                + "</div></body></html>"
            );
        }

        private static java.util.Map<String, String> parseFormBody(String body) {
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                try {
                    String k = java.net.URLDecoder.decode(kv[0], "UTF-8");
                    String v = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], "UTF-8") : "";
                    map.put(k, v);
                } catch (Exception ignore) {}
            }
            return map;
        }

        private static String htmlEsc(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
        }
    }

    // ── Mandatory SOS recovery-code enrollment gate ─────────────────────────────
    // Blocks an admin with no SOS recovery codes until they generate a set. Unlike the TOTP
    // reminder this is not skippable: codes are generated on-screen instantly, so enforcing it
    // cannot lock anyone out. Redirects to the SOS Recovery Codes page in enroll mode.

    private static class SosEnrollGateFilter implements Filter {

        @Override public void init(FilterConfig c) {}
        @Override public void destroy() {}

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {

            if (!(req instanceof HttpServletRequest)) { chain.doFilter(req, res); return; }
            HttpServletRequest  httpReq = (HttpServletRequest)  req;
            HttpServletResponse httpRes = (HttpServletResponse) res;

            if (!"GET".equalsIgnoreCase(httpReq.getMethod()))                   { chain.doFilter(req, res); return; }
            if ("XMLHttpRequest".equals(httpReq.getHeader("X-Requested-With"))) { chain.doFilter(req, res); return; }
            if (isExcluded(httpReq.getRequestURI()))                            { chain.doFilter(req, res); return; }

            try {
                Jenkins jenkins = Jenkins.getInstanceOrNull();
                if (jenkins == null)                                               { chain.doFilter(req, res); return; }
                if (!(jenkins.getSecurityRealm() instanceof OmniAuthSecurityRealm)) { chain.doFilter(req, res); return; }
                if (!(jenkins.getAuthorizationStrategy() instanceof OmniAuthAuthorizationStrategy)) { chain.doFilter(req, res); return; }
                if (!jenkins.hasPermission(Jenkins.ADMINISTER))                    { chain.doFilter(req, res); return; }

                hudson.model.User current = hudson.model.User.current();
                if (current == null)                                               { chain.doFilter(req, res); return; }
                // Gate on USABLE codes: an admin who has burned through every code must regenerate.
                OmniAuthRecoveryCodesProperty prop = current.getProperty(OmniAuthRecoveryCodesProperty.class);
                if (prop != null && prop.getUnusedCount() > 0)                     { chain.doFilter(req, res); return; }

                httpRes.sendRedirect(httpReq.getContextPath()
                        + "/manage/omniauth-management/emergencyAccess?enroll=1");
            } catch (Exception ignored) {
                chain.doFilter(req, res);
            }
        }

        private static boolean isExcluded(String path) {
            return path.contains("/emergencyAccess") ||
                   path.contains("/sosRecoveryCodes") ||
                   path.contains("/sosGenerateCodes")  ||
                   path.contains("/totpReminder")      ||
                   path.contains("/breakGlass")        ||
                   path.contains("/crumbIssuer")       ||
                   path.contains("/api/")              ||
                   path.contains("/adjuncts/")         ||
                   path.contains("/static/")           ||
                   path.contains("/plugin/")           ||
                   path.contains("/images/")           ||
                   path.contains("/login")             ||
                   path.contains("/logout")            ||
                   path.contains("/securityRealm")     ||
                   path.contains("/sos/")              ||
                   path.endsWith(".js")                ||
                   path.endsWith(".css")               ||
                   path.endsWith(".ico")               ||
                   path.endsWith(".png")               ||
                   path.endsWith(".gif")               ||
                   path.endsWith(".woff2")             ||
                   path.endsWith(".ttf");
        }
    }

    // ── Site-wide "SOS Mode active" banner ───────────────────────────────────────
    // Injected on every HTML page while SOS is armed, so an active recovery window is impossible
    // to miss. Carries a one-click Deactivate (admin-only; POSTs to the management endpoint).

    private static class SosBannerFilter implements Filter {

        @Override public void init(FilterConfig c) {}
        @Override public void destroy() {}

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {

            if (!(req instanceof HttpServletRequest)) { chain.doFilter(req, res); return; }
            HttpServletRequest  httpReq = (HttpServletRequest)  req;
            HttpServletResponse httpRes = (HttpServletResponse) res;

            String path = httpReq.getRequestURI();
            // Show the banner only to admins — the only ones who can Deactivate, and the ones it's
            // meant for. This also keeps it off the public login page (no anonymous "SOS active"
            // broadcast, and no Deactivate button that an unauthenticated visitor can't use).
            if (!"GET".equalsIgnoreCase(httpReq.getMethod()) || isBannerExcluded(path)
                    || !OmniAuthSos.isArmed() || !currentUserIsAdmin()) {
                chain.doFilter(req, res);
                return;
            }

            HttpServletRequest noGzipReq = new HttpServletRequestWrapper(httpReq) {
                @Override public String getHeader(String name) {
                    if ("Accept-Encoding".equalsIgnoreCase(name)) return null;
                    return super.getHeader(name);
                }
                @Override public Enumeration<String> getHeaders(String name) {
                    if ("Accept-Encoding".equalsIgnoreCase(name)) return Collections.emptyEnumeration();
                    return super.getHeaders(name);
                }
            };

            StreamCapture capture = new StreamCapture(httpRes);
            chain.doFilter(noGzipReq, capture);

            String contentType = capture.getContentType();
            byte[] body = capture.toByteArray();
            if (contentType != null && contentType.contains("text/html")) {
                String charset = StandardCharsets.UTF_8.name();
                if (contentType.contains("charset=")) {
                    charset = contentType.replaceAll(".*charset=([^;]+).*", "$1").trim();
                }
                String html = new String(body, charset);
                if (html.contains("</body>")) {
                    String script = buildSosBannerScript(httpReq.getContextPath(), OmniAuthSos.minutesRemaining());
                    html = html.replace("</body>", script + "</body>");
                    body = html.getBytes(charset);
                }
            }
            httpRes.setContentLength(body.length);
            httpRes.getOutputStream().write(body);
        }

        private static boolean currentUserIsAdmin() {
            try {
                Jenkins j = Jenkins.getInstanceOrNull();
                return j != null && j.hasPermission(Jenkins.ADMINISTER);
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isBannerExcluded(String path) {
            return path.contains("/sos/")        ||
                   path.contains("/crumbIssuer")  ||
                   path.contains("/api/")         ||
                   path.contains("/adjuncts/")    ||
                   path.contains("/static/")      ||
                   path.contains("/plugin/")      ||
                   path.contains("/images/")      ||
                   path.endsWith(".js")           ||
                   path.endsWith(".css")          ||
                   path.endsWith(".ico")          ||
                   path.endsWith(".png")          ||
                   path.endsWith(".gif")          ||
                   path.endsWith(".woff2")        ||
                   path.endsWith(".ttf");
        }

        private static String buildSosBannerScript(String ctx, long mins) {
            String ctxJs = ctx.replace("\\", "\\\\").replace("'", "\\'");
            return "<script>(function(){try{" +
                "if(document.getElementById('oau-sos-banner'))return;" +
                "var ctx='" + ctxJs + "';var mins=" + mins + ";" +
                "var bar=document.createElement('div');bar.id='oau-sos-banner';" +
                "bar.style.cssText='position:fixed;top:0;left:0;right:0;z-index:2147483647;background:#b91c1c;color:#fff;"
                + "padding:10px 16px;display:flex;align-items:center;justify-content:center;gap:14px;flex-wrap:wrap;"
                + "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Arial,sans-serif;font-size:13px;"
                + "box-shadow:0 2px 8px rgba(0,0,0,0.3);';" +
                "bar.innerHTML='<span style=\"display:inline-flex;align-items:center;gap:8px;\">" +
                  "<strong style=\"display:inline-flex;align-items:center;gap:6px;\"><svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z\"/><path d=\"M12 9v4\"/><path d=\"M12 17h.01\"/></svg>SOS Mode active</strong>" +
                  "<span style=\"opacity:.9;\">Emergency recovery is open at <code style=\\'background:rgba(0,0,0,.2);padding:1px 5px;border-radius:3px;\\'>/sos/recover</code>"
                  + " &middot; auto-closes in '+mins+' min</span></span>" +
                  "<button type=\"button\" id=\"oau-sos-deact\" style=\"white-space:nowrap;padding:5px 14px;border:1px solid #fff;"
                  + "border-radius:5px;background:#fff;color:#b91c1c;font-size:12px;font-weight:700;cursor:pointer;\">Deactivate now</button>';" +
                "function place(){document.body.insertBefore(bar,document.body.firstChild);" +
                  "document.body.style.paddingTop=(bar.offsetHeight||40)+'px';}" +
                "if(document.body){place();}else{document.addEventListener('DOMContentLoaded',place);}" +
                "var btn=bar.querySelector('#oau-sos-deact');" +
                "btn.onclick=function(){btn.disabled=true;btn.textContent='Deactivating...';" +
                  "fetch(ctx+'/crumbIssuer/api/json',{credentials:'same-origin'})" +
                  ".then(function(r){return r.json();})" +
                  ".then(function(cd){var h={'Content-Type':'application/x-www-form-urlencoded'};h[cd.crumbRequestField]=cd.crumb;" +
                    "return fetch(ctx+'/manage/omniauth-management/sosDeactivate',{method:'POST',headers:h,body:'',credentials:'same-origin'});})" +
                  ".then(function(r){if(r.ok){window.location.reload();}else{btn.disabled=false;btn.textContent='Deactivate now';alert('You must be an administrator to deactivate SOS Mode.');}})" +
                  ".catch(function(){btn.disabled=false;btn.textContent='Deactivate now';alert('Could not deactivate — network error.');});" +
                "};" +
                "}catch(e){}})();</script>";
        }
    }

    private static class StreamCapture extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener wl) {}
            @Override public void write(int b) { bos.write(b); }
            @Override public void write(byte[] b, int off, int len) { bos.write(b, off, len); }
        };

        StreamCapture(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return stream;
        }

        @Override
        public PrintWriter getWriter() {
            return new PrintWriter(bos);
        }

        public byte[] toByteArray() {
            return bos.toByteArray();
        }
    }
}
