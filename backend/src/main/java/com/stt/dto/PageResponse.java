package com.stt.dto;

import java.util.List;

/** Stable JSON shape for paginated results (Spring's PageImpl serialisation is not a stable contract). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
