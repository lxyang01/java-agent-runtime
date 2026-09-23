package io.github.lxyang01.billguard.storage;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lxyang01.agent.util.Json;
import java.sql.SQLException;
import org.postgresql.util.PGobject;

/** JSONB 绑定:Object/JsonNode → PGobject(type=jsonb)。 */
public final class PgJson {

    public static Object value(Object value) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(Json.write(value));
            return object;
        } catch (SQLException e) {
            throw new IllegalArgumentException("cannot bind jsonb value", e);
        }
    }

    public static Object value(JsonNode node) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(node.toString());
            return object;
        } catch (SQLException e) {
            throw new IllegalArgumentException("cannot bind jsonb value", e);
        }
    }

    private PgJson() {}
}
