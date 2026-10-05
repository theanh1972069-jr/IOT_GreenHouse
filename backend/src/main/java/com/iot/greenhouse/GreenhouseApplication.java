package com.iot.greenhouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class GreenhouseApplication {

	public static void main(String[] args) {
		SpringApplication.run(GreenhouseApplication.class, args);
	}

}
