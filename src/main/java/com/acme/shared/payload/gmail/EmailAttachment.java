package com.acme.shared.payload.gmail;

public record EmailAttachment(
    String filename,
    String contentType,
    byte[] data
) {}