package com.aura.auth.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByUserIdOrderByIsDefaultDescCreatedAtDesc(Long userId);

    /**
     * Always scoped by {@code userId}, never a bare {@code findById}. Looking an address up by id
     * alone and checking ownership afterwards is how one forgotten check turns into an IDOR that
     * leaks other people's home addresses.
     */
    Optional<Address> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    Optional<Address> findByUserIdAndIsDefaultTrue(Long userId);

    /**
     * Clears the existing default before a new one is set. Required because the database enforces
     * at most one default per user via a partial unique index — without demoting the incumbent
     * first, promoting a second address fails on that constraint.
     */
    @Modifying
    @Query("UPDATE Address a SET a.isDefault = false WHERE a.userId = :userId AND a.isDefault = true")
    void clearDefaultFor(@Param("userId") Long userId);
}
