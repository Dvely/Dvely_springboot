package com.example.dvely;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DvelyApplication {

	public static void main(String[] args) {
		SpringApplication.run(DvelyApplication.class, args);
	}

}
