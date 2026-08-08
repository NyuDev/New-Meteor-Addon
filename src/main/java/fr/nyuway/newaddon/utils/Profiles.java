package fr.nyuway.newaddon.utils;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

/**
 * Reads a player's id off their profile.
 *
 * <p>The fourth per-version split in this addon, and the least obvious: {@code GameProfile}
 * became a record in authlib 7, so {@code getId()} turned into {@code id()}. Authlib 6 still
 * has the old name, and Minecraft moved from one to the other at 1.21.10 - which is why the
 * cut is there and not at a version boundary that looks more natural.
 *
 * <p>Kept here so the modules ask a plain question and never learn that any of this happened.
 */
public final class Profiles {

    private Profiles() {
    }

    public static UUID idOf(GameProfile profile) {
        //? if >=1.21.10 {
        return profile.id();
        //?} else {
        /*return profile.getId();
        *///?}
    }
}
