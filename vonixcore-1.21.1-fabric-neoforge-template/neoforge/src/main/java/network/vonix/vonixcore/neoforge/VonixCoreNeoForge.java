package network.vonix.vonixcore.neoforge;

// import dev.architectury.platform.neoforge.EventBuses;
import network.vonix.vonixcore.VonixCore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(VonixCore.MODID)
public class VonixCoreNeoForge {
    public VonixCoreNeoForge(IEventBus modBus) {
        // EventBuses.registerModEventBus(VonixCore.MODID, modBus);
        VonixCore.init();
    }
}