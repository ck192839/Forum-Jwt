package com.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
		classes = MyProjectBackendApplicationTests.SmokeTestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MyProjectBackendApplicationTests {

	@Test
	void contextLoadsWithoutExternalServices() {
	}

	@SpringBootConfiguration
	static class SmokeTestApplication {
	}
}
