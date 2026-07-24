package io.github.rads4.ckaws.auth;

/**
 * Base type for all authentication-core failures.
 *
 * <p>Unchecked on purpose (milestone M1 decision): the auth core does not force callers to handle
 * failures. The future Jenkins integration layer decides how to surface them (fail the build with an
 * actionable message, etc.) rather than having that policy baked in here.
 */
public class CkAwsAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CkAwsAuthException(String message) {
        super(message);
    }

    public CkAwsAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
