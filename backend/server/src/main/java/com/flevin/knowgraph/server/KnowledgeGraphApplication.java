package com.flevin.knowgraph.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.flevin.knowgraph")
public class KnowledgeGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeGraphApplication.class, args);
    }
}
