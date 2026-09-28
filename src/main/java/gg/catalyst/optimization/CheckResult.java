// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

public record CheckResult(
        String currentValue,
        String recommendedValue,
        String reason
) {
    public static CheckResult of(Object current, Object recommended, String reason) {
        return new CheckResult(String.valueOf(current), String.valueOf(recommended), reason);
    }
}
