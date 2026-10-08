package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * A stand-in for the mod list's factory type, declared so this mod can implement it without depending on it.
 *
 * <h2>Why this file is not shipped</h2>
 *
 * <p>The mod list finds a mod's settings screen by instantiating an entrypoint class and checking that it
 * implements its own interface. So the interface has to be named at compile time, and the choice is
 * between depending on the artifact and declaring the two shapes here. Declaring them means the built jar
 * contains no classes belonging to another mod, which matters for more than tidiness: a mod that ships
 * another mod's API classes has to be loaded before it and can shadow its real ones.
 *
 * <p>The build excludes everything under this directory from the jar, so what ships is one class that
 * implements an interface by name and is linked against the real one at run time.
 *
 * <p>The shapes below were read from the mod list's own artifact for the game version this targets, and
 * they can be checked again the same way:
 *
 * <pre>java -cp modmenu-&lt;version&gt;.jar com.terraformersmc.modmenu.api.ModMenuApi</pre>
 *
 * <p>Two properties make this safe. Its interface has no abstract methods — every method is default or
 * static, and this mod overrides exactly one — so a class implementing it needs no more than that one
 * method. And the entrypoint class names the interface rather than extending anything, so it resolves
 * against the real one at run time.
 */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {

    S create(Screen parent);
}
