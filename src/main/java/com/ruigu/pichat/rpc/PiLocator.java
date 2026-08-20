package com.ruigu.pichat.rpc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 定位本机 node 可执行文件与 pi 的 cli.js 入口。
 * 探测顺序：
 *   node: 环境变量 PI_NODE_PATH → "node"（走 PATH）
 *   cli:  环境变量 PI_CLI_PATH → `npm root -g` → 已知常见路径 → where pi
 */
public final class PiLocator {

    private PiLocator() {}

    /** 返回 node 可执行命令（可能是裸命令名，由 OS 从 PATH 解析）。 */
    public static List<String> findNodeCommand() {
        String env = System.getenv("PI_NODE_PATH");
        if (env != null && !env.isBlank()) return List.of(env);
        // 常见 Windows 安装位置
        List<String> candidates = new ArrayList<>();
        for (String p : List.of(
                "D:\\Program Files\\nodejs\\node.exe",
                "C:\\Program Files\\nodejs\\node.exe",
                System.getenv("ProgramFiles") + "\\nodejs\\node.exe")) {
            if (Files.exists(Path.of(p))) candidates.add(p);
        }
        // 兜底：交给 OS 的 PATH 解析
        candidates.add("node");
        return candidates;
    }

    /** 返回 pi 的 dist/cli.js 完整路径；找不到返回 null。 */
    public static String findCliJs() {
        String env = System.getenv("PI_CLI_PATH");
        if (env != null && !env.isBlank() && Files.exists(Path.of(env))) return env;

        List<Path> candidates = new ArrayList<>();

        // 1. npm root -g
        try {
            ProcessBuilder pb = new ProcessBuilder("npm", "root", "-g");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(10, TimeUnit.SECONDS) && !out.isEmpty()) {
                candidates.add(Path.of(out, "@earendil-works", "pi-coding-agent", "dist", "cli.js"));
            }
        } catch (Exception ignored) {}

        // 2. where pi → 找到 pi.cmd 所在目录，同目录 node_modules
        try {
            ProcessBuilder pb = new ProcessBuilder("where", "pi");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                for (String line : out.split("\\r?\\n")) {
                    if (line.isBlank()) continue;
                    Path dir = Path.of(line).getParent();
                    if (dir != null) {
                        candidates.add(dir.resolve("node_modules").resolve("@earendil-works")
                                .resolve("pi-coding-agent").resolve("dist").resolve("cli.js"));
                        candidates.add(dir.resolve("pi-coding-agent").resolve("dist").resolve("cli.js"));
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. 已知常见路径
        for (String p : List.of(
                "D:\\Program Files\\nodejs\\node_modules\\@earendil-works\\pi-coding-agent\\dist\\cli.js",
                "C:\\Program Files\\nodejs\\node_modules\\@earendil-works\\pi-coding-agent\\dist\\cli.js",
                System.getProperty("user.home") + "\\.pi\\dist\\cli.js")) {
            candidates.add(Path.of(p));
        }

        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) return c.toString();
        }
        return null;
    }

    /** 校验并返回可用的 node 命令。 */
    public static String resolveNode() {
        for (String cmd : findNodeCommand()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
                if (ok) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static String describe() {
        String node = resolveNode();
        String cli = findCliJs();
        StringBuilder sb = new StringBuilder();
        sb.append("node=").append(node == null ? "未找到" : node);
        sb.append("\npi cli=").append(cli == null ? "未找到" : cli);
        return sb.toString();
    }
}
