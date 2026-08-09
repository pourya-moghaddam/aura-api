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
import com.aura.common.phone.IranianPhoneNumber;
import com.aura.common.security.Roles;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User provisioning for the control panel: requirement 2, "the super admin can create users."
 *
 * <p>Deliberately a separate service from {@link UserService} rather than more methods bolted onto
 * it. The two have almost no logic in common — this is CRUD over an admin-facing resource, that is
 * OTP and session lifecycle — and {@code UserService} was already carrying a lot before this.
 */
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;

    @Transactional(readOnly = true)
    public Page<UserProfileResponse> search(String phoneQuery, String roleFilter, Pageable pageable) {
        String phonePattern = StringUtils.hasText(phoneQuery) ? "%" + phoneQuery.trim() + "%" : null;
        String normalizedRoleFilter = StringUtils.hasText(roleFilter) ? roleFilter.trim() : null;
        return userRepository.search(phonePattern, normalizedRoleFilter, pageable)
            .map(this::toProfileResponse);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getById(long userId) {
        return toProfileResponse(requireUser(userId));
    }

    /**
     * No password is set here. The provisioned account signs in the same way every other account
     * does — OTP first, self-service password afterward — this endpoint's only job is to exist
     * with the right phone number and roles before that first login.
     */
    @Transactional
    public UserProfileResponse create(CreateUserRequest request) {
        String phone = IranianPhoneNumber.normalize(request.phone());
        if (userRepository.existsByPhone(phone)) {
            throw new UserAlreadyExistsException("A user with this phone number already exists.");
        }

        User user = new User();
        user.setPhone(phone);
        user.setIsActive(true);
        user.getRoles().addAll(resolveRoles(unionWithUser(request.roles())));

        return toProfileResponse(userRepository.save(user));
    }

    @Transactional
    public UserProfileResponse updateRoles(long userId, UpdateUserRolesRequest request) {
        User user = requireUser(userId);
        Set<String> requestedRoleNames = unionWithUser(request.roles());

        guardAgainstStrippingTheLastSuperAdmin(user, requestedRoleNames);

        user.getRoles().clear();
        user.getRoles().addAll(resolveRoles(requestedRoleNames));

        return toProfileResponse(userRepository.save(user));
    }

    /**
     * Deactivation also revokes every refresh token the user holds. The access token they are
     * mid-request with cannot be recalled — that is the accepted cost of a stateless 15-minute
     * token — but without this they could keep refreshing past that window indefinitely on an
     * account an admin just tried to shut off.
     */
    @Transactional
    public UserProfileResponse updateStatus(long userId, UpdateUserStatusRequest request) {
        User user = requireUser(userId);

        if (!request.active() && hasRole(user, Roles.SUPER_ADMIN)
            && userRepository.countByRoles_Name(Roles.SUPER_ADMIN) <= 1) {
            throw new LastSuperAdminException();
        }

        user.setIsActive(request.active());
        User saved = userRepository.save(user);

        if (!request.active()) {
            refreshTokenService.revokeAllForUser(userId);
        }

        return toProfileResponse(saved);
    }

    private void guardAgainstStrippingTheLastSuperAdmin(User user, Set<String> requestedRoleNames) {
        boolean losingSuperAdmin = hasRole(user, Roles.SUPER_ADMIN)
            && !requestedRoleNames.contains(Roles.SUPER_ADMIN);

        if (losingSuperAdmin && userRepository.countByRoles_Name(Roles.SUPER_ADMIN) <= 1) {
            throw new LastSuperAdminException();
        }
    }

    private Set<String> unionWithUser(Set<String> roleNames) {
        Set<String> union = new HashSet<>(roleNames);
        union.add(Roles.USER);
        return union;
    }

    private Set<Role> resolveRoles(Set<String> roleNames) {
        return roleNames.stream()
            .map(name -> roleRepository.findByName(name).orElseThrow(() -> new UnknownRoleException(name)))
            .collect(Collectors.toSet());
    }

    private boolean hasRole(User user, String roleName) {
        return user.getRoles().stream().map(Role::getName).anyMatch(roleName::equals);
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
    }

    private UserProfileResponse toProfileResponse(User user) {
        Set<String> roleNames = user.getRoles().stream()
            .map(Role::getName)
            .collect(Collectors.toSet());

        return new UserProfileResponse(
            user.getId(),
            user.getPhone(),
            user.getEmail(),
            user.getIsActive(),
            user.getPassword() != null,
            roleNames,
            user.getCreatedAt()
        );
    }
}
