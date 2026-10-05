package org.uee.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;

/**
 * Runs a datapack function by resource id.
 *
 * <p><b>Compile-unverified.</b> Along with the rest of the MC layer, this has not been through a
 * compiler.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A flow can be defined as a configuration document or as an MC function. The function form is
 * not interpreted by UEE — the game already has a function runner, and a second one written here
 * would not support {@code execute}, {@code data}, macros or anything else a pack might reasonably
 * use. All UEE needs is to route a flow name to the game's own runner.
 *
 * <p>Kept in one method so the loader-specific call is the only thing that has to be adjusted against
 * a real toolchain.
 */
public final class McFunctions {

    private McFunctions() {
    }

    /**
     * Runs a function, as if the caller had typed {@code /function <id>}.
     *
     * @param source where the output goes and what permissions apply
     * @param resourceId the function's resource path, e.g. {@code mypack:uee/export}
     * @throws IllegalArgumentException when the id is malformed or no such function exists
     */
    public static void run(CommandSourceStack source, String resourceId) {
        ResourceLocation id = ResourceLocation.tryParse(resourceId);
        if (id == null) {
            throw new IllegalArgumentException("'" + resourceId + "' is not a valid function id");
        }
        var server = source.getServer();
        var function = server.getFunctions().get(id);
        if (function.isEmpty()) {
            // Distinguished from "the function threw": a missing function is almost always a typo in
            // the pack, and saying so is more useful than a generic failure.
            throw new IllegalArgumentException("no function at " + resourceId);
        }
        server.getFunctions().execute(function.get(), source);
    }
}
