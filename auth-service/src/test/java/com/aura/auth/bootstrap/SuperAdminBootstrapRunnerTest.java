package com.aura.auth.bootstrap;

import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.user.User;
import com.aura.auth.user.UserRepository;
import com.aura.common.security.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuperAdminBootstrapRunnerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private Environment environment;

    private SuperAdminBootstrapRunner runner(SuperAdminProperties properties) {
        return new SuperAdminBootstrapRunner(userRepository, roleRepository, passwordEncoder, properties, environment);
    }

    @Test
    void doesNothingWhenASuperAdminAlreadyExists() {
        when(userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(true);
        SuperAdminBootstrapRunner runner = runner(new SuperAdminProperties("+989000000000", "irrelevant-1234"));

        runner.run(null);

        verify(userRepository, never()).save(any());
        // Must not even ask about the profile - an existing admin makes the question moot, and
        // asking anyway would be a pointless call on every single startup.
        verifyNoInteractions(environment);
    }

    @Test
    void inDevWithNoCredentialsConfiguredItWarnsAndSkips() {
        when(userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(false);
        when(environment.matchesProfiles("prod")).thenReturn(false);
        SuperAdminBootstrapRunner runner = runner(new SuperAdminProperties(null, null));

        runner.run(null);

        verify(userRepository, never()).save(any());
    }

    /**
     * The one case that must never be silent: shipping to production with no way to reach the
     * control panel at all.
     */
    @Test
    void inProdWithNoCredentialsConfiguredItFailsStartup() {
        when(userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(false);
        when(environment.matchesProfiles("prod")).thenReturn(true);
        SuperAdminBootstrapRunner runner = runner(new SuperAdminProperties(null, null));

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * A short password is a configuration mistake, not an intentionally-skipped dev convenience -
     * it fails in every profile, not just prod.
     */
    @Test
    void aTooShortPasswordFailsStartupEvenOutsideProd() {
        when(userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(false);
        SuperAdminBootstrapRunner runner = runner(new SuperAdminProperties("+989000000000", "short"));

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(environment);
    }

    @Test
    void createsAnActiveSuperAdminWithAnEncodedPassword() {
        when(userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(false);
        Role superAdminRole = new Role(1L, Roles.SUPER_ADMIN, "Full control");
        Role userRole = new Role(2L, Roles.USER, "Baseline");
        when(roleRepository.findByName(Roles.SUPER_ADMIN)).thenReturn(Optional.of(superAdminRole));
        when(roleRepository.findByName(Roles.USER)).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("a-strong-password-1234")).thenReturn("encoded-hash");
        SuperAdminBootstrapRunner runner = runner(
            new SuperAdminProperties("09120000000", "a-strong-password-1234"));

        runner.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User created = captor.getValue();

        // Persisted with the canonical form, same as every other user - typed-input normalization
        // applies here too, not just to self-service sign-up.
        assertThat(created.getPhone()).isEqualTo("+989120000000");
        assertThat(created.getPassword()).isEqualTo("encoded-hash");
        assertThat(created.getIsActive()).isTrue();
        // USER as well as SUPER_ADMIN, through the same baseline rule every other account uses.
        // Without it this is the one account in the system that cannot sign in to the storefront,
        // and the single exception to "every account has USER" for anything that assumes it.
        assertThat(created.getRoles()).containsExactlyInAnyOrder(superAdminRole, userRole);
    }

    @Test
    void neverPersistsThePlaintextPassword() {
        when(userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(false);
        when(roleRepository.findByName(Roles.SUPER_ADMIN))
            .thenReturn(Optional.of(new Role(1L, Roles.SUPER_ADMIN, "Full control")));
        when(roleRepository.findByName(Roles.USER))
            .thenReturn(Optional.of(new Role(2L, Roles.USER, "Baseline")));
        when(passwordEncoder.encode(any())).thenReturn("encoded-hash");
        SuperAdminBootstrapRunner runner = runner(
            new SuperAdminProperties("+989000000000", "a-strong-password-1234"));

        runner.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isNotEqualTo("a-strong-password-1234");
    }

    @Test
    void failsFastIfTheSuperAdminRoleWasNeverSeeded() {
        when(userRepository.existsByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(false);
        when(roleRepository.findByName(Roles.SUPER_ADMIN)).thenReturn(Optional.empty());
        SuperAdminBootstrapRunner runner = runner(
            new SuperAdminProperties("+989000000000", "a-strong-password-1234"));

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
    }
}
