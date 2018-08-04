package com.velocitypowered.api.util;

/**
 * Represents where a chat message is going to be sent.
 */
public enum MessagePosition {
    /**
     * The chat message will appear in the client's HUD. These messages can be filtered out by the client.
     */
    CHAT,
    /**
     * The chat message will appear in the client's HUD and can't be dismissed.
     */
    SYSTEM,
    /**
     * The chat message will appear above the player's main HUD. This text format doesn't support many component features,
     * such as hover events.
     */
    ACTION_BAR
}
