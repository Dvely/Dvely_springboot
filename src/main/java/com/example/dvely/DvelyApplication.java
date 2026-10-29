package com.example.dvely;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 기본 사용자 자동 구성을 끈다. 인증은 JWT·GitHub OAuth 뿐이고 폼·Basic 로그인이 없어 쓰지 않는 사용자인데,
// 켜 두면 기동할 때마다 비밀번호를 하나 만들어 로그에 찍는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class DvelyApplication {

	public static void main(String[] args) {
		SpringApplication.run(DvelyApplication.class, args);
	}

}
