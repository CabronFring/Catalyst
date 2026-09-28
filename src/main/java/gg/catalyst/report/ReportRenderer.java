// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.report;

import gg.catalyst.optimization.CheckResult;
import gg.catalyst.optimization.Optimization;
import gg.catalyst.optimization.OptimizationLevel;
import gg.catalyst.optimization.ScanResult;
import gg.catalyst.util.Branding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders reports as legacy colour-coded strings rather than Adventure components,
 * because Adventure is absent from the Bukkit and Spigot APIs that Catalyst also targets.
 */
public final class ReportRenderer {
    private static final String BRAND = "§b";
    private static final String SAFE = "§a";
    private static final String MOD = "§e";
    private static final String EXP = "§6";
    private static final String MUTED = "§7";
    private static final String DIM = "§8";
    private static final String VALUE = "§f";
    private static final String BOLD = "§l";
    private static final String RESET = "§r";

    private ReportRenderer() {}

    /** Compact report, used for the startup console scan. */
    public static List<String> summary(ScanResult scan, boolean showPassing) {
        final List<String> lines = new ArrayList<>();
        lines.add(BRAND + BOLD + Branding.name() + RESET + MUTED + "  " + scan.platform().displayName + " - performance scan");

        if (scan.isClean()) {
            lines.add(SAFE + "  Everything " + Branding.name() + " checks is already well configured.");

            if (showPassing) lines.add(MUTED + "  " + scan.passing().size() + " settings verified.");
            return lines;
        }

        lines.add("");
        lines.add(VALUE + "  Suggestions (" + scan.findings().size() + ")");

        for (final Map.Entry<Optimization, CheckResult> e : scan.findings().entrySet()) {
            final Optimization opt = e.getKey();
            final CheckResult r = e.getValue();
            lines.add(MUTED + "    - " + VALUE + opt.name()
                    + MUTED + "  " + r.currentValue() + " -> " + r.recommendedValue()
                    + "  " + colorOf(opt.level()) + "[" + opt.level().name().toLowerCase() + "]");
        }

        if (showPassing && !scan.passing().isEmpty()) {
            lines.add("");
            lines.add(MUTED + "  Already optimal (" + scan.passing().size() + ")");

            for (final Optimization opt : scan.passing())
                lines.add(MUTED + "    - " + opt.name());
        }

        lines.add("");
        lines.addAll(callToAction(scan));

        return lines;
    }

    /** Full report with explanations, used for /catalyst config check. */
    public static List<String> detailed(ScanResult scan) {
        final List<String> lines = new ArrayList<>();
        lines.add(BRAND + BOLD + Branding.name() + RESET + MUTED + "  " + scan.platform().displayName + " - performance scan");
        lines.add("");

        if (scan.isClean()) {
            lines.add(SAFE + "  Everything " + Branding.name() + " checks is already well configured.");
            lines.add(MUTED + "  " + scan.passing().size() + " settings verified.");
            return lines;
        }

        for (final Map.Entry<Optimization, CheckResult> e : scan.findings().entrySet()) {
            lines.addAll(finding(e.getKey(), e.getValue()));
            lines.add("");
        }

        lines.addAll(callToAction(scan));

        return lines;
    }

    /** One finding's lines, shared by the saved report and the clickable chat view. */
    public static List<String> finding(Optimization opt, CheckResult r) {
        return List.of(
                VALUE + BOLD + "  " + opt.name() + RESET + "  " + colorOf(opt.level()) + "[" + opt.level().name().toLowerCase() + "]",
                MUTED + "    now: " + VALUE + r.currentValue(),
                MUTED + "    set: " + SAFE + r.recommendedValue(),
                MUTED + "    " + opt.description(),
                MUTED + "    " + r.reason(),
                DIM + "    id: " + opt.id());
    }

    private static List<String> callToAction(ScanResult scan) {
        final List<String> lines = new ArrayList<>();
        final int safe = scan.count(OptimizationLevel.SAFE);
        final int mod = scan.count(OptimizationLevel.MODERATE);
        final int exp = scan.count(OptimizationLevel.EXPERIMENTAL);

        final List<String> parts = new ArrayList<>();

        if (safe > 0) parts.add(safe + " safe");

        if (mod > 0) parts.add(mod + " moderate");

        if (exp > 0) parts.add(exp + " experimental");
        lines.add(VALUE + "  " + String.join(MUTED + " | " + VALUE, parts) + MUTED + " change(s) available.");

        if (safe > 0)
            lines.add(SAFE + "  /catalyst config apply safe" + MUTED + "  applies the safe ones. Files are backed up first.");

        if (mod > 0)
            lines.add(MOD + "  /catalyst config check" + MUTED + "  explains the moderate ones before you decide.");

        return lines;
    }

    private static String colorOf(OptimizationLevel level) {
        return switch (level) {
            case SAFE -> SAFE;
            case MODERATE -> MOD;
            case EXPERIMENTAL -> EXP;
        };
    }
}
