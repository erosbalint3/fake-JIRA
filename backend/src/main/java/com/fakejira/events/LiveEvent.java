package com.fakejira.events;

import java.util.Map;
import java.util.Set;

/**
 * A change pushed to connected browsers over server-sent events.
 * Published inside a transaction and delivered only after it commits.
 */
public record LiveEvent(Set<Long> recipients, String type, Map<String, Object> data) {
}
