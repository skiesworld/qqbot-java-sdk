package io.github.skiesworld.qqbot.util;

import io.github.skiesworld.qqbot.event.EventModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringsTest {

    @Test
    void snakeToUpperCamelKeepsDigitBearingAcronyms() {
        assertEquals("GroupOpenid", Strings.snakeToUpperCamel("group_openid"));
        assertEquals("C2CMessageCreate", Strings.snakeToUpperCamel("C2C_MESSAGE_CREATE"));
        assertEquals("GroupAtMessageCreate", Strings.snakeToUpperCamel("GROUP_AT_MESSAGE_CREATE"));
        assertEquals("DmsMessage", Strings.snakeToUpperCamel("dms_message"));
        assertEquals("UploadPartFinish", Strings.snakeToUpperCamel("upload_part_finish"));
        assertEquals("ApiPermissionDemandIdentify",
                Strings.snakeToUpperCamel("API_PERMISSION_DEMAND_IDENTIFY"));
    }

    @Test
    void snakeToLowerCamelForFieldNames() {
        assertEquals("msgType", Strings.snakeToLowerCamel("msg_type"));
        assertEquals("md510m", Strings.snakeToLowerCamel("md5_10m"));
        assertEquals("isWakeup", Strings.snakeToLowerCamel("is_wakeup"));
        assertEquals("", Strings.snakeToLowerCamel("_"));
    }

    @Test
    void eventModelClassNameFollowsTheLookupConvention() {
        assertEquals(EventModels.PACKAGE + "C2CMessageCreate", EventModels.className("C2C_MESSAGE_CREATE"));
        assertEquals(EventModels.PACKAGE + "GroupAtMessageCreate",
                EventModels.className("GROUP_AT_MESSAGE_CREATE"));
        assertEquals(EventModels.PACKAGE + "SubscribeMessageStatus",
                EventModels.className("SUBSCRIBE_MESSAGE_STATUS"));
    }

    @Test
    void hexRoundTripsAndRejectsGarbage() {
        byte[] raw = {0, 15, -1, 127, -128};
        assertEquals("000fff7f80", Strings.hex(raw));
        assertEquals(5, Strings.unhex(Strings.hex(raw)).length);
        assertThrows(IllegalArgumentException.class, () -> Strings.unhex("abc"));
        assertThrows(IllegalArgumentException.class, () -> Strings.unhex("zz01"));
    }

    @Test
    void constantTimeEqualsMatchesOnlyIdenticalValues() {
        assertTrue(Strings.constantTimeEquals("deadbeef", "deadbeef"));
        assertFalse(Strings.constantTimeEquals("deadbeef", "deadbee0"));
        assertFalse(Strings.constantTimeEquals("deadbeef", "deadbee"));
        assertFalse(Strings.constantTimeEquals(null, "x"));
    }

    @Test
    void blankChecksAndPathTail() {
        assertTrue(Strings.isBlank("  "));
        assertFalse(Strings.isNotBlank(""));
        assertEquals("messages", Strings.lastSegment("/v2/users/{user_openid}/messages"));
        String longPath = "/v2/" + "x".repeat(300);
        assertEquals(5 + "...(304 chars)".length(), Strings.trimTail(longPath, 5).length());
        assertEquals("short", Strings.trimTail("short", 5));
        assertThrows(IllegalArgumentException.class, () -> Strings.requireNonBlank(" ", "appId"));
    }
}
