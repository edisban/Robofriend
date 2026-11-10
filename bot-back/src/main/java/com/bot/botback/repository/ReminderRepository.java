package com.bot.botback.repository;

import com.bot.botback.model.Reminder;
import com.bot.botback.model.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    /**
     * Claims a due reminder for delivery by moving it from PENDING to SENT. Returns 1 only for the
     * one caller that won, so two backend instances (or overlapping runs) can't both deliver it.
     */
    @Transactional
    @Modifying
    @Query("update Reminder r set r.status = com.bot.botback.model.ReminderStatus.SENT "
            + "where r.id = :id and r.status = com.bot.botback.model.ReminderStatus.PENDING")
    int claimForDelivery(@Param("id") Long id);

    List<Reminder> findByUserIdOrderByScheduledAtAsc(Long userId);
    List<Reminder> findByUserIdAndStatusAndScheduledAtBeforeOrderByScheduledAtAsc(Long userId, ReminderStatus status, OffsetDateTime now);
    List<Reminder> findByUserIdAndStatusAndScheduledAtAfterOrderByScheduledAtAsc(Long userId, ReminderStatus status, OffsetDateTime now);
    List<Reminder> findByStatusAndScheduledAtBeforeOrderByScheduledAtAsc(ReminderStatus status, OffsetDateTime now);
}
