package com.bot.botback.service;

import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Reminder;
import com.bot.botback.model.ReminderStatus;
import com.bot.botback.model.Sender;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import com.bot.botback.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The first time a user opens the app each day, RoboFriend looks over what it knows (dated memories,
 * today's reminders, the last conversation) and texts first if something matters today: a birthday,
 * an exam, a follow-up on yesterday. Otherwise it stays quiet.
 */
@Service
public class CheckInService {

    private static final Logger log = LoggerFactory.getLogger(CheckInService.class);
    private static final String SKIP = "SKIP";
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ReminderRepository reminderRepository;
    private final MemoryService memoryService;
    private final GroqClient groqClient;
    private final ListService listService;

    public CheckInService(UserRepository userRepository,
                          ChatMessageRepository chatMessageRepository,
                          ReminderRepository reminderRepository,
                          MemoryService memoryService,
                          GroqClient groqClient,
                          ListService listService) {
        this.userRepository = userRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.reminderRepository = reminderRepository;
        this.memoryService = memoryService;
        this.groqClient = groqClient;
        this.listService = listService;
    }

    /** Returns RoboFriend's opening message for today, already saved to the chat, if it has one. */
    public Optional<ChatMessage> checkIn(Long userId, ZoneId zone) {
        if (!groqClient.isConfigured()) {
            return Optional.empty();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (userRepository.claimCheckin(userId, now.toLocalDate()) == 0) {
            return Optional.empty(); // already checked in today
        }

        String text;
        try {
            text = groqClient.complete(buildPrompt(userId, now), 0.8);
        } catch (Exception e) {
            log.warn("Daily check-in failed for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
        if (text.isBlank() || text.toUpperCase().startsWith(SKIP)) {
            log.info("Daily check-in for user {}: nothing to bring up today", userId);
            return Optional.empty();
        }

        ChatMessage message = new ChatMessage();
        message.setUserId(userId);
        message.setSender(Sender.BOT);
        message.setContent(text);
        log.info("Daily check-in for user {}: sent a message", userId);
        log.debug("Daily check-in text for user {}: {}", userId, text);
        return Optional.of(chatMessageRepository.save(message));
    }

    private String buildPrompt(Long userId, ZonedDateTime now) {
        ZoneId zone = now.getZone();
        LocalDate today = now.toLocalDate();

        List<ChatMessage> recent = new ArrayList<>(chatMessageRepository.findTop13ByUserIdOrderByCreatedAtDesc(userId));
        String lastTalked = recent.isEmpty()
                ? "You have never talked before."
                : "Their last message was " + ago(recent.stream()
                        .filter(m -> m.getSender() == Sender.USER)
                        .map(m -> m.getCreatedAt())
                        .findFirst().orElse(recent.get(0).getCreatedAt()), now) + ".";
        Collections.reverse(recent);
        String conversation = recent.isEmpty()
                ? "(none)"
                : recent.stream()
                        .map(m -> (m.getSender() == Sender.USER ? "Them: " : "You: ") + m.getContent())
                        .collect(Collectors.joining("\n"));

        OffsetDateTime dayStart = today.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayAfterTomorrow = today.plusDays(2).atStartOfDay(zone).toOffsetDateTime();
        List<Reminder> soon = reminderRepository.findByUserIdOrderByScheduledAtAsc(userId).stream()
                .filter(r -> r.getStatus() != ReminderStatus.DONE)
                .filter(r -> !r.getScheduledAt().isBefore(dayStart) && r.getScheduledAt().isBefore(dayAfterTomorrow))
                .toList();
        String reminders = soon.isEmpty()
                ? "None"
                : soon.stream()
                        .map(r -> {
                            ZonedDateTime at = r.getScheduledAt().atZoneSameInstant(zone);
                            String day = at.toLocalDate().equals(today) ? "today" : "tomorrow";
                            return "- " + r.getTitle() + " (" + day + " " + at.format(TIME) + ")";
                        })
                        .collect(Collectors.joining("\n"));

        List<String> profile = new ArrayList<>();
        userRepository.findById(userId).ifPresent(user -> profile.addAll(ProfileService.describe(user, today)));
        memoryService.profileFacts(userId).forEach(m -> profile.add(m.fact()));
        // The name they chose is user-written, so it can't carry the reply marker.
        String stats = ReplySplitter.neutralize(profile.stream()
                .map(line -> "- " + line)
                .collect(Collectors.joining("\n")));
        String lists = listService.openItems(userId).entrySet().stream()
                .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("\n"));
        String memories = memoryService.recentMemories(userId).stream()
                .map(m -> "- " + (m.savedAt() == null ? "" : "[learned " + m.savedAt().atZoneSameInstant(zone).format(DAY) + "] ") + m.fact())
                .collect(Collectors.joining("\n"));

        return """
                You are RoboFriend, the user's close friend. They just opened your chat.
                It is %s (their local time). %s

                Decide whether a good friend would text them first right now about something SPECIFIC
                that matters today:
                - their own birthday today (see their profile below): always wish them a happy birthday
                - an event today or tomorrow (birthday, exam, trip, appointment, party, game, interview)
                - following up on something that happened in the last day or two ("how did the exam go?")
                - their reminders for today/tomorrow
                - open to-dos that seem due or have been waiting a while (mention at most one or two)
                Work out real dates first: time words inside a memory ("tomorrow", "next week") are relative
                to the date you learned it, not to today. Ignore things that are already well in the past.
                If you talked less than 3 hours ago, only text if it's genuinely time-sensitive.

                If there is something: write ONE short, warm, casual message (1-2 sentences) about it, like a
                friend texting. Use the language of their recent messages (Greek or English).
                If there is nothing specific for today: output exactly %s

                Output only the message, or %s.

                Their reminders today and tomorrow:
                %s

                Their current stats and profile:
                %s

                Their open list items:
                %s

                What you remember about them (newest first):
                %s

                Recent conversation (oldest first):
                %s
                """.formatted(now.format(DAY_TIME), lastTalked, SKIP, SKIP, reminders,
                stats.isEmpty() ? "None" : stats,
                lists.isEmpty() ? "None" : lists,
                memories.isEmpty() ? "Nothing yet" : memories,
                conversation);
    }

    private static String ago(OffsetDateTime then, ZonedDateTime now) {
        if (then == null) {
            return "a while ago";
        }
        Duration d = Duration.between(then.toInstant(), now.toInstant());
        if (d.toMinutes() < 60) {
            return d.toMinutes() + " minutes ago";
        }
        if (d.toHours() < 48) {
            return d.toHours() + " hours ago";
        }
        return d.toDays() + " days ago";
    }
}
