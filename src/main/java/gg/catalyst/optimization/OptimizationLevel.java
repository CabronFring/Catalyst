// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.optimization;

public enum OptimizationLevel {
    /**
     * Well-established, widely recommended settings with no gameplay side effects.
     * Applied automatically by /catalyst config apply safe.
     */
    SAFE,

    /**
     * Effective but may have minor gameplay or compatibility trade-offs.
     * Applied by /catalyst config apply moderate (implies SAFE too).
     */
    MODERATE,

    /**
     * Aggressive or less-tested settings. May break farms, redstone, or specific
     * game mechanics. Applied only by /catalyst config apply experimental.
     */
    EXPERIMENTAL
}
