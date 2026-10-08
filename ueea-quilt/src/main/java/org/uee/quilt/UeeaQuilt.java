package org.uee.quilt;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import org.uee.Ueea;
import org.uee.mc.UeeaCommand;

/**
 * The Quilt entry point for the analysis half.
 *
 * <p>Written against the same entry points the exporter's Quilt build uses, which are the Fabric ones: this
 * loader runs them, and reaching for a different set would add a dependency the other loaders do not have.
 */
public final class UeeaQuilt implements ModInitializer {

    @Override
    public void onInitialize() {
        Ueea.bind(new QuiltAdapter());
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> UeeaCommand.register(dispatcher));
    }
}
