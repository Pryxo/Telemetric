package dev.telemetric.config;

import com.google.gson.JsonObject;

/** Anything that saves itself into a JSON object. Implementations have to cope with missing or malformed keys. */
public interface JsonSerializable {

    JsonObject toJson();

    void fromJson(JsonObject json);
}
