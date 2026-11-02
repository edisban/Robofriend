package com.bot.botback.repository;

import com.bot.botback.model.Reminder;
import com.bot.botback.model.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByUserIdOrderByScheduledAtAsc(Long userId);
    List<Reminder> findByUserIdAndStatusAndScheduledAtBeforeOrderByScheduledAtAsc(Long userId, ReminderStatus status, OffsetDateTime now);
    List<Reminder> findByUserIdAndStatusAndScheduledAtAfterOrderByScheduledAtAsc(Long userId, ReminderStatus status, OffsetDateTime now);
    List<Reminder> findByStatusAndScheduledAtBeforeOrderByScheduledAtAsc(ReminderStatus status, OffsetDateTime now);
}
