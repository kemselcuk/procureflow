package com.procureflow.app.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Maps the {@code text[]}/{@code uuid[]} aggregates of the bulk membership/actor queries. Keeping
 * the mapping in one place guarantees that the admin list and the current-actor snapshot decode
 * roles and managed departments identically.
 */
final class AssignmentSets {
    private AssignmentSets() {}

    static Set<Role> roles(ResultSet rows, String column) throws SQLException {
        var values = (Object[]) rows.getArray(column).getArray();
        var roles = new HashSet<Role>(values.length);
        for (var value : values) roles.add(Role.valueOf(value.toString()));
        return roles;
    }

    static Set<UUID> departments(ResultSet rows, String column) throws SQLException {
        var values = (Object[]) rows.getArray(column).getArray();
        var departments = new HashSet<UUID>(values.length);
        for (var value : values) {
            departments.add(value instanceof UUID id ? id : UUID.fromString(value.toString()));
        }
        return departments;
    }
}
