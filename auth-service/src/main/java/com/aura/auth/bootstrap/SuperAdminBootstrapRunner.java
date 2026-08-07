package com.aura.auth.bootstrap;

import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.user.User;
import com.aura.auth.user.UserRepository;
import com.aura.common.phone.IranianPhoneNumber;
import com.aura.common.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first {@code SUPER_ADMIN} on startup, since the control panel has no sign-up and
 * every other control role has to originate from a user management action taken by someone who
 * already holds one.
 *
 * <p>Runs as an {@link ApplicationRunner} rather than a Flyway migration deliberately: a migration
 * file is version-controlled and shipped in the image, so a password baked into one would be
 * either checked into git or trivially recoverable from any build artifact. This runs once per
 * environment, reads the password from the process environment, hashes it immediately, and never
 * writes the plaintext anywhere — logs included.
 *
 * <p>Idempotent by construction: it checks whether any user already holds {@code SUPER_ADMIN}
 * before doing anything, so it is safe to run on every startup of every instance rather than
 * needing a "ran once" flag of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminBootstrapRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SuperAdminProperties superAdminProperties;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)) {
            log.debug("A super admin already exists; skipping bootstrap.");
            return;
        }

        if (!superAdminProperties.isConfigured()) {
            failOrWarn("""
                No SUPER_ADMIN exists and AURA_SUPERADMIN_PHONE / AURA_SUPERADMIN_PASSWORD are not \
                set. The control panel has no sign-up, so without this there is no way to reach it.""");
            return;
        }

        if (!superAdminProperties.isPasswordStrongEnough()) {
            // Always fatal, in every profile - unlike a missing credential, a too-short one is a
            // configuration mistake, not an intentionally-skipped dev convenience.
            throw new IllegalStateException(
                "AURA_SUPERADMIN_PASSWORD is too short. The bootstrap super admin has unlimited "
                    + "privilege from the moment it exists and requires at least 12 characters.");
        }

        createSuperAdmin();
    }

    private void createSuperAdmin() {
        String phone = IranianPhoneNumber.normalize(superAdminProperties.phone());
        Role superAdminRole = roleRepository.findByName(Roles.SUPER_ADMIN)
            .orElseThrow(() -> new IllegalStateException(
                "Role 'SUPER_ADMIN' not found - has the V1 migration run?"));

        User user = new User();
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(superAdminProperties.password()));
        user.setIsActive(true);
        user.getRoles().add(superAdminRole);
        userRepository.save(user);

        log.info("Created the bootstrap super admin for phone {}. Sign in via the control panel "
            + "and consider rotating this password once you have created a named admin account.",
            maskPhone(phone));
    }

    private void failOrWarn(String message) {
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    /** Never log a full phone number for what is, by construction, a highly privileged account. */
    private String maskPhone(String canonicalPhone) {
        return canonicalPhone.substring(0, canonicalPhone.length() - 4) + "****";
    }
}
