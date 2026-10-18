package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** The strict JSON shapes the model must return, and the small JSON helpers every request body shares. */
final class ReplySchema {
    private ReplySchema() { }

    /** The reply schema with the intent enums narrowed to what this snapshot allows; reply comes first so speech can start early. */
    static JsonObject replyFormat(MobSnapshot snapshot) {
        TradingView view = TradingView.of(snapshot);
        JsonObject properties = new JsonObject(); properties.add("assessment", property("string")); properties.add("reply", property("string"));
        JsonObject social = property("string"); social.add("enum", strings("NONE", "APOLOGY", "KIND", "INSULT", "THREAT")); properties.add("socialIntent", social);
        JsonObject actionFields = new JsonObject(), type = property("string"); type.add("enum", strings("NONE", "FOLLOW", "FLEE", "ATTACK", "STOP"));
        actionFields.add("type", type); actionFields.add("target", property("string"));
        properties.add("action", objectSchema(actionFields, "type", "target"));
        JsonObject bargainFields = new JsonObject(), bargainType = property("string");
        bargainType.add("enum", strings(view.permittedBargainTypes().toArray(String[]::new)));
        bargainFields.add("type", bargainType); bargainFields.add("offerIndex", property("integer")); bargainFields.add("price", property("integer"));
        properties.add("bargain", objectSchema(bargainFields, "type", "offerIndex", "price"));
        JsonObject dealFields = new JsonObject(), dealType = property("string"); dealType.add("enum", strings(view.permittedDealTypes().toArray(String[]::new)));
        dealFields.add("type", dealType); dealFields.add("id", property("string")); dealFields.add("price", property("integer"));
        properties.add("deal", objectSchema(dealFields, "type", "id", "price"));
        properties.add("unlock", property("string"));
        JsonObject questFields = new JsonObject(), questType = property("string"); questType.add("enum", strings(view.permittedQuestTypes().toArray(String[]::new)));
        questFields.add("type", questType); questFields.add("item", property("string")); questFields.add("count", property("integer"));
        questFields.add("title", property("string")); questFields.add("rewardDeal", property("string")); questFields.add("rewardPercent", property("integer"));
        properties.add("quest", objectSchema(questFields, "type", "item", "count", "title", "rewardDeal", "rewardPercent"));
        properties.add("newFacts", property("string"));
        return format("mob_reply", objectSchema(properties, "assessment", "reply", "socialIntent", "action", "bargain", "deal", "unlock", "quest", "newFacts"));
    }

    /** The biography schema: name, story, own desire and secret, and the village when the mob founds one. */
    static JsonObject biographyFormat() {
        JsonObject fields = new JsonObject();
        for (String field : BIOGRAPHY_FIELDS) fields.add(field, property("string"));
        return format("mob_biography", objectSchema(fields, BIOGRAPHY_FIELDS.toArray(String[]::new)));
    }
    static final List<String> BIOGRAPHY_FIELDS = List.of("name", "story", "desire", "secret", "villageName", "villageStory");

    /** For the last correction: every trading intent is forced to NONE by the schema itself. */
    static void forbidIntents(JsonObject body) {
        if (!"json_schema".equals(body.getAsJsonObject("response_format").get("type").getAsString())) return;
        JsonObject properties = body.getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema").getAsJsonObject("properties");
        for (String intent : List.of("bargain", "deal", "quest"))
            properties.getAsJsonObject(intent).getAsJsonObject("properties").getAsJsonObject("type").add("enum", strings("NONE"));
        properties.getAsJsonObject("unlock").add("enum", strings(""));
    }

    /** Providers without structured outputs get json_object plus the schema spelled out in the system prompt. */
    static void useJsonObject(JsonObject body) {
        JsonElement schema = body.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("schema");
        JsonObject format = new JsonObject(); format.addProperty("type", "json_object"); body.add("response_format", format);
        JsonObject system = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        system.addProperty("content", system.get("content").getAsString() + "\nОбязательная схема JSON-ответа: " + schema);
    }

    private static JsonObject format(String name, JsonObject schema) {
        JsonObject jsonSchema = new JsonObject(); jsonSchema.addProperty("name", name); jsonSchema.addProperty("strict", true); jsonSchema.add("schema", schema);
        JsonObject format = new JsonObject(); format.addProperty("type", "json_schema"); format.add("json_schema", jsonSchema); return format;
    }
    static JsonObject objectSchema(JsonObject fields, String... required) { JsonObject schema = property("object"); schema.add("properties", fields); schema.add("required", strings(required)); schema.addProperty("additionalProperties", false); return schema; }
    static JsonObject property(String type) { JsonObject property = new JsonObject(); property.addProperty("type", type); return property; }
    static JsonArray strings(String... values) { JsonArray array = new JsonArray(); for (String value : values) array.add(value); return array; }
    static JsonObject message(String role, String content) { JsonObject message = new JsonObject(); message.addProperty("role", role); message.addProperty("content", content); return message; }
    /** The string field, or "" when absent or not a string. */
    static String optional(JsonObject object, String field) { JsonElement value = object.get(field); return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : ""; }
    /** The string field; a missing or non-string one is a malformed answer. */
    static String string(JsonObject object, String field) { JsonElement value = object.get(field); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(); return value.getAsString(); }
    static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return new java.math.BigDecimal(value.getAsString()).intValueExact();
    }
}
