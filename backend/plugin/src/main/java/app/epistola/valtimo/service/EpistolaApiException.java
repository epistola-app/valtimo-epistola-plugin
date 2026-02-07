package app.epistola.valtimo.service;

/**
 * Exception thrown when an Epistola API call fails.
 */
public class EpistolaApiException extends RuntimeException {

    public EpistolaApiException(String message) {
        super(message);
    }

    public EpistolaApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
