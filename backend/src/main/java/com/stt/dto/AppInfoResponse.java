package com.stt.dto;

/** Which transcription engines are available on this deployment. */
public record AppInfoResponse(boolean geminiEnabled, String geminiModel, int maxSessionSeconds) {
}
