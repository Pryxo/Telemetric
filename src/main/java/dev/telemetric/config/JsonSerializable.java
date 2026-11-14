package dev.telemetric.config;

import com.google.gson.JsonObject;

/** Anything that persists itself into a JSON object. Implementations must tolerate missing/malformed keys. */
public interface JsonSerializable {

    JsonObject toJson();

    void fromJson(JsonObject json);
}
