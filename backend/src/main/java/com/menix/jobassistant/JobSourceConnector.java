package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** One independently synchronized, attribution-preserving public job source. */
public interface JobSourceConnector {
    String sourceName();
    String attributionUrl();
    List<JsonNode> fetch() throws Exception;
    JobListing normalize(JsonNode item);
    default Instant exactTimestamp(JsonNode item) { return null; }
}
