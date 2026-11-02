package com.bot.botback;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "jwt.secret=test-secret-0123456789-abcdefghijklmnop"
})
class BotBackApplicationTests {

	@Test
	void contextLoads() {
	}

}
