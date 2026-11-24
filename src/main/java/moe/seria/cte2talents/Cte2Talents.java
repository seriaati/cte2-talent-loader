package moe.seria.cte2talents;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("cte2talents")
public class Cte2Talents {
    public Cte2Talents(FMLJavaModLoadingContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            context.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            MinecraftForge.EVENT_BUS.addListener(BuildCommand::register);
            MinecraftForge.EVENT_BUS.addListener(BuildCommand::tick);
            MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> GearExport.register(e.getDispatcher()));
            MinecraftForge.EVENT_BUS.addListener(GearExport::onClose);
            MinecraftForge.EVENT_BUS.addListener(GearExport::tick);
            MinecraftForge.EVENT_BUS.addListener(GearScan::tick);
        }
    }
}
