package com.example.springai.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Service
public class PromptTemplateService {

    private static final Pattern UNRESOLVED_PLACEHOLDER_PATTERN = Pattern.compile("<[a-zA-Z][a-zA-Z0-9_]*>");

    private final ResourceLoader resourceLoader;

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String render(String classpathLocation, Map<String, Object> variables) {
        Resource resource = resourceLoader.getResource("classpath:" + classpathLocation);
        try {
            String template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String rendered = template;

            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                String placeholder = "<" + entry.getKey() + ">";
                String replacement = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                rendered = rendered.replace(placeholder, replacement);
            }

            if (UNRESOLVED_PLACEHOLDER_PATTERN.matcher(rendered).find()) {
                throw new IllegalStateException("Unresolved placeholders in template: " + classpathLocation);
            }
            return rendered;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read prompt template: " + classpathLocation, exception);
        }
    }
}