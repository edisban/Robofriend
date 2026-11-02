package com.bot.botback.repository;

import com.bot.botback.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    /**
     * Claims today's check-in for the user. Returns 1 only for the first caller of the day, so two
     * open tabs can't both trigger one.
     */
    @Transactional
    @Modifying
    @Query("update User u set u.lastCheckinOn = :today "
            + "where u.id = :userId and (u.lastCheckinOn is null or u.lastCheckinOn < :today)")
    int claimCheckin(@Param("userId") Long userId, @Param("today") LocalDate today);
}
