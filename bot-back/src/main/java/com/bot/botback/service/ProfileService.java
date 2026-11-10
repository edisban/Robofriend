package com.bot.botback.service;

import com.bot.botback.dto.UpdateProfileRequest;
import com.bot.botback.model.User;
import com.bot.botback.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The user's own profile (name and birthday) and deleting their account. */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final LocalDate EARLIEST_BIRTHDAY = LocalDate.of(1900, 1, 1);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);
    /** How far ahead a birthday is mentioned as coming up. */
    private static final int BIRTHDAY_SOON_DAYS = 7;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ProfileService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public User get(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public User update(Long userId, UpdateProfileRequest request) {
        if (request.birthday() != null && request.birthday().isBefore(EARLIEST_BIRTHDAY)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please check your birthday.");
        }
        User user = get(userId);
        String name = request.displayName() == null ? "" : request.displayName().strip();
        user.setDisplayName(name.isEmpty() ? null : name);
        user.setBirthday(request.birthday());
        return userRepository.save(user);
    }

    /**
     * Permanently deletes the account after checking the password. The database removes everything
     * that belongs to it (messages, memories, reminders, lists, sessions) along with it.
     *
     * @return false when the password is wrong; nothing is deleted then
     */
    @Transactional
    public boolean deleteAccount(Long userId, String password) {
        User user = get(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            return false;
        }
        userRepository.delete(user);
        log.info("User {} deleted their account", userId);
        return true;
    }

    /**
     * Lines about the user for RoboFriend's prompts: their name and birthday, with how soon it is,
     * e.g. "Birthday: 24 October 1996 (in 3 days they turn 30)". Empty when nothing is set.
     */
    public static List<String> describe(User user, LocalDate today) {
        List<String> lines = new ArrayList<>();
        if (user.getDisplayName() != null) {
            lines.add("Goes by: " + user.getDisplayName());
        }
        LocalDate birthday = user.getBirthday();
        if (birthday != null) {
            // Feb 29 falls on Feb 28 in other years.
            LocalDate next = MonthDay.from(birthday).atYear(today.getYear());
            if (next.isBefore(today)) {
                next = MonthDay.from(birthday).atYear(today.getYear() + 1);
            }
            long daysAway = ChronoUnit.DAYS.between(today, next);
            int turning = next.getYear() - birthday.getYear();
            String when;
            if (daysAway == 0) {
                when = " (TODAY is their birthday: they turn " + turning + "!)";
            } else if (daysAway == 1) {
                when = " (tomorrow they turn " + turning + ")";
            } else if (daysAway <= BIRTHDAY_SOON_DAYS) {
                when = " (in " + daysAway + " days they turn " + turning + ")";
            } else {
                when = "";
            }
            lines.add("Birthday: " + birthday.format(DAY_MONTH) + " " + birthday.getYear() + when);
        }
        return lines;
    }
}
