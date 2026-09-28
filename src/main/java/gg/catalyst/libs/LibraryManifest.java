// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.libs;

import java.util.List;
import java.util.stream.Stream;

/**
 * Every jar Catalyst will ever download, pinned by SHA-256. Nothing outside this list is
 * fetched or put on a classpath, and a file whose hash differs is refused.
 *
 * Snapshots are pinned by their exact timestamped build (e.g. 1.21.11-20260512.221357-18)
 * rather than -SNAPSHOT, so a new upload can never silently replace what was verified.
 */
public final class LibraryManifest {
    public static final String CENTRAL = "https://repo1.maven.org/maven2";
    public static final String OPENCOLLAB = "https://repo.opencollab.dev/main";
    public static final String VIAVERSION = "https://repo.viaversion.com";

    public record Artifact(String group, String name, String version, String repository, String sha256) {
        public String fileName() { return name + "-" + version + ".jar"; }

        /** Timestamped snapshot builds live under the -SNAPSHOT directory of their base version. */
        public String url() {
            final String dir = version.replaceFirst("-\\d{8}\\.\\d{6}-\\d+$", "-SNAPSHOT");
            return repository + "/" + group.replace('.', '/') + "/" + name + "/" + dir + "/" + this.fileName();
        }
    }

    /**
     * The bot client: MCProtocolLib and exactly what it needs at runtime. Trimmed from its
     * full dependency tree (netty-all, QUIC, MQTT and every platform's native transport),
     * then confirmed by connecting bots with only these jars on the classpath.
     */
    public static final List<Artifact> BOT_CLIENT = List.of(
            new Artifact("com.google.code.gson", "gson", "2.13.2", CENTRAL,
                    "dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0"),
            new Artifact("com.nukkitx.fastutil", "fastutil-common", "8.5.3", OPENCOLLAB,
                    "d6f17bb3c6a2512c41ea78f533f9018b49199bae74ef9cb3d90a5e9b5801f938"),
            new Artifact("com.nukkitx.fastutil", "fastutil-int-common", "8.5.3", OPENCOLLAB,
                    "843631cbd8b96b5f0a66c2e916ec8770ab15a317d79bc1a2cd79e9ed2b66648b"),
            new Artifact("com.nukkitx.fastutil", "fastutil-int-int-maps", "8.5.3", OPENCOLLAB,
                    "52df12c8395db36947060efaabe7a1827d942b1e01140f2e7d7b3094522d8d5e"),
            new Artifact("com.nukkitx.fastutil", "fastutil-int-object-maps", "8.5.3", OPENCOLLAB,
                    "67488ac5ff4e9a821c42d9aa57d2e8f59dfd5d2a415ca8cbf2d6af01d5422816"),
            new Artifact("com.nukkitx.fastutil", "fastutil-int-sets", "8.5.3", OPENCOLLAB,
                    "e1288a27842cc6a7604d8243351879778f8a736e26038edb2c946b8db559343e"),
            new Artifact("com.nukkitx.fastutil", "fastutil-object-common", "8.5.3", OPENCOLLAB,
                    "02242fa5fc72e571b8ec41b41eef173b256fc1f84867579db672e4774dab541f"),
            new Artifact("com.nukkitx.fastutil", "fastutil-object-int-maps", "8.5.3", OPENCOLLAB,
                    "b8fff44de02a05a6ae2eeb28f5ed9862cbd180ea9c6c6f413962f58e331c9ca0"),
            new Artifact("com.nukkitx.fastutil", "fastutil-object-sets", "8.5.3", OPENCOLLAB,
                    "ba21454ed268d53c512b1f1ad52ed1e16fe28da3cea57ff0fa4ca957297a0070"),
            new Artifact("io.netty", "netty-buffer", "4.2.1.Final", CENTRAL,
                    "2ece8d8413d5aa35a1d1e0fe94f69f9e9edd8f7abb53f6c557a473c9fd0bfc8e"),
            new Artifact("io.netty", "netty-codec-base", "4.2.1.Final", CENTRAL,
                    "c2a1a7af991694272f74bb7dab01059049790c14b453760009827e105530d3b7"),
            new Artifact("io.netty", "netty-codec-compression", "4.2.1.Final", CENTRAL,
                    "c7d51a4f69c4bfbf043f92d196f61a6df42ad29c5bd0d053d107117220a5ca46"),
            new Artifact("io.netty", "netty-codec", "4.2.1.Final", CENTRAL,
                    "eef6f18f71ac5e32dc27a8d525479146c59fec30099d431a40662aff7dbd46f1"),
            new Artifact("io.netty", "netty-codec-dns", "4.2.1.Final", CENTRAL,
                    "dfea353a4095ebfbc7c94549904b74f7c0369f26ae5491e79bc5e47d9054ad1a"),
            new Artifact("io.netty", "netty-codec-haproxy", "4.2.1.Final", CENTRAL,
                    "f6d28d0cd45cc9984ba6286b559760f7fabd5dcf0ad178701ef0eae5299f4657"),
            new Artifact("io.netty", "netty-codec-http", "4.2.1.Final", CENTRAL,
                    "10e831862bf7dabceccf4fd82989c31dda39a89ed669cf7e96dee09729077541"),
            new Artifact("io.netty", "netty-codec-socks", "4.2.1.Final", CENTRAL,
                    "4a5c557d8b1f0f364906a2cc5f1df8dd36e7b71aa4dbc4e8d7aede81d46be793"),
            new Artifact("io.netty", "netty-common", "4.2.1.Final", CENTRAL,
                    "7eea4a96f61ace06337906374150cb73c94f248897fda3da865b5c38a710bdfd"),
            new Artifact("io.netty", "netty-handler", "4.2.1.Final", CENTRAL,
                    "b97d057adffffb824ce6b5d9876bfe1f0bfceb24504b638d3f6c6293cfff5dc5"),
            new Artifact("io.netty", "netty-handler-proxy", "4.2.1.Final", CENTRAL,
                    "c92ef0552d859a736874cc6519304060afe04f8f435c4a23b22b59a525e21c4f"),
            new Artifact("io.netty", "netty-resolver", "4.2.1.Final", CENTRAL,
                    "fb1c5a9f1230cc558944ac633e5b64d3e4ec981d8e0d5727d5f58122008b218e"),
            new Artifact("io.netty", "netty-resolver-dns", "4.2.1.Final", CENTRAL,
                    "093523eb425a7398c4bcc6b1d5919c17d9d865816a56db4824ceb14d6da25206"),
            new Artifact("io.netty", "netty-resolver-dns-classes-macos", "4.2.1.Final", CENTRAL,
                    "c8025e0f87b7b8290ddf9a1019a1e5f5e4769d79a479f2c16df48a0f51582af1"),
            new Artifact("io.netty", "netty-transport", "4.2.1.Final", CENTRAL,
                    "ba9fd45598fa05605d2a5014d6f47112e4392ba2b912cd1e3786e4851b397cf7"),
            new Artifact("io.netty", "netty-transport-native-unix-common", "4.2.1.Final", CENTRAL,
                    "7f2a7746950ccb227908afe3ee21b9bcf69ccc58437bb17c1579a8d572fb38a9"),
            new Artifact("io.netty", "netty-transport-classes-epoll", "4.2.1.Final", CENTRAL,
                    "cc2f97afa5684f1995a5f1006b36c9ba4b233c1e68038c50e5b77b14aba4eea4"),
            new Artifact("io.netty", "netty-transport-classes-kqueue", "4.2.1.Final", CENTRAL,
                    "8f1d4b4c7b92edd31d19c176ab60c5e3e71e784481cc95c7fc50c74511d32d56"),
            new Artifact("io.netty", "netty-transport-classes-io_uring", "4.2.1.Final", CENTRAL,
                    "c6deb8b20cf76a37e2cf6e905fd3eee4f87eed6478d4ed75c7709f40e0019156"),
            new Artifact("net.kyori", "adventure-api", "4.25.0", CENTRAL,
                    "8e281bf1357ff91af9783a6c24276b483d8154a7b3cab5b04e909e6bf5e72c9d"),
            new Artifact("net.kyori", "adventure-key", "4.25.0", CENTRAL,
                    "cec8f0abf642df0e627bf5a9fff6f17cbb620ea6fe05f2162b96cad1564d8857"),
            new Artifact("net.kyori", "adventure-nbt", "4.25.0", CENTRAL,
                    "8ee6da621f496f16c619de6ec5c0b9c140ceac9b89c2a5b3377938dbd55a162e"),
            new Artifact("net.kyori", "adventure-text-serializer-commons", "4.25.0", CENTRAL,
                    "0cccad7f54db9a1ddc8164b282c1b4c49c2e9c76f682cbe2c9654161dc9ad395"),
            new Artifact("net.kyori", "adventure-text-serializer-gson", "4.25.0", CENTRAL,
                    "931f67959d46e0fe55b64ab9164e31b578b79235787f69d472b62865f97b7245"),
            new Artifact("net.kyori", "adventure-text-serializer-json", "4.25.0", CENTRAL,
                    "482b4aa218d7c879fcf57e5d0f9cc04db4b4d4b5679eb99b727f6104ed7fb982"),
            new Artifact("net.kyori", "adventure-text-serializer-json-legacy-impl", "4.25.0", CENTRAL,
                    "3827ea0b41ec6c373f913d824e6027612e5014b9497fbef98c4b1a1559b0fc3b"),
            new Artifact("net.kyori", "examination-api", "1.3.0", CENTRAL,
                    "c9237ffecb05428f6eff86216246ac70ce0b47b04c08ea7ca35020fde57f8492"),
            new Artifact("net.kyori", "examination-string", "1.3.0", CENTRAL,
                    "7d01fc25a4bb3af0e1662685455f4541fbf4626216ea5846e455c1491e156b8c"),
            new Artifact("net.kyori", "option", "1.1.0", CENTRAL,
                    "97b69b4b17dfe02217c9131ad342564cbc9aebd04c75eb689639b5f78fd4b11c"),
            new Artifact("net.lenni0451.commons", "gson", "1.9.0", CENTRAL,
                    "5cb4fb360bd50ecfa595d954e27b914b64e41bc9e70c7cb9a17e84a6f1856777"),
            new Artifact("net.lenni0451.commons", "httpclient", "1.9.0", CENTRAL,
                    "c7c01c4639ee890b69a443d135c3bd55aedf24e41e19189c2ad874a0ed429c3e"),
            new Artifact("net.raphimc", "MinecraftAuth", "5.0.0", CENTRAL,
                    "7a1af91e850587ce052136e3c19de2c5ce5493255371668b87c75436a798d70d"),
            new Artifact("org.cloudburstmc.math", "api", "2.0", OPENCOLLAB,
                    "4348d532b11d033f1c6e11037fe3d045223878333d9e140c4f11bb3309b72083"),
            new Artifact("org.cloudburstmc.math", "immutable", "2.0", OPENCOLLAB,
                    "6ada50be0ac66f4012b60ec9b0c7efe87f201ca989fd3d7963438a6bb6981ebf"),
            new Artifact("org.cloudburstmc", "nbt", "3.0.4.Final", CENTRAL,
                    "1a30d7e05ad52ccbffb2016808c5dc2366147f0715c202ad375552d80a8c4c64"),
            new Artifact("org.geysermc.mcprotocollib", "protocol", "1.21.11-20260512.221357-18", OPENCOLLAB,
                    "00d9ae3464dac8dcfe861303d728cb3361a9794d8ad0455aa340229ee83ee709"),
            new Artifact("org.slf4j", "slf4j-api", "2.0.16", CENTRAL,
                    "a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a"));

