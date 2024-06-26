package com.fce4.dtrtoolkit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;

import java.text.SimpleDateFormat;
import java.util.Date;

@SpringBootApplication
@ComponentScan("com.fce4.dtrtoolkit.Validators")
@ComponentScan("com.fce4.dtrtoolkit.Extractors")
@ComponentScan("com.fce4.dtrtoolkit.Controllers")
@ComponentScan("com.fce4.dtrtoolkit.Taxonomies")
@ComponentScan("com.fce4.dtrtoolkit")
@EnableScheduling
@OpenAPIDefinition(servers = {@Server(url ="/", description = "Default Server URL")}, info = @Info(title = "TypeAPI",
		version = "0.0", description = "Supporting functionalities for the Typeregistry."))
public class TypeApplication {
	public static void main(String[] args) {
		Date currentDate = new Date(System.currentTimeMillis());
		SimpleDateFormat df = new SimpleDateFormat("yyyy-MMM");
		System.setProperty("timestamp", df.format(currentDate));
		SpringApplication.run(TypeApplication.class, args);
	}
}
