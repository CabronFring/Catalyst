// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.event;

import gg.catalyst.optimization.Optimization;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.io.File;
import java.util.List;

/**
 * Catalyst wrote one or more optimizations to config, from config apply or config fix. Fired on
 * the main thread, only when something actually changed, for audit or logging. Where each one
 * landed is on the optimization itself - {@link Optimization#configFile()} and {@link Optimization#configKey()}.
 */
public final class CatalystOptimizationsAppliedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final List<Optimization> applied;
    private final List<Optimization> liveApplied;
    private final File backupDir;

    public CatalystOptimizationsAppliedEvent(List<Optimization> applied, List<Optimization> liveApplied, File backupDir) {
        this.applied = List.copyOf(applied);
        this.liveApplied = List.copyOf(liveApplied);
        this.backupDir = backupDir;
    }

    public List<Optimization> getApplied() { return this.applied; }

    /** The subset that also took effect without a restart. */
    public List<Optimization> getLiveApplied() { return this.liveApplied; }

    /** The backup taken before the change, or null if it could not be made. */
    public File getBackupDir() { return this.backupDir; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
