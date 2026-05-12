package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapParamsTest {

    @Test
    void shouldExposeKeyValuePairs() {
        var params = new MapParams(Map.of("a", "1", "b", "2"));
        var map = params.asMap();
        assertEquals("1", map.get("a"));
        assertEquals("2", map.get("b"));
    }

    @Test
    void shouldDefensivelyCopySource() {
        var source = new HashMap<String, Object>();
        source.put("a", "1");
        var params = new MapParams(source);

        source.put("b", "2");

        var map = params.asMap();
        assertEquals("1", map.get("a"));
        assertNull(map.get("b"));
    }

    @Test
    void shouldReturnFreshCopyEachCall() {
        var params = new MapParams(Map.of("a", "1"));
        assertNotSame(params.asMap(), params.asMap());
    }

    @Test
    void shouldThrowOnNullSource() {
        assertThrows(TemplateParamException.class, () -> new MapParams(null));
    }
}