package com.aura.auth.user;

import com.aura.auth.user.dto.CreateUserRequest;
import com.aura.auth.user.dto.UpdateUserRolesRequest;
import com.aura.auth.user.dto.UpdateUserStatusRequest;
import com.aura.auth.user.dto.UserProfileResponse;
import com.aura.common.security.SuperAdminOnly;
import com.aura.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Requirement 2: user provisioning, restricted to {@code SUPER_ADMIN}.
 *
 * <p>{@link SuperAdminOnly} checks both that the token was minted for the control panel at all and
 * that it specifically belongs to a super admin, not a seller or a plain admin — neither check
 * implies the other, since a control-audience token only proves the holder cleared <em>some</em>
 * control-role check at login.
 */
@RestController
@RequestMapping("/api/auth/control/users")
@RequiredArgsConstructor
@SuperAdminOnly
public class UserManagementController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserManagementService userManagementService;

    @GetMapping
    public ResponseEntity<PageResponse<UserProfileResponse>> search(
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String role,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), boundedSize, Sort.by("createdAt").descending());
        return ResponseEntity.ok(PageResponse.of(userManagementService.search(phone, role, pageable)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> getById(@PathVariable long userId) {
        return ResponseEntity.ok(userManagementService.getById(userId));
    }

    @PostMapping
    public ResponseEntity<UserProfileResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserProfileResponse created = userManagementService.create(request);
        return ResponseEntity
            .created(URI.create("/api/auth/control/users/" + created.id()))
            .body(created);
    }

    @PutMapping("/{userId}/roles")
    public ResponseEntity<UserProfileResponse> updateRoles(
        @PathVariable long userId,
        @Valid @RequestBody UpdateUserRolesRequest request
    ) {
        return ResponseEntity.ok(userManagementService.updateRoles(userId, request));
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<UserProfileResponse> updateStatus(
        @PathVariable long userId,
        @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return ResponseEntity.ok(userManagementService.updateStatus(userId, request));
    }
}
