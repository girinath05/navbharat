package com.cts.uam.model;

import com.cts.uam.enums.AuthResultReason;

/**
 * AuthResult - the outcome of a login attempt.
 *
 * Returned by UserService.authenticate().
 * Holds whether login succeeded (isSuccess()) and the reason if it failed.
 *
 * On success: reason = SUCCESS, user = the logged-in User object
 * On failure: reason = one of the failure codes, user = null
 * (except WRONG_PASSWORD which still carries the user for failed-attempt
 * tracking)
 *
 * Use the static factory methods to create instances - constructor is private.
 */
public class AuthResult {

    // Why the login succeeded or failed
    public final AuthResultReason reason;

    // The user who tried to log in (null on most failures)
    public final User user;

    // Private - use the static factory methods below
    private AuthResult(AuthResultReason reason, User user) {
        this.reason = reason;
        this.user = user;
    }

    // Returns true only when the login was fully successful
    public boolean isSuccess() {
        return reason == AuthResultReason.SUCCESS;
    }

    // Factory: login succeeded - user is now authenticated
    public static AuthResult success(User u) {
        return new AuthResult(AuthResultReason.SUCCESS, u);
    }

    // Factory: login failed for reasons that don't involve the user object
    // (e.g. user not found, account inactive, account locked)
    public static AuthResult failure(AuthResultReason r) {
        return new AuthResult(r, null);
    }

    // Factory: wrong password - user is still attached so we can increment failed
    // attempts
    public static AuthResult wrongPassword(User u) {
        return new AuthResult(AuthResultReason.WRONG_PASSWORD, u);
    }
}
