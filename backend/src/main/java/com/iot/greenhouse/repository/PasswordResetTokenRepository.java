package com.iot.greenhouse.repository;

import com.iot.greenhouse.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from PasswordResetToken token where token.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("""
            update PasswordResetToken token
            set token.usedAt = :now
            where token.user.id = :userId
              and token.usedAt is null
            """)
    int invalidateUnusedForUser(@Param("userId") Long userId, @Param("now") ZonedDateTime now);

    @Modifying
    @Query("delete from PasswordResetToken token where token.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") ZonedDateTime cutoff);
}
