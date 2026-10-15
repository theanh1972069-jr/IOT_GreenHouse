package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /** Only matches tokens that have neither been revoked nor expired. */
    @Query("""
            select s from UserSession s
            where s.tokenHash = :tokenHash
              and s.revokedAt is null
              and s.expiresAt > :now
            """)
    Optional<UserSession> findActiveByTokenHash(@Param("tokenHash") String tokenHash,
                                                @Param("now") ZonedDateTime now);

    /** True while the user holds at least one live (non-revoked, non-expired) session. */
    boolean existsByUserIdAndRevokedAtIsNullAndExpiresAtAfter(Long userId, ZonedDateTime now);

    Optional<UserSession> findByTokenHash(String tokenHash);

    long countByUserIdAndRevokedAtIsNull(Long userId);

    List<UserSession> findAllByUserIdAndRevokedAtIsNull(Long userId);

    @Modifying
    @Query("""
            delete from UserSession s
            where s.expiresAt < :cutoff or s.revokedAt is not null
            """)
    int deleteStale(@Param("cutoff") ZonedDateTime cutoff);
}
