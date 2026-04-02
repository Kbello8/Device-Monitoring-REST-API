package com.example.devicemonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point for the entire Spring Boot application.
 *
 * CONCEPT — Spring Boot Auto-Configuration:
 * Spring Boot is a framework that removes most of the manual setup traditionally
 * required in Java web apps (XML configs, servlet registration, etc.).
 * The @SpringBootApplication annotation below is a shortcut that activates three
 * behaviors at once:
 *   1. @Configuration      — marks this class as a source of Spring "bean" definitions
 *   2. @EnableAutoConfiguration — tells Spring Boot to automatically configure itself
 *                                 based on what's on the classpath (e.g., if H2 is present,
 *                                 set up an in-memory database automatically)
 *   3. @ComponentScan      — scans this package and all sub-packages for classes
 *                            annotated with @Component, @Service, @Repository, etc.,
 *                            and registers them as managed objects ("beans")
 */
@SpringBootApplication
public class DevicemonitorApplication {

	/**
	 * The main method — the standard Java program entry point.
	 *
	 * CONCEPT — SpringApplication.run():
	 * This single line bootstraps the entire application:
	 *   - Starts an embedded Tomcat web server (no separate server install needed)
	 *   - Creates the Spring ApplicationContext (the container that manages all beans)
	 *   - Triggers auto-configuration (sets up H2, JPA, MVC, etc.)
	 *   - Runs any CommandLineRunner beans (like DataSeeder) after startup
	 *
	 * @param args command-line arguments passed when launching the app (unused here)
	 */
	public static void main(String[] args) {
		SpringApplication.run(DevicemonitorApplication.class, args);
	}

}
