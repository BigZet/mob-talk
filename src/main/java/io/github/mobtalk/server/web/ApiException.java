package io.github.mobtalk.server.web;

/** An API failure the page can show as is: an HTTP status, a stable code and a Russian sentence (docs/admin-web.md, «Ошибка»). */
public final class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    /** Seconds to wait, for 429; 0 otherwise. */
    public final int retryAfter;

    public ApiException(int status, String code, String message) { this(status, code, message, 0); }
    public ApiException(int status, String code, String message, int retryAfter) {
        super(message, null, false, false);
        this.status = status; this.code = code; this.retryAfter = retryAfter;
    }

    public static ApiException badRequest(String message) { return new ApiException(400, "bad_request", message); }
    public static ApiException notFound(String message) { return new ApiException(404, "not_found", message); }
    public static ApiException conflict(String message) { return new ApiException(409, "conflict", message); }
    public static ApiException forbidden(String message) { return new ApiException(403, "forbidden", message); }
    public static ApiException unavailable(String message) { return new ApiException(503, "unavailable", message); }
}
