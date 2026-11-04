package dev.schemaforge;

public class ApiException extends RuntimeException {
    final int status;
    public ApiException(int status, String message) { super(message); this.status = status; }
    public static ApiException bad(String message) { return new ApiException(400, message); }
}
