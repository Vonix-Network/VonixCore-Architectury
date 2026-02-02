package network.vonix.vonixcore.fabric;

import net.fabricmc.api.ModInitializer;
import network.vonix.vonixcore.VonixCore;

public final class VonixCoreFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        VonixCore.init();
    }
}
