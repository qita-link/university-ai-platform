package com.campus.platform.security.handler;

import com.campus.platform.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未认证或令牌无效：返回 401 + 统一响应体。具体失败原因只写日志，不返回给客户端。
 */
@Slf4j
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorWriter writer;

    public RestAuthenticationEntryPoint(SecurityErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("认证失败 {} {}: {}", request.getMethod(), request.getRequestURI(), authException.getMessage());
        response.setHeader("WWW-Authenticate", "Bearer");
        writer.write(response, ErrorCode.UNAUTHORIZED);
    }
}
