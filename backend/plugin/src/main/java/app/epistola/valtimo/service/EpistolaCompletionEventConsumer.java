package app.epistola.valtimo.service;

/**
 * Consumes document generation completion events from Epistola
 * and correlates BPMN messages to wake up waiting processes.
 * <p>
 * Implementations may use polling, webhooks, event streams, etc.
 * The BPMN process doesn't care how it gets notified — it waits
 * for a message. The infrastructure behind the message correlation
 * is swappable.
 */
public interface EpistolaCompletionEventConsumer {

    /**
     * Start consuming events (called on application startup).
     */
    void start();

    /**
     * Stop consuming events (called on shutdown).
     */
    void stop();
}
