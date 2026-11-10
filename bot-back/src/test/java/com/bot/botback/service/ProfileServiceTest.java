package com.bot.botback.service;

import com.bot.botback.model.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the profile reads to RoboFriend: name, birthday and how soon it is. */
class ProfileServiceTest {

    private static User user(String name, LocalDate birthday) {
        User user = new User();
        user.setDisplayName(name);
        user.setBirthday(birthday);
        return user;
    }

    private static final LocalDate BIRTHDAY = LocalDate.of(1996, 10, 24);

    @Test
    void describesNameAndBirthday() {
        assertEquals(List.of("Goes by: Lena", "Birthday: 24 October 1996"),
                ProfileService.describe(user("Lena", BIRTHDAY), LocalDate.of(2026, 3, 1)));
        assertEquals(List.of(), ProfileService.describe(user(null, null), LocalDate.of(2026, 3, 1)));
    }

    @Test
    void saysWhenTheBirthdayIsTodayOrComingUp() {
        assertEquals("Birthday: 24 October 1996 (TODAY is their birthday: they turn 30!)",
                ProfileService.describe(user(null, BIRTHDAY), LocalDate.of(2026, 10, 24)).get(0));
        assertEquals("Birthday: 24 October 1996 (tomorrow they turn 30)",
                ProfileService.describe(user(null, BIRTHDAY), LocalDate.of(2026, 10, 23)).get(0));
        assertEquals("Birthday: 24 October 1996 (in 7 days they turn 30)",
                ProfileService.describe(user(null, BIRTHDAY), LocalDate.of(2026, 10, 17)).get(0));
        assertEquals("Birthday: 24 October 1996",
                ProfileService.describe(user(null, BIRTHDAY), LocalDate.of(2026, 10, 16)).get(0));
    }

    @Test
    void comingUpAcrossNewYearAndOnLeapDays() {
        assertEquals("Birthday: 2 January 2000 (in 3 days they turn 27)",
                ProfileService.describe(user(null, LocalDate.of(2000, 1, 2)), LocalDate.of(2026, 12, 30)).get(0));
        // Born on 29 February: celebrated on the 28th in other years.
        assertEquals("Birthday: 29 February 2004 (TODAY is their birthday: they turn 22!)",
                ProfileService.describe(user(null, LocalDate.of(2004, 2, 29)), LocalDate.of(2026, 2, 28)).get(0));
    }
}
