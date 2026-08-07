package com.aura.auth.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    Optional<User> findByEmail(String phone);

    /** Whether any user holds the given role — used to check if the super-admin bootstrap has already run. */
    boolean existsByRoles_Name(String roleName);

    /**
     * How many users hold the given role — used to guard against stripping the last
     * {@code SUPER_ADMIN} from the system, which would be a permanent, silent lockout since the
     * control panel has no sign-up and no recovery path of its own.
     */
    long countByRoles_Name(String roleName);

    /**
     * Both filters are optional so one query covers "list everyone", "search by phone", "filter by
     * role", and both at once, rather than branching in the service layer between several queries.
     * {@code DISTINCT} matters here: joining to a multi-valued collection duplicates the parent row
     * once per match, which would otherwise return the same user twice for someone holding two roles.
     *
     * <p>{@code phonePattern} is the caller's job to wrap in {@code %...%}, not
     * {@code CONCAT('%', :phone, '%')} here. With a null parameter, Postgres cannot infer a type
     * for a bind variable used only inside {@code CONCAT} and falls back to {@code bytea}, which
     * {@code LIKE} then refuses to compare against a {@code varchar} column — confirmed against a
     * live database: every unfiltered listing failed with "operator does not exist: character
     * varying ~~ bytea". Passing the whole pattern (or {@code null}) as one parameter avoids the
     * ambiguity outright.
     */
    @Query("""
        SELECT DISTINCT u FROM User u LEFT JOIN u.roles r
        WHERE (:phonePattern IS NULL OR u.phone LIKE :phonePattern)
        AND (:role IS NULL OR r.name = :role)
        """)
    Page<User> search(@Param("phonePattern") String phonePattern, @Param("role") String role, Pageable pageable);
}