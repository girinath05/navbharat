package com.cts.uam.enums;

/**
 * AuthResultReason - explains why a login attempt succeeded or failed.
 *
 * Used in AuthResult to tell the caller (and the UI) what happened.
 *
 * SUCCESS - login worked, user is now authenticated
 * USER_NOT_FOUND - no account exists with that username
 * ACCOUNT_LOCKED - account is temporarily or permanently locked
 * ACCOUNT_INACTIVE - account exists but is not ACTIVE (e.g. PENDING, DISABLED,
 * TERMINATED)
 * WRONG_PASSWORD - username found but password did not match
 */
public enum AuthResultReason {
    SUCCESS, USER_NOT_FOUND, ACCOUNT_LOCKED, ACCOUNT_INACTIVE, WRONG_PASSWORD
}
