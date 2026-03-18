package com.example.springai.model;

public record DocumentRoutingRequest(String text, String conversationId, String fundCode) {
}