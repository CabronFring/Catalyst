// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class BackupManager {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final File backupsDir;

    public BackupManager(Plugin plugin) {
        this.backupsDir = new File(plugin.getDataFolder(), "backups");
    }

    /**
     * Copies all config files that exist in the given context into a timestamped
     * backup directory. Returns the backup directory path for display to the user.
     */
    public File backup(OptimizationContext ctx) throws IOException {
        final String timestamp = LocalDateTime.now().format(FMT);
        final File dest = new File(this.backupsDir, timestamp);
        dest.mkdirs();

        final List<String> paths = List.of(
                "server.properties",
                "bukkit.yml",
                "spigot.yml",
                "config/paper-global.yml",
                "paper.yml",
                "config/paper-world-defaults.yml",
                "purpur.yml"
        );

        for (final String rel : paths) {
            final File src = new File(ctx.serverRoot(), rel);

            if (!src.exists()) continue;
            final File target = new File(dest, rel);
            target.getParentFile().mkdirs();
            Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return dest;
    }

    /** Lists all backup directories, newest first. */
    public List<File> listBackups() {
        if (!this.backupsDir.exists()) return List.of();
        final File[] dirs = this.backupsDir.listFiles(File::isDirectory);

        if (dirs == null) return List.of();
        final List<File> list = new ArrayList<>(List.of(dirs));
        list.sort((a, b) -> b.getName().compareTo(a.getName()));

        return list;
    }

    /**
     * Restores all files from the given backup directory into the server root.
     */
    public void restore(File backupDir, File serverRoot) throws IOException {
        this.restoreTree(backupDir, serverRoot, backupDir);
    }

    private void restoreTree(File base, File serverRoot, File current) throws IOException {
        final File[] children = current.listFiles();

        if (children == null) return;

        for (final File f : children) {
            if (f.isDirectory()) {
                this.restoreTree(base, serverRoot, f);
            } else {
                final String rel = base.toURI().relativize(f.toURI()).getPath();
                final File dest = new File(serverRoot, rel);
                dest.getParentFile().mkdirs();
                Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
