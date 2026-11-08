package com.dt.digitaltwinsimulator.scenario;

import tools.jackson.databind.JsonNode;
import java.util.regex.Pattern;

final class ScenarioJson {
    // Bound arithmetic in milliseconds before converting scheduling differences to nanoseconds.
    static final long MAX_TIME = Long.MAX_VALUE / 1_000_000;
    private static final Pattern TIME = Pattern.compile("(\\d{2,}):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{3}))?");
    private ScenarioJson() { }
    static boolean has(JsonNode n, String key) { return n != null && n.has(key) && !n.get(key).isNull(); }
    static String text(JsonNode n, String key, String fallback) {
        if (!has(n, key)) return fallback;
        if (!n.get(key).isString()) throw new IllegalArgumentException(key + " must be a string.");
        return n.get(key).asString();
    }
    static boolean bool(JsonNode n, String key, boolean fallback) {
        if (!has(n, key)) return fallback;
        if (!n.get(key).isBoolean()) throw new IllegalArgumentException(key + " must be boolean.");
        return n.get(key).asBoolean();
    }
    static long integer(JsonNode n, String key, long fallback) {
        return has(n, key) ? integer(n.get(key), key) : fallback;
    }
    static long integer(JsonNode n, String label) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong()) throw new IllegalArgumentException(label + " must be an integer.");
        return n.asLong();
    }
    static double speed(JsonNode n, double fallback) {
        if (n == null || n.isNull()) return fallback;
        if (!n.isNumber()) throw new IllegalArgumentException("speed must be numeric.");
        double speed = n.asDouble();
        if (!Double.isFinite(speed) || speed < 0.1 || speed > 100) throw new IllegalArgumentException("speed must be in 0.1..100.");
        return speed;
    }
    static String identifier(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException(label + " must use 1..128 letters, digits, dot, underscore or hyphen.");
        return value;
    }
    static long at(JsonNode value) {
        if (value == null || value.isNull()) throw new IllegalArgumentException("at is required.");
        if (value.isIntegralNumber()) return time(integer(value, "at"));
        if (value.isString()) {
            var match = TIME.matcher(value.asString());
            if (match.matches()) {
                try {
                    return time(Math.addExact(Math.multiplyExact(Long.parseLong(match.group(1)), 3_600_000),
                            Long.parseLong(match.group(2)) * 60_000 + Long.parseLong(match.group(3)) * 1000
                                    + (match.group(4) == null ? 0 : Long.parseLong(match.group(4)))));
                } catch (ArithmeticException | NumberFormatException exception) { /* report below */ }
            }
        }
        throw new IllegalArgumentException("at must be nonnegative integer ms or HH:mm:ss(.SSS).");
    }
    static long time(long value) {
        if (value < 0 || value > MAX_TIME) throw new IllegalArgumentException("Time is outside the supported nonnegative range.");
        return value;
    }
    static void object(JsonNode value, String label) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException(label + " must be an object.");
    }
}