    /**
     * Protocol translation, so bots speaking the newest protocol can join an older or newer
     * server: ViaVersion carries newer clients down, ViaBackwards older clients up, and
     * ViaLoader runs both inside a plain client. All GPL-3.0, hence Catalyst's licence.
     */
    public static final List<Artifact> VIA = List.of(
            new Artifact("net.raphimc", "ViaLoader", "3.0.4", VIAVERSION,
                    "c3ef9246185940fd72e3708fed987636de0de36f0815024d115c5c475b594760"),
            new Artifact("com.viaversion", "viaversion-common", "5.12.0", VIAVERSION,
                    "183e0ba9e5c8a19ac192b884e4a7d9402af5f321738e4f18010ba139214f76cc"),
            new Artifact("com.viaversion", "viabackwards-common", "5.12.0", VIAVERSION,
                    "232651d294c8608d579886e1d95e9ab8b42a0b2dcaec28818b5218fd1ddb2385"),
            new Artifact("com.google.guava", "guava", "33.3.1-jre", CENTRAL,
                    "4bf0e2c5af8e4525c96e8fde17a4f7307f97f8478f11c4c8e35a0e3298ae4e90"),
            new Artifact("com.google.guava", "failureaccess", "1.0.2", CENTRAL,
                    "8a8f81cf9b359e3f6dfa691a1e776985c061ef2f223c9b2c80753e1b458e8064"));

    /** Everything the downloader should hold. */
    public static final List<Artifact> ALL = Stream.concat(BOT_CLIENT.stream(), VIA.stream()).toList();

    private LibraryManifest() {}
}
