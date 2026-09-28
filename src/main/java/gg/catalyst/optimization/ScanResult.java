// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

import gg.catalyst.platform.ServerPlatform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScanResult {
    private final ServerPlatform platform;
    private final Map<Optimization, CheckResult> findings = new LinkedHashMap<>();
    private final List<Optimization> passing = new ArrayList<>();

    public ScanResult(ServerPlatform platform) {
        this.platform = platform;
    }

    public void addFinding(Optimization opt, CheckResult result) { this.findings.put(opt, result); }
    public void addPassing(Optimization opt) { this.passing.add(opt); }

    public ServerPlatform platform() { return this.platform; }
    public Map<Optimization, CheckResult> findings() { return this.findings; }
    public List<Optimization> passing() { return this.passing; }

    public int count(OptimizationLevel level) {
        return (int) this.findings.keySet().stream().filter(o -> o.level() == level).count();
    }

    public boolean isClean() { return this.findings.isEmpty(); }

    public List<Optimization> at(OptimizationLevel level) {
        return this.findings.keySet().stream().filter(o -> o.level() == level).toList();
    }
}
