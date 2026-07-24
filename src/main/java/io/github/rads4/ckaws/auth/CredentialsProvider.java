package io.github.rads4.ckaws.auth;

/**
 * A source of currently-valid AWS credentials.
 *
 * <p>This is a forward-looking seam, not wired to anything in milestone M1. A later milestone can
 * provide a caching/refreshing implementation that wraps {@link AuthCore} and uses
 * {@link AwsCredentials#expiresWithin} to re-authenticate before expiry — without changing the core.
 * Caching and refresh scheduling are intentionally out of scope for M1.
 */
@FunctionalInterface
public interface CredentialsProvider {

    /**
     * @return valid credentials, re-authenticating if necessary
     * @throws CkAwsAuthException if credentials cannot be obtained
     */
    AwsCredentials get();
}
