package com.acme.shared.payload.gmail;

public record Attachment(
    String filename,
    String contentType,
    String base64
) {}