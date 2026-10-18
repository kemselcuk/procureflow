package com.procureflow.app.requests;

import com.procureflow.app.identity.Actor;
import com.procureflow.app.shared.ApiException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class CommandResults {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    CommandResults(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    RequestData.Result claim(Actor actor, UUID key, String operation, Object canonicalInput, Instant now) {
        final String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(List.of(operation, canonicalInput))));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var inserted = jdbc.update("""
                INSERT INTO api_command (company_id, actor_identity_id, idempotency_key, operation, fingerprint, created_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, actor.companyId(), actor.identityId(), key, operation, fingerprint, Timestamp.from(now));
        if (inserted == 1) return null;
        return jdbc.queryForObject("""
                SELECT operation, fingerprint, response_status, response_json::text FROM api_command
                WHERE company_id = ? AND actor_identity_id = ? AND idempotency_key = ?
                """, (rs, index) -> {
            if (!operation.equals(rs.getString(1)) || !fingerprint.equals(rs.getString(2)))
                throw ApiException.conflict("idempotency_key_reused");
            if (rs.getString(4) == null) throw new IllegalStateException("Committed command has no result");
            return new RequestData.Result(rs.getInt(3), json.readValue(rs.getString(4), RequestData.View.class));
        }, actor.companyId(), actor.identityId(), key);
    }

    RequestData.Result complete(Actor actor, UUID key, int status, RequestData.View view) {
        var changed = jdbc.update("""
                UPDATE api_command SET response_status = ?, response_json = CAST(? AS jsonb)
                WHERE company_id = ? AND actor_identity_id = ? AND idempotency_key = ? AND response_json IS NULL
                """, status, json.writeValueAsString(view), actor.companyId(), actor.identityId(), key);
        if (changed != 1) throw new IllegalStateException("Command result ownership was lost");
        return new RequestData.Result(status, view);
    }
}
