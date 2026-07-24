package io.github.rads4.ckaws.auth;

/**
 * Thrown when a valid STS {@code RoleSessionName} cannot be produced from the given inputs — e.g. a
 * blank job name, a non-positive build number, or inputs that cannot be sanitized into the frozen
 * {@code jk-<job>-<build>} shape within STS's constraints.
 */
public class SessionNameException extends CkAwsAuthException {

    private static final long serialVersionUID = 1L;

    public SessionNameException(String message) {
        super(message);
    }
}
