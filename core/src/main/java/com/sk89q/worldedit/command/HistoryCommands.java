/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.command;

import com.boydti.fawe.config.BBC;
import com.boydti.fawe.object.FawePlayer;
import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandPermissions;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;

/**
 * Commands to undo, redo, and clear history.
 */
@Command(aliases = {}, desc = "Commands to undo, redo, and clear history: [More Info](http://wiki.sk89q.com/wiki/WorldEdit/Features#History)")
public class HistoryCommands extends MethodCommands {

    /**
     * Create a new instance.
     *
     * @param worldEdit reference to WorldEdit
     */
    public HistoryCommands(WorldEdit worldEdit) {
        super(worldEdit);
    }

    @Command(
            aliases = {"/undo", "undo"},
            usage = "[times] [player]",
            desc = "Undoes the last action",
            min = 0,
            max = 2
    )
    @CommandPermissions("worldedit.history.undo")
    public void undo(Player player, LocalSession session, CommandContext context) throws WorldEditException {
        int times = Math.max(1, context.getInteger(0, 1));
        FawePlayer.wrap(player).checkConfirmation(() -> {
            EditSession undone = null;
            int i = 0;
            for (; i < times; ++i) {
                if (context.argsLength() < 2) {
                    undone = session.undo(session.getBlockBag(player), player);
                } else {
                    player.checkPermission("worldedit.history.undo.other");
                    LocalSession sess = worldEdit.getSessionManager().findByName(context.getString(1));
                    if (sess == null) {
                        BBC.COMMAND_HISTORY_OTHER_ERROR.send(player, context.getString(1));
                        break;
                    }
                    undone = sess.undo(session.getBlockBag(player), player);
                    if (undone == null) break;
                }
            }
            if (undone == null) i--;
            if (i > 0) {
                BBC.COMMAND_UNDO_SUCCESS.send(player, i == 1 ? "" : " x" + i);
                worldEdit.flushBlockBag(player, undone);
            }
            if (undone == null) {
                BBC.COMMAND_UNDO_ERROR.send(player);
            }
        }, getArguments(context), times, 50, context);
    }

    @Command(
            aliases = {"/redo", "redo"},
            usage = "[times] [player]",
            desc = "Redoes the last action (from history)",
            min = 0,
            max = 2
    )
    @CommandPermissions("worldedit.history.redo")
    public void redo(Player player, LocalSession session, CommandContext args) throws WorldEditException {

        int times = Math.max(1, args.getInteger(0, 1));

        EditSession redone = null;
        int i = 0;
        for (; i < times; ++i) {
            if (args.argsLength() < 2) {
                redone = session.redo(session.getBlockBag(player), player);
            } else {
                player.checkPermission("worldedit.history.redo.other");
                LocalSession sess = worldEdit.getSession(args.getString(1));
                if (sess == null) {
                    BBC.COMMAND_HISTORY_OTHER_ERROR.send(player, args.getString(1));
                    break;
                }
                redone = sess.redo(session.getBlockBag(player), player);
                if (redone == null) break;
            }
        }
        if (redone == null) i--;
        if (i > 0) {
            BBC.COMMAND_REDO_SUCCESS.send(player, i == 1 ? "" : " x" + i);
            worldEdit.flushBlockBag(player, redone);
        }
        if (redone == null) {
            BBC.COMMAND_REDO_ERROR.send(player);
        }
    }

    @Command(
            aliases = {"/clearhistory", "clearhistory"},
            usage = "",
            desc = "Clear your history",
            min = 0,
            max = 0
    )
    @CommandPermissions("worldedit.history.clear")
    public void clearHistory(Player player, LocalSession session) throws WorldEditException {
        session.clearHistory();
        BBC.COMMAND_HISTORY_CLEAR.send(player);
    }

    public static Class<?> inject() {
        return HistoryCommands.class;
    }
}