-- Every account holds USER. Backfilled here because the code change alone does not reach accounts
-- that already exist.
--
-- The bootstrap super admin is the case that matters: the runner only creates an account when no
-- SUPER_ADMIN exists, so an already-deployed environment keeps the account it made before USER was
-- part of the rule. That account then cannot sign in to the storefront at all, and is the single
-- exception to "every account has USER" for any code that assumes it.
--
-- USER is a baseline, not a privilege: it grants storefront access and nothing else, and control
-- endpoints are gated by the aud=control audience plus a control role rather than by its absence.
-- So granting it to accounts that lack it takes nothing away and adds no reachable permission.
--
-- Idempotent by construction - the anti-join means re-running inserts nothing.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
CROSS JOIN roles r
WHERE r.name = 'USER'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur
      WHERE ur.user_id = u.id AND ur.role_id = r.id
  );
