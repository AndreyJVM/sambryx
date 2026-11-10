package mari.samba.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import mari.samba.ad.AdJoinException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String DEFAULT_GENERIC_ERROR_MESSAGE = "An unexpected error occurred.";

    @ExceptionHandler(SshSessionExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleSshSessionExpired(
            @NonNull SshSessionExpiredException ex, @NonNull HttpServletRequest request, HttpSession session) {

        log.warn("SSH session expired on {}: {}", request.getRequestURI(), ex.getMessage());

        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // Session already invalidated, safe to ignore
            }
        }

        String msg = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : "SSH session has expired or is invalid. Please log in again.";

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(msg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParams(
            @NonNull MissingServletRequestParameterException ex, @NonNull HttpServletRequest request) {
        String msg = "Required parameter is missing: " + ex.getParameterName();
        log.warn("Missing parameter on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(
            @NonNull HttpMessageNotReadableException ex, @NonNull HttpServletRequest request) {
        log.warn("Malformed JSON on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("Malformed JSON request."));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            @NonNull ConstraintViolationException ex, @NonNull HttpServletRequest request) {
        String errors = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));

        log.warn("Constraint violation on {}: {}", request.getRequestURI(), errors);
        return ResponseEntity.badRequest().body(ApiResponse.error("Validation error: " + errors));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            @NonNull MethodArgumentNotValidException ex, @NonNull HttpServletRequest request) {

        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("Validation error on {}: {}", request.getRequestURI(), errors);
        return ResponseEntity.badRequest().body(ApiResponse.error("Validation error: " + errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            @NonNull IllegalArgumentException ex, @NonNull HttpServletRequest request) {
        log.warn("Bad request on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler({SecurityException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(
            @NonNull Exception ex, @NonNull HttpServletRequest request) {
        String msg = (ex instanceof AccessDeniedException) ? "Access denied." : ex.getMessage();
        log.warn("Security violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(msg));
    }

    @ExceptionHandler(SambaCommandException.class)
    public ResponseEntity<ApiResponse<Void>> handleSambaCommandException(
            @NonNull SambaCommandException ex, @NonNull HttpServletRequest request) {
        log.error("Samba command execution failure on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Samba command failed: " + ex.getMessage()));
    }

    @ExceptionHandler(AdJoinException.class)
    public ResponseEntity<ApiResponse<Void>> handleAdJoinException() {
        log.error("Domain join failed.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Domain join failed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(
            @NonNull Exception ex, @NonNull HttpServletRequest request) {
        log.error("Unhandled exception ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(DEFAULT_GENERIC_ERROR_MESSAGE));
    }
}
