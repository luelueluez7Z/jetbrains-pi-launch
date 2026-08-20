package com.ruigu.pichat.rpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 定位本机 node 可执行文件与 pi 的 cli.js 入口。跨平台（Windows / macOS）。
 *
 * node 探测链（由先到后，逐个用 `node --version` 校验）：
 *   1. 环境变量 PI_NODE_PATH（显式指定，如 "D:\\tools\\node.exe"）
 *   2. where/which node —— PATH 中解析出的 node 绝对路径
 *   3. `npm root -g` 的父目录（node 安装根）
 *   4. 平台特定环境变量：
 *        Windows: NVM_SYMLINK / NVM_HOME、ProgramFiles 系（ProgramW6432 / ProgramFiles / ProgramFiles(x86)）
 *        macOS:   NVM_DIR（nvm 安装根）
 *   5. 兜底：裸 "node"（交给 OS 的 PATH 解析）
 *
 * cli.js 探测链：
 *   1. 环境变量 PI_CLI_PATH（显式指定 cli.js 路径）
 *   2. `npm root -g` + @earendil-works/pi-coding-agent/dist/cli.js
 *   3. where/which pi → 所在目录（解析符号链接）的 node_modules 推导
 *   4. where/which node → node 安装目录的 node_modules 推导（Windows 全局安装兜底）
 *   5. ~/.pi/dist/cli.js
 *
 * 不硬编码任何本机安装路径，全部通过环境变量 / 系统探测发现。
 */
public final class PiLocator {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private PiLocator() {}

    /** node 可执行文件名（Windows 为 node.exe，其余为 node）。 */
    private static String nodeExecutableName() {
        return IS_WINDOWS ? "node.exe" : "node";
    }

    /** 返回 node 可执行命令候选（可能是裸命令名，由 OS 从 PATH 解析）。 */
    public static List<String> findNodeCommand() {
        // 1. 显式覆盖
        String env = System.getenv("PI_NODE_PATH");
        if (env != null && !env.isBlank()) return List.of(env);

        List<String> candidates = new ArrayList<>();

        // 2. where/which node —— PATH 中的 node 绝对路径
        addFindInPath(candidates, "node");

        // 3. npm root -g 的父目录（node 安装根）
        addNpmPrefixNode(candidates);

        // 4. 平台特定环境变量
        if (IS_WINDOWS) {
            for (String key : List.of("NVM_SYMLINK", "NVM_HOME")) {
                String v = System.getenv(key);
                if (v != null && !v.isBlank()) candidates.add(v + "\\node.exe");
            }
            for (String key : List.of("ProgramW6432", "ProgramFiles", "ProgramFiles(x86)")) {
                String v = System.getenv(key);
                if (v != null && !v.isBlank()) candidates.add(v + "\\nodejs\\node.exe");
            }
        } else {
            // macOS / Linux：nvm 安装根（~/.nvm/versions/node/vX/bin/node 由 PATH 覆盖，这里补 NVM_DIR 根）
            String nvmDir = System.getenv("NVM_DIR");
            if (nvmDir != null && !nvmDir.isBlank()) candidates.add(nvmDir + "/node");
        }

        // 5. 兜底 PATH
        candidates.add("node");
        return candidates;
    }

    /** 返回 pi 的 dist/cli.js 完整路径；找不到返回 null。 */
    public static String findCliJs() {
        String env = System.getenv("PI_CLI_PATH");
        if (env != null && !env.isBlank() && Files.exists(Path.of(env))) return env;

        List<Path> candidates = new ArrayList<>();

        // 1. npm root -g
        addNpmRootCli(candidates);

        // 2. where/which pi → 所在目录（解析符号链接）推导
        addWherePiCli(candidates);

        // 3. where/which node → node 安装目录推导（Windows 全局安装兜底）
        addWhereNodeCli(candidates);

        // 4. 用户全局 ~/.pi/dist
        candidates.add(Path.of(System.getProperty("user.home"), ".pi", "dist", "cli.js"));

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

    // ── 探测辅助 ──────────────────────────────────────────────

    /** 把 PATH 中解析出的 name 绝对路径加入候选（Windows: where，其他: which）。 */
    private static void addFindInPath(List<String> out, String name) {
        for (String hit : findInPath(name)) out.add(hit);
    }

    /** npm root -g 的父目录（node 安装根）下的 node 可执行文件。 */
    private static void addNpmPrefixNode(List<String> out) {
        String root = npmGlobalRoot();
        if (root == null) return;
        Path rootPath = Path.of(root);
        Path parent = rootPath.getParent();
        if (parent != null) out.add(parent.resolve(nodeExecutableName()).toString());
        // nvm-windows / nvm 兼容：node 可能直接在全局 root 下
        out.add(rootPath.resolve(nodeExecutableName()).toString());
    }

    /** npm root -g + @earendil-works/pi-coding-agent/dist/cli.js。 */
    private static void addNpmRootCli(List<Path> out) {
        String root = npmGlobalRoot();
        if (root != null) {
            out.add(Path.of(root, "@earendil-works", "pi-coding-agent", "dist", "cli.js"));
        }
    }

    /** 运行 `npm root -g`，返回标准输出的 trim 结果；失败返回 null。 */
    private static String npmGlobalRoot() {
        try {
            ProcessBuilder pb = new ProcessBuilder("npm", "root", "-g");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(10, TimeUnit.SECONDS) && !out.isEmpty()) return out;
        } catch (Exception ignored) {}
        return null;
    }

    /** where/which pi：从 pi 可执行文件所在目录（解析符号链接）推导 cli.js。 */
    private static void addWherePiCli(List<Path> out) {
        for (String exe : findInPath("pi")) {
            try {
                Path p = Path.of(exe);
                if (Files.isSymbolicLink(p)) p = p.toRealPath();
                Path dir = p.getParent();
                if (dir == null) continue;
                out.add(dir.resolve("node_modules").resolve("@earendil-works")
                        .resolve("pi-coding-agent").resolve("dist").resolve("cli.js"));
                out.add(dir.resolve("pi-coding-agent").resolve("dist").resolve("cli.js"));
            } catch (Exception ignored) {}
        }
    }

    /** where/which node：从 node 安装目录推导 cli.js（Windows 全局安装兜底）。 */
    private static void addWhereNodeCli(List<Path> out) {
        for (String exe : findInPath("node")) {
            try {
                Path dir = Path.of(exe).getParent();
                if (dir == null) continue;
                out.add(dir.resolve("node_modules").resolve("@earendil-works")
                        .resolve("pi-coding-agent").resolve("dist").resolve("cli.js"));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 在 PATH 中定位可执行文件。Windows 用 `where`（可能多行），macOS/Linux 用 `which`（单行）。
     * 返回所有命中（trim 后非空）。失败返回空列表。
     */
    private static List<String> findInPath(String name) {
        List<String> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(IS_WINDOWS ? "where" : "which", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = new String(p.getInputStream().readAllBytes());
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                for (String line : text.split("\\r?\\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) result.add(t);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }
}
