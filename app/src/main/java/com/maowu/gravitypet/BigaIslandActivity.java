package com.maowu.gravitypet;

import org.godotengine.godot.GodotActivity;

import java.util.ArrayList;
import java.util.List;

/** Hosts the exported 鼻嘎岛 Godot project inside the existing Android app. */
public class BigaIslandActivity extends GodotActivity {
    private boolean restoreOverlayAfterIsland;

    @Override
    public List<String> getCommandLine() {
        List<String> arguments = new ArrayList<>(super.getCommandLine());
        arguments.add("--main-pack");
        arguments.add("res://biga_island.pck");
        return arguments;
    }

    @Override
    protected void onStart() {
        super.onStart();
        restoreOverlayAfterIsland = OverlayPetService.isRunning();
        if (restoreOverlayAfterIsland) {
            sendOverlayAction(OverlayPetService.ACTION_HIDE_FOR_ISLAND);
        }
    }

    @Override
    protected void onStop() {
        if (restoreOverlayAfterIsland && OverlayPetService.isRunning()) {
            sendOverlayAction(OverlayPetService.ACTION_SHOW_AFTER_ISLAND);
        }
        restoreOverlayAfterIsland = false;
        super.onStop();
    }

    private void sendOverlayAction(String action) {
        startService(new android.content.Intent(this, OverlayPetService.class).setAction(action));
    }
}
