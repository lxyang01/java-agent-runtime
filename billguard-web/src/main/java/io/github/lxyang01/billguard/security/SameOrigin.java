package io.github.lxyang01.billguard.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * POST 同源校验(对齐 web.py _same_origin):
 * Origin(优先)或 Referer(兜底)任一存在时,其 netloc(小写)必须等于 Host;
 * 两者都缺失放行 —— 非浏览器客户端(curl/服务间)没有 Cookie 自动附带语义,
 * 不在 CSRF 威胁模型内;Cookie 已带 SameSite=Strict,本校验是纵深防御第二层。
 * 比较不含 scheme(TLS 终止部署下 Origin 为 https 而内网请求无 scheme)。
 */
public final class SameOrigin {

    private SameOrigin() {}

    public static boolean isSameOrigin(HttpServletRequest request) {
        String origin = header(request, "Origin");
        String referer = header(request, "Referer");
        if (origin.isEmpty() && referer.isEmpty()) {
            return true;
        }
        String source = origin.isEmpty() ? referer : origin;
        String host = header(request, "Host").strip().toLowerCase();
        if (host.isEmpty()) {
            return false;
        }
        return netloc(source).equals(host);
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value;
    }

    /** 从 URL 提取 netloc(scheme://host[:port] → host[:port]),小写。 */
    private static String netloc(String url) {
        String text = url.strip();
        int schemeEnd = text.indexOf("://");
        String rest = schemeEnd >= 0 ? text.substring(schemeEnd + 3) : text;
        int pathStart = rest.indexOf('/');
        if (pathStart >= 0) {
            rest = rest.substring(0, pathStart);
        }
        int queryStart = rest.indexOf('?');
        if (queryStart >= 0) {
            rest = rest.substring(0, queryStart);
        }
        return rest.strip().toLowerCase();
    }
}
