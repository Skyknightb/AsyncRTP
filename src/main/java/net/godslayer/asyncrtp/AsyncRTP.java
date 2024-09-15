package net.godslayer.asyncrtp;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AsyncRTP.MODID)
public class AsyncRTP {
    public static final String MODID = "asyncrtp";

    public AsyncRTP() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register common setup method
        modEventBus.addListener(this::setup);

        // Register server starting event
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void setup(FMLCommonSetupEvent event) {
        // Some common setup code
    }

    private void onServerStarting(ServerStartingEvent event) {
        // Register the RTP command
        event.getServer().getCommands().getDispatcher().register(AsyncRTPCommand.register());
    }
}