package dev.cyber.spawnerprotect;

import dev.cyber.spawnerprotect.modules.SpawnerProtectPlus;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import meteordevelopment.meteorclient.systems.modules.Category;

public class SpawnerProtectAddon extends MeteorAddon {

    public static final Category CATEGORY = new Category("Spawner Protect", Items.SPAWNER.getDefaultStack());

    @Override
    public void onInitialize() {
        Modules.get().add(new SpawnerProtectPlus());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.cyber.spawnerprotect";
    }

    @Override
    public String getWebsite() {
        return null;
    }

    @Override
    public GithubRepo getRepo() {
        return null;
    }
}
