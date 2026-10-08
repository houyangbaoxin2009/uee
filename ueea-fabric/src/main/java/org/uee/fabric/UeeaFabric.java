package org.uee.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import org.uee.Ueea;
import org.uee.mc.UeeaCommand;

/**
 * The Fabric entry point for the analysis half.
 *
 * <p>It binds an adapter and registers a command, and that is the whole of it. The adapter is the same class
 * the exporter's build compiles, because it is mod-agnostic now -- it collects, and the two things it used to
 * reach for are asked of whoever can answer. This mod answers neither, so its defaults stand: a failure goes
 * to the error stream, and no declaration tables are read.
 */
public final class UeeaFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Ueea.bind(new FabricAdapter());
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> UeeaCommand.register(dispatcher));
    }
}
