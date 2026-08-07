package com.aura.auth.user;

import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.token.RefreshTokenService;
import com.aura.auth.user.dto.CreateUserRequest;
import com.aura.auth.user.dto.UpdateUserRolesRequest;
import com.aura.auth.user.dto.UpdateUserStatusRequest;
import com.aura.auth.user.dto.UserProfileResponse;
import com.aura.auth.user.exception.LastSuperAdminException;
import com.aura.auth.user.exception.UnknownRoleException;
import com.aura.auth.user.exception.UserAlreadyExistsException;
import com.aura.common.security.Roles;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    private static final String CANONICAL_PHONE = "+989121234567";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private RefreshTokenService refreshTokenService;

    private UserManagementService service;
    private Role userRole;
    private Role adminRole;
    private Role superAdminRole;

    @BeforeEach
    void setUp() {
        service = new UserManagementService(userRepository, roleRepository, refreshTokenService);
        userRole = new Role(1L, Roles.USER, "Storefront customer");
        adminRole = new Role(2L, Roles.ADMIN, "Catalog administrator");
        superAdminRole = new Role(3L, Roles.SUPER_ADMIN, "Full control");
    }

    private User user(long id, Set<Role> roles) {
        return new User(id, null, CANONICAL_PHONE, null, true, null, null, new HashSet<>(roles));
    }

    // --- search --------------------------------------------------------------------------------

    private Pageable pageable() {
        return PageRequest.of(0, 20);
    }

    @Test
    void searchWithNoFiltersPassesNullsThrough() {
        when(userRepository.search(isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(), pageable(), 0));

        service.search(null, null, pageable());

        verify(userRepository).search(null, null, pageable());
    }

    @Test
    void searchWrapsAPhoneQueryInPercentSignsForLike() {
        when(userRepository.search(eq("%0912%"), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(), pageable(), 0));

        service.search("0912", null, pageable());

        verify(userRepository).search("%0912%", null, pageable());
    }

    /**
     * A blank string must be treated the same as "no filter", not passed through as a literal
     * {@code "%%"} pattern that would (harmlessly, but pointlessly) match every row via SQL rather
     * than being skipped by the {@code IS NULL} branch in the query.
     */
    @Test
    void searchTreatsABlankPhoneQueryAsNoFilter() {
        when(userRepository.search(isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(), pageable(), 0));

        service.search("   ", null, pageable());

        verify(userRepository).search(null, null, pageable());
    }

    @Test
    void searchTrimsAndPassesThroughARoleFilter() {
        when(userRepository.search(isNull(), eq(Roles.ADMIN), any()))
            .thenReturn(new PageImpl<>(List.of(), pageable(), 0));

        service.search(null, "  ADMIN  ", pageable());

        verify(userRepository).search(null, Roles.ADMIN, pageable());
    }

    // --- create ------------------------------------------------------------------------------

    @Test
    void createUnionsUserIntoWhateverRolesWereRequested() {
        when(roleRepository.findByName(Roles.USER)).thenReturn(Optional.of(userRole));
        when(roleRepository.findByName(Roles.ADMIN)).thenReturn(Optional.of(adminRole));
        when(userRepository.existsByPhone(CANONICAL_PHONE)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = service.create(new CreateUserRequest("09121234567", Set.of(Roles.ADMIN)));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        Set<String> savedRoleNames = captor.getValue().getRoles().stream().map(Role::getName)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(savedRoleNames).containsExactlyInAnyOrder(Roles.ADMIN, Roles.USER);
        assertThat(response.phone()).isEqualTo(CANONICAL_PHONE);
    }

    @Test
    void createWithNoRolesRequestedStillGetsUser() {
        when(roleRepository.findByName(Roles.USER)).thenReturn(Optional.of(userRole));
        when(userRepository.existsByPhone(CANONICAL_PHONE)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateUserRequest("09121234567", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRoles()).extracting(Role::getName).containsExactly(Roles.USER);
    }

    @Test
    void createRejectsAPhoneThatAlreadyExists() {
        when(userRepository.existsByPhone(CANONICAL_PHONE)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateUserRequest("09121234567", Set.of())))
            .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownRoleName() {
        when(userRepository.existsByPhone(CANONICAL_PHONE)).thenReturn(false);
        // Whether the USER lookup happens before resolveRoles hits the unknown name depends on
        // HashSet iteration order, which the test must not depend on either way - lenient() says
        // so explicitly rather than leaving it to accidentally pass.
        lenient().when(roleRepository.findByName(Roles.USER)).thenReturn(Optional.of(userRole));
        when(roleRepository.findByName("NOT_A_REAL_ROLE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateUserRequest("09121234567", Set.of("NOT_A_REAL_ROLE"))))
            .isInstanceOf(UnknownRoleException.class);

        verify(userRepository, never()).save(any());
    }

    // --- updateRoles ---------------------------------------------------------------------------

    @Test
    void updateRolesReplacesTheSetWholesale() {
        User existing = user(1L, Set.of(userRole, adminRole));
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(roleRepository.findByName(Roles.USER)).thenReturn(Optional.of(userRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateRoles(1L, new UpdateUserRolesRequest(Set.of()));

        // ADMIN must be gone, not merely left alongside whatever was requested - this is a
        // replace, not an additive PATCH.
        assertThat(existing.getRoles()).extracting(Role::getName).containsExactly(Roles.USER);
    }

    /** The core guard: the system must never end up with zero active super admins. */
    @Test
    void updateRolesRejectsStrippingTheOnlyRemainingSuperAdmin() {
        User onlySuperAdmin = user(1L, Set.of(userRole, superAdminRole));
        when(userRepository.findById(1L)).thenReturn(Optional.of(onlySuperAdmin));
        when(userRepository.countByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRoles(1L, new UpdateUserRolesRequest(Set.of(Roles.USER))))
            .isInstanceOf(LastSuperAdminException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateRolesAllowsStrippingSuperAdminWhenAnotherOneRemains() {
        User oneOfTwoSuperAdmins = user(1L, Set.of(userRole, superAdminRole));
        when(userRepository.findById(1L)).thenReturn(Optional.of(oneOfTwoSuperAdmins));
        when(userRepository.countByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(2L);
        when(roleRepository.findByName(Roles.USER)).thenReturn(Optional.of(userRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateRoles(1L, new UpdateUserRolesRequest(Set.of(Roles.USER)));

        assertThat(oneOfTwoSuperAdmins.getRoles()).extracting(Role::getName).containsExactly(Roles.USER);
    }

    @Test
    void updateRolesForANonexistentUserFails() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRoles(99L, new UpdateUserRolesRequest(Set.of())))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- updateStatus --------------------------------------------------------------------------

    @Test
    void deactivatingAUserRevokesEveryRefreshToken() {
        User plainUser = user(1L, Set.of(userRole));
        when(userRepository.findById(1L)).thenReturn(Optional.of(plainUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateStatus(1L, new UpdateUserStatusRequest(false));

        assertThat(plainUser.getIsActive()).isFalse();
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    @Test
    void reactivatingAUserDoesNotTouchRefreshTokens() {
        User plainUser = user(1L, Set.of(userRole));
        plainUser.setIsActive(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(plainUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateStatus(1L, new UpdateUserStatusRequest(true));

        assertThat(plainUser.getIsActive()).isTrue();
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void deactivatingTheOnlyRemainingSuperAdminIsRejected() {
        User onlySuperAdmin = user(1L, Set.of(userRole, superAdminRole));
        when(userRepository.findById(1L)).thenReturn(Optional.of(onlySuperAdmin));
        when(userRepository.countByRoles_Name(Roles.SUPER_ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateStatus(1L, new UpdateUserStatusRequest(false)))
            .isInstanceOf(LastSuperAdminException.class);

        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }
}
