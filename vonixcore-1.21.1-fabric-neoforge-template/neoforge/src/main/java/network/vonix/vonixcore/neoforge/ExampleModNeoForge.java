package network.vonix.vonixcore.neoforge;

import net.neoforged.fml.common.Mod;

import network.vonix.vonixcore.ExampleMod;

@Mod(ExampleMod.MOD_ID)
public final class ExampleModNeoForge {
    public ExampleModNeoForge() {
        // Run our common setup.
        ExampleMod.init();
    }
}
