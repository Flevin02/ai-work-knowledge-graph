package com.flevin.knowgraph.server;

import com.flevin.knowgraph.server.config.properties.AiProperties;
import com.flevin.knowgraph.server.config.properties.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.flevin.knowgraph")
@EnableConfigurationProperties({AiProperties.class, RagProperties.class})
@MapperScan("com.flevin.knowgraph.server.repository.mapper")
public class KnowledgeGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeGraphApplication.class, args);
    }
}
