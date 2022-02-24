package com.boydti.fawe.object.brush;

import com.boydti.fawe.config.BBC;
import com.sk89q.worldedit.LocalConfiguration;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.command.tool.BrushTool;
import com.sk89q.worldedit.command.tool.DoubleActionTraceTool;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Platform;

public class InspectBrush extends BrushTool implements DoubleActionTraceTool {

    /**
     * Construct the tool.
     */
    public InspectBrush() {
        super("worldedit.tool.inspect");
    }

    @Override
    public boolean actSecondary(Platform server, LocalConfiguration config, Player player, LocalSession session) {
        return perform(player, session, false);
    }

    @Override
    public boolean actPrimary(Platform server, LocalConfiguration config, Player player, LocalSession session) {
        return perform(player, session, true);
    }

    public boolean perform(final Player player, LocalSession session, boolean rightClick) {
        if (!session.isToolControlEnabled() || !player.hasPermission("worldedit.tool.inspect")) {
            player.print(BBC.getPrefix() + BBC.NO_PERM.f("worldedit.tool.inspect"));
            return false;
        }

        player.print(BBC.getPrefix() + BBC.SETTING_DISABLE.f("history.use-database (Import with /frb #import )"));
        return false;
    }

    @Override
    public boolean canUse(Actor actor) {
        return actor.hasPermission("worldedit.tool.inspect");
    }
}
