package io.github.lxyang01.billguard.web;

import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.tool.ToolException;
import io.github.lxyang01.billguard.auth.AuthError;
import io.github.lxyang01.billguard.auth.PermissionDenied;
import io.github.lxyang01.billguard.core.BusyException;
import io.github.lxyang01.billguard.core.LockedException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常 → JSON 状态码映射(对齐 Python handler 的 except 链):
 * AuthError→401 / PermissionDenied→403 / 参数与工具策略错误→400 /
 * Locked→423 / Busy→429 / 兜底 500「请求失败:{msg}」。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthError.class)
    public ResponseEntity<Map<String, Object>> authError(AuthError e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(PermissionDenied.class)
    public ResponseEntity<Map<String, Object>> permissionDenied(PermissionDenied e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, ToolException.class, PolicyException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, Object>> locked(LockedException e) {
        return error(HttpStatus.LOCKED, e.getMessage());
    }

    @ExceptionHandler(BusyException.class)
    public ResponseEntity<Map<String, Object>> busy(BusyException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "请求失败:" + e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
