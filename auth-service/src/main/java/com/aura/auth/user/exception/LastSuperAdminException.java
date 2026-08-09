package com.aura.auth.user.exception;

import com.aura.common.web.error.BusinessRuleException;

/**
 * Would remove the last active {@code SUPER_ADMIN} in the system, whether by stripping the role
 * or by deactivating the account. The control panel has no sign-up, so once that reaches zero
 * there is no path back in short of a manual database edit.
 */
public class LastSuperAdminException extends BusinessRuleException {

    public LastSuperAdminException() {
        super("last-super-admin",
            "This is the last active super admin. Promote another user before removing this one.");
    }
}
