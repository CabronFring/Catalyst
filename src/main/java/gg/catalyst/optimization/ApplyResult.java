// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class ApplyResult {
    private final List<Optimization> applied = new ArrayList<>();
    private final List<Optimization> liveApplied = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private File backupDir;

    public void addApplied(Optimization opt) { this.applied.add(opt); }
    public void addLiveApplied(Optimization opt) { this.liveApplied.add(opt); }
    public void addError(String error) { this.errors.add(error); }
    public void setBackupDir(File dir) { this.backupDir = dir; }

    /** Everything whose value was written to a config file. */
    public List<Optimization> applied() { return this.applied; }

    /** The subset that also took effect immediately, with no restart. */
    public List<Optimization> liveApplied() { return this.liveApplied; }

    /** Written to config but only active after a restart. */
    public List<Optimization> restartRequired() {
        return this.applied.stream().filter(o -> !this.liveApplied.contains(o)).toList();
    }

    public List<String> errors() { return this.errors; }
    public File backupDir() { return this.backupDir; }

    public boolean success() { return this.errors.isEmpty(); }
}
