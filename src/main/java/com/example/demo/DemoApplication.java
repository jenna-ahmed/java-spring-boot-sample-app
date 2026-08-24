package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
                System.out.println("cache test");
		System.out.println(">>> RUNNING VERSION 2 <<<");
		SpringApplication.run(DemoApplication.class, args);
	}

}
