package network.vonix.vonixcore.teleport;

/**
 * Enumeration of possible RTP request states.
 * Used for tracking request lifecycle and providing user feedback.
 */
public enum RTPStatus {
    /**
     * Request has been queued but not yet started processing
     */
    QUEUED,
    
    /**
     * Request is currently being processed (searching for location)
     */
    PROCESSING,
    
    /**
     * Location found, preparing for teleportation
     */
    TELEPORTING,
    
    /**
     * Request completed successfully
     */
    COMPLETED,
    
    /**
     * Request failed (no safe location found, timeout, etc.)
     */
    FAILED,
    
    /**
     * Request was cancelled by user or system
     */
    CANCELLED;

    /**
     * Check if this status represents a terminal state (request finished)
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Check if this status represents an active state (request in progress)
     */
    public boolean isActive() {
        return this == PROCESSING || this == TELEPORTING;
    }
}