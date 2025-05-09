package com.f4.reel.broker;

/**
 * Exception that indicates an error should trigger a retry.
 * This exception class is used to explicitly mark errors that should cause
 * the Kafka message processing to be retried according to the configured retry policy.
 */
public class RetryableException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;

    public RetryableException(String message) {
        super(message);
    }
    
    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public RetryableException(Throwable cause) {
        super(cause);
    }
}