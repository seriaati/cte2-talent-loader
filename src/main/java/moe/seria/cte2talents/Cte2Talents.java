package moe.seria.cte2talents;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("cte2talents")
public class Cte2Talents {
    public Cte2Talents() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.addListener(BuildCommand::register);
            MinecraftForge.EVENT_BUS.addListener(BuildCommand::tick);
        }
    }
}
