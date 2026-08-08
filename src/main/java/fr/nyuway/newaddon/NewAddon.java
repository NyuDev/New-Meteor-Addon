package fr.nyuway.newaddon;

import fr.nyuway.newaddon.modules.AutoMoss;
import fr.nyuway.newaddon.modules.AutoStasisPull;
import fr.nyuway.newaddon.modules.ElytraResupply;
import fr.nyuway.newaddon.modules.LiveMessage;
import fr.nyuway.newaddon.modules.StasisProtection;
import fr.nyuway.newaddon.modules.StasisPull;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewAddon extends MeteorAddon {

    public static final Logger LOG = LoggerFactory.getLogger("New");
    public static final Category CATEGORY = new Category("New");

    @Override
    public void onRegisterCategories() {
        // Must use the static Modules.registerCategory() during this callback
        // (Meteor enforces Categories.REGISTERING == true here).
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        LOG.info("New addon initializing...");
        Modules.get().add(new AutoMoss());
        Modules.get().add(new StasisPull());
        Modules.get().add(new AutoStasisPull());
        Modules.get().add(new StasisProtection());
        Modules.get().add(new ElytraResupply());
        Modules.get().add(new LiveMessage());
    }

    @Override
    public String getPackage() {
        return "fr.nyuway.newaddon";
    }
}
