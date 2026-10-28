package com.campus.platform.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前请求的客户端信息（IP、User-Agent），供审计使用。
 * <p>
 * 客户端 IP 取自 {@link HttpServletRequest#getRemoteAddr()}。部署在反向代理后时，
 * 通过 {@code server.forward-headers-strategy} 让容器解析可信的 X-Forwarded-For，
 * 而不是在代码里直接信任请求头（请求头可被伪造）。
 *
 * @param ip        客户端 IP，非 Web 请求为 null
 * @param userAgent User-Agent，已截断到 255 字符
 */
public record ClientInfo(String ip, String userAgent) {

    private static final ClientInfo NONE = new ClientInfo(null, null);

    public static ClientInfo current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return NONE;
        }
        HttpServletRequest request = servletAttrs.getRequest();
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > 255) {
            ua = ua.substring(0, 255);
        }
        return new ClientInfo(request.getRemoteAddr(), ua);
    }
}
