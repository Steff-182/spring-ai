package com.example.embedding;

import ai.djl.engine.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EmbeddingServiceApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingServiceApplication.class);

    public static void main(String[] args) {
        // Prevent OrtEnvironment ordering issues by forcing DJL ONNX init first.
        try {
            Engine.getEngine("OnnxRuntime");
            LOGGER.info("DJL engine initialized: OnnxRuntime");
        }
        catch (Exception exception) {
            LOGGER.warn("Unable to pre-initialize OnnxRuntime engine", exception);
        }

        SpringApplication.run(EmbeddingServiceApplication.class, args);
    }
}
