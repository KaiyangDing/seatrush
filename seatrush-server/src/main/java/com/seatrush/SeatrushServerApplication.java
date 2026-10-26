package com.seatrush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SeatrushServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatrushServerApplication.class, args);
	}

}
