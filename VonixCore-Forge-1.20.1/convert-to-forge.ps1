# Convert NeoForge 1.21.x imports to Forge 1.20.1 imports - Extended Version
$files = Get-ChildItem -Path "c:\Users\rwcoo\Developement\VonixCore\VonixCore-Forge-1.20.1\src\main\java" -Filter "*.java" -Recurse

foreach ($file in $files) {
    $content = Get-Content $file.FullName -Raw
    
    # Replace NeoForge imports with Forge equivalents
    $content = $content -replace 'import net\.neoforged\.bus\.api\.IEventBus;', 'import net.minecraftforge.eventbus.api.IEventBus;'
    $content = $content -replace 'import net\.neoforged\.bus\.api\.SubscribeEvent;', 'import net.minecraftforge.eventbus.api.SubscribeEvent;'
    $content = $content -replace 'import net\.neoforged\.bus\.api\.EventPriority;', 'import net.minecraftforge.eventbus.api.EventPriority;'
    $content = $content -replace 'import net\.neoforged\.fml\.ModContainer;', ''
    $content = $content -replace 'import net\.neoforged\.fml\.common\.Mod;', 'import net.minecraftforge.fml.common.Mod;'
    $content = $content -replace 'import net\.neoforged\.fml\.common\.EventBusSubscriber;', 'import net.minecraftforge.fml.common.Mod.EventBusSubscriber;'
    $content = $content -replace 'import net\.neoforged\.fml\.config\.ModConfig;', 'import net.minecraftforge.fml.config.ModConfig;'
    $content = $content -replace 'import net\.neoforged\.fml\.event\.lifecycle\.FMLCommonSetupEvent;', 'import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;'
    $content = $content -replace 'import net\.neoforged\.fml\.event\.lifecycle\.FMLClientSetupEvent;', 'import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;'
    $content = $content -replace 'import net\.neoforged\.fml\.event\.config\.ModConfigEvent;', 'import net.minecraftforge.fml.event.config.ModConfigEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.common\.NeoForge;', 'import net.minecraftforge.common.MinecraftForge;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.common\.ModConfigSpec;', 'import net.minecraftforge.common.ForgeConfigSpec;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.server\.ServerStartedEvent;', 'import net.minecraftforge.event.server.ServerStartedEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.server\.ServerStartingEvent;', 'import net.minecraftforge.event.server.ServerStartingEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.server\.ServerStoppingEvent;', 'import net.minecraftforge.event.server.ServerStoppingEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.ServerChatEvent;', 'import net.minecraftforge.event.ServerChatEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.CommandEvent;', 'import net.minecraftforge.event.CommandEvent;'
    
    # Player events
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.entity\.player\.PlayerEvent;', 'import net.minecraftforge.event.entity.player.PlayerEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.entity\.player\.AdvancementEvent;', 'import net.minecraftforge.event.entity.player.AdvancementEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.entity\.player\.PlayerInteractEvent;', 'import net.minecraftforge.event.entity.player.PlayerInteractEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.entity\.player\.ItemEntityPickupEvent;', 'import net.minecraftforge.event.entity.player.EntityItemPickupEvent;'
    
    # Entity events
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.entity\.living\.LivingDeathEvent;', 'import net.minecraftforge.event.entity.living.LivingDeathEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.entity\.item\.ItemTossEvent;', 'import net.minecraftforge.event.entity.item.ItemTossEvent;'
    
    # Tick events
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.tick\.PlayerTickEvent;', 'import net.minecraftforge.event.TickEvent;'
    
    # Level/World events
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.level\.BlockEvent;', 'import net.minecraftforge.event.level.BlockEvent;'
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.level\.ExplosionEvent;', 'import net.minecraftforge.event.level.ExplosionEvent;'
    
    # Command events
    $content = $content -replace 'import net\.neoforged\.neoforge\.event\.RegisterCommandsEvent;', 'import net.minecraftforge.event.RegisterCommandsEvent;'
    
    # Dist marker
    $content = $content -replace 'import net\.neoforged\.api\.distmarker\.Dist;', 'import net.minecraftforge.api.distmarker.Dist;'
    
    # Client GUI
    $content = $content -replace 'import net\.neoforged\.neoforge\.client\.gui\.ConfigurationScreen;', ''
    $content = $content -replace 'import net\.neoforged\.neoforge\.client\.gui\.IConfigScreenFactory;', ''
    
    # Replace NeoForge class references with Forge equivalents
    $content = $content -replace 'NeoForge\.EVENT_BUS', 'MinecraftForge.EVENT_BUS'
    $content = $content -replace 'ModConfigSpec', 'ForgeConfigSpec'
    
    # Fix EventBusSubscriber annotations - the annotation pattern is different
    $content = $content -replace '@EventBusSubscriber\(modid', '@Mod.EventBusSubscriber(modid'
    
    # Fix TriState.FALSE -> Event.Result.DENY (Forge equivalent)
    $content = $content -replace 'net\.neoforged\.neoforge\.common\.util\.TriState\.FALSE', 'net.minecraftforge.eventbus.api.Event.Result.DENY'
    
    # Fix ItemEntityPickupEvent -> EntityItemPickupEvent
    $content = $content -replace 'ItemEntityPickupEvent\.Pre', 'EntityItemPickupEvent'
    
    # Fix PlayerTickEvent - Forge uses TickEvent.PlayerTickEvent
    $content = $content -replace 'PlayerTickEvent\.Pre', 'TickEvent.PlayerTickEvent'
    $content = $content -replace 'PlayerTickEvent\.Post', 'TickEvent.PlayerTickEvent'
    
    # Save the modified content
    Set-Content -Path $file.FullName -Value $content -NoNewline
}

Write-Host "Conversion complete! Files converted: $($files.Count)"
