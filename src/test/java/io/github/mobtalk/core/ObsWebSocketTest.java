package io.github.mobtalk.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** obs-websocket 5: пароль по примеру из протокола, адрес, разбор событий и ответов о длительности. */
class ObsWebSocketTest {
    @Test void authenticationMatchesTheProtocolExample() {
        assertEquals("1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4=", ObsWebSocket.authentication("supersecretpassword",
                "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=", "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="));
    }

    @Test void addressDefaultsToLocalPort() {
        assertEquals("ws://127.0.0.1:4455", ObsWebSocket.uri("").toString());
        assertEquals("ws://192.168.1.5:4455", ObsWebSocket.uri("192.168.1.5:4455").toString());
        assertThrows(IllegalArgumentException.class, () -> ObsWebSocket.uri("http://x:1"));
    }

    @Test void statesAndReplayArrive() throws Exception {
        List<String> seen = new ArrayList<>();
        ObsWebSocket.Events events = new ObsWebSocket.Events() {
            @Override public void state(String output, boolean active, long elapsed) { seen.add(output + " " + active + " " + elapsed); }
            @Override public void replaySaved(String path) { seen.add("replay " + path); }
            @Override public void status(boolean ok, String text) { seen.add("status " + ok); }
        };
        var constructor = ObsWebSocket.class.getDeclaredConstructor(ObsWebSocket.Events.class, String.class);
        constructor.setAccessible(true);
        ObsWebSocket obs = constructor.newInstance(events, "");
        obs.handle(JsonParser.parseString("{\"op\":5,\"d\":{\"eventType\":\"RecordStateChanged\",\"eventData\":{\"outputActive\":true,\"outputState\":\"OBS_WEBSOCKET_OUTPUT_STARTING\"}}}").getAsJsonObject());
        obs.handle(JsonParser.parseString("{\"op\":5,\"d\":{\"eventType\":\"RecordStateChanged\",\"eventData\":{\"outputActive\":true,\"outputState\":\"OBS_WEBSOCKET_OUTPUT_STARTED\"}}}").getAsJsonObject());
        obs.handle(JsonParser.parseString("{\"op\":7,\"d\":{\"requestType\":\"GetStreamStatus\",\"requestId\":\"1\",\"requestStatus\":{\"result\":true,\"code\":100},\"responseData\":{\"outputActive\":true,\"outputDuration\":125000}}}").getAsJsonObject());
        obs.handle(JsonParser.parseString("{\"op\":7,\"d\":{\"requestType\":\"CreateRecordChapter\",\"requestStatus\":{\"result\":false,\"code\":204}}}").getAsJsonObject());
        obs.handle(JsonParser.parseString("{\"op\":5,\"d\":{\"eventType\":\"ReplayBufferSaved\",\"eventData\":{\"savedReplayPath\":\"C:/Videos/Replay.mkv\"}}}").getAsJsonObject());
        assertEquals(List.of("record true 0", "stream true 125000", "replay C:/Videos/Replay.mkv"), seen);
    }
}
