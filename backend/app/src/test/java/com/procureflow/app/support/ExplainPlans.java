package com.procureflow.app.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only recorder for {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} output. It returns a
 * compact summary so plan evidence can be printed and asserted without a database console; the
 * raw JSON stays available for the report.
 */
public final class ExplainPlans {
    private ExplainPlans() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Plan(String topNodeType, List<String> nodeTypes, List<String> indexes, List<String> relations,
                       long actualRows, double totalCost, double executionMs, long sharedHitBlocks, long sharedReadBlocks,
                       String rawJson) {
        public boolean sequentialScan() {
            return nodeTypes.stream().anyMatch(type -> type.equals("Seq Scan"));
        }

        public String summary() {
            return "top=" + topNodeType + " nodes=" + nodeTypes + " indexes=" + indexes + " relations=" + relations
                    + " actualRows=" + actualRows + " cost=" + totalCost + " executionMs=" + executionMs
                    + " sharedHit=" + sharedHitBlocks + " sharedRead=" + sharedReadBlocks;
        }
    }

    public static Plan capture(JdbcTemplate jdbc, String sql, Object... arguments) {
        return parse(jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class, arguments));
    }

    public static Plan parse(String json) {
        var document = JSON.readTree(json);
        var entry = document.get(0);
        var root = entry.path("Plan");
        var nodeTypes = new ArrayList<String>();
        var indexes = new ArrayList<String>();
        var relations = new ArrayList<String>();
        var buffers = new long[2];
        walk(root, nodeTypes, indexes, relations, buffers);
        return new Plan(root.path("Node Type").asText(), List.copyOf(nodeTypes), List.copyOf(indexes),
                List.copyOf(relations), root.path("Actual Rows").asLong(), root.path("Total Cost").asDouble(),
                entry.path("Execution Time").asDouble(), buffers[0], buffers[1], json);
    }

    private static void walk(JsonNode node, List<String> nodeTypes, List<String> indexes, List<String> relations, long[] buffers) {
        if (node == null || node.isMissingNode()) return;
        nodeTypes.add(node.path("Node Type").asText());
        var index = node.path("Index Name").asText();
        if (!index.isEmpty() && !indexes.contains(index)) indexes.add(index);
        var relation = node.path("Relation Name").asText();
        if (!relation.isEmpty() && !relations.contains(relation)) relations.add(relation);
        buffers[0] += node.path("Shared Hit Blocks").asLong();
        buffers[1] += node.path("Shared Read Blocks").asLong();
        for (var child : node.path("Plans")) walk(child, nodeTypes, indexes, relations, buffers);
    }
}
