package com.cts.uam.model;

import com.cts.uam.enums.AuthResultReason;

public class AuthResult {
    public final AuthResultReason reason;
    public final User user;

    private AuthResult(AuthResultReason reason, User user) {
        this.reason = reason;
        this.user = user;
    }

    public boolean isSuccess() {
        return reason == AuthResultReason.SUCCESS;
    }

    public static AuthResult success(User u) {
        return new AuthResult(AuthResultReason.SUCCESS, u);
    }

    public static AuthResult failure(AuthResultReason r) {
        return new AuthResult(r, null);
    }

    public static AuthResult wrongPassword(User u) {
        return new AuthResult(AuthResultReason.WRONG_PASSWORD, u);
    }
}
