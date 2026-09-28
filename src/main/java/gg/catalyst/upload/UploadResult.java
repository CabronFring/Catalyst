// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.upload;

import java.util.List;

public record UploadResult(boolean success, String message, List<String> urls) {
    public static UploadResult ok(List<String> urls) {
        return new UploadResult(true, "Uploaded.", urls);
    }

    public static UploadResult fail(String message) {
        return new UploadResult(false, message, List.of());
    }
}
