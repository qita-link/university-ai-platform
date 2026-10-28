package com.campus.platform.common.exception;

import com.campus.platform.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 统一异常处理。
 * <p>
 * 原则：对外只返回错误码和安全的提示语；数据库异常、堆栈、SQL、密钥等内部细节只写入服务端日志，
 * 通过响应中的 traceId 关联排查。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.error("业务处理失败: code={}, message={}", code, ex.getMessage(), ex);
        } else {
            log.info("业务异常: code={}, message={}", code, ex.getMessage());
        }
        ResponseEntity<ApiResponse<Void>> response = build(code, ex.getMessage(), null);
        if (ex instanceof RateLimitedException rl) {
            return ResponseEntity.status(response.getStatusCode())
                    .header("Retry-After", Long.toString(rl.getRetryAfterSeconds()))
                    .body(response.getBody());
        }
        return response;
    }

    // ---------------- 参数校验 ----------------

    /** @RequestBody 校验失败，以及 @ModelAttribute（查询参数对象）绑定/校验失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        List<ApiResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiResponse.FieldError(fe.getField(),
                        fe.isBindingFailure() ? "参数格式错误" : fe.getDefaultMessage()))
                .toList();
        List<ApiResponse.FieldError> globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> new ApiResponse.FieldError(ge.getObjectName(), ge.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, null, concat(errors, globalErrors));
    }

    /** Spring 6.1+ 内置方法参数校验（@RequestParam/@PathVariable 上的约束注解） */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException ex) {
        List<ApiResponse.FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new ApiResponse.FieldError(
                                r.getMethodParameter().getParameterName(), messageOf(err))))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, null, errors);
    }

    /** Service 层 @Validated 方法校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiResponse.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, null, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.debug("请求体无法解析: {}", ex.getMessage());
        return build(ErrorCode.BAD_REQUEST, "请求体格式错误", null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(ErrorCode.VALIDATION_FAILED, null,
                List.of(new ApiResponse.FieldError(ex.getParameterName(), "不能为空")));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        return build(ErrorCode.VALIDATION_FAILED, null,
                List.of(new ApiResponse.FieldError(ex.getRequestPartName(), "不能为空")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.VALIDATION_FAILED, null,
                List.of(new ApiResponse.FieldError(ex.getName(), "参数格式错误")));
    }

    // ---------------- HTTP 协议层 ----------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, null, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, "接口不存在", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE, null, null);
    }

    // ---------------- 安全 ----------------

    /** 方法级授权（@PreAuthorize）拒绝。URL 级拒绝由 Security 过滤器链中的 AccessDeniedHandler 处理。 */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.info("访问被拒绝: {}", ex.getMessage());
        return build(ErrorCode.FORBIDDEN, null, null);
    }

    // ---------------- 数据层 ----------------

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        // 业务层应优先自行检查并给出具体提示；此处是兜底（如并发写入触发唯一约束）
        log.warn("唯一约束冲突: {}", rootMessage(ex));
        return build(ErrorCode.CONFLICT, "数据已存在或发生并发冲突，请刷新后重试", null);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> handleLocking(RuntimeException ex) {
        log.warn("并发冲突: {}", rootMessage(ex));
        return build(ErrorCode.CONFLICT, null, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据完整性约束失败: {}", rootMessage(ex));
        return build(ErrorCode.CONFLICT, "数据不满足约束条件", null);
    }

    // ---------------- 兜底 ----------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未处理的异常", ex);
        return build(ErrorCode.INTERNAL_ERROR, null, null);
    }

    // ---------------- helpers ----------------

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message,
                                                    List<ApiResponse.FieldError> errors) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.fail(code, message, errors));
    }

    private static String messageOf(MessageSourceResolvable err) {
        return err.getDefaultMessage() != null ? err.getDefaultMessage() : "参数不合法";
    }

    private static String lastNode(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        if (b.isEmpty()) {
            return a;
        }
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
