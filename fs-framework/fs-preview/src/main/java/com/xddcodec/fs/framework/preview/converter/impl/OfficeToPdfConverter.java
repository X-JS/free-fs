package com.xddcodec.fs.framework.preview.converter.impl;

import com.xddcodec.fs.framework.preview.converter.IConverter;
import com.xddcodec.fs.framework.preview.office.LibreOfficePathResolver;
import com.xddcodec.fs.framework.preview.office.OfficeToPdfConfig;
import com.xddcodec.fs.framework.preview.queue.OfficeTaskQueueHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@RequiredArgsConstructor
public class OfficeToPdfConverter implements IConverter {

    private final OfficeToPdfConfig config;
    private final OfficeTaskQueueHandler queueHandler;

    /**
     * 解析后的 soffice 可执行文件路径（懒加载缓存）。
     */
    private volatile String sofficeExecutable;

    @Override
    public String getTargetExtension() {
        return "pdf";
    }

    @Override
    public InputStream convert(InputStream sourceStream, String sourceExtension) {

        return queueHandler.submitAndWait(sourceStream, sourceExtension, this::doActualConvert);
    }

    /**
     * 真正的转换逻辑
     *
     * @param sourceStream    源文件输入流
     * @param sourceExtension 源文件扩展名
     * @return PDF 格式的输出流
     */
    private InputStream doActualConvert(InputStream sourceStream, String sourceExtension) {
        Path tempInputFile = null;
        Path tempOutputFile = null;
        Path userProfileDir = null;
        Path tempLogFile = null;

        try {
            String baseName = UUID.randomUUID().toString();
            tempInputFile = createTempFile(baseName, sourceExtension);
            tempOutputFile = createTempFile(baseName, "pdf");
            tempLogFile = createTempFile(baseName, "log");
            userProfileDir = Path.of(config.getCachePath(), baseName + "-profile");
            Files.createDirectories(userProfileDir);

            // 写入源文件
            try (OutputStream out = Files.newOutputStream(tempInputFile)) {
                sourceStream.transferTo(out);
            }

            // JODConverter 的 UNO 远程调用在部分复杂 DOCX 上会一直卡在加载文档。
            // 使用独立用户配置目录直接调用 LibreOffice CLI，行为与人工 headless 转换一致。
            ProcessBuilder processBuilder = new ProcessBuilder(List.of(
                    resolveSofficeExecutable(),
                    "--headless",
                    "--invisible",
                    "--nodefault",
                    "--nolockcheck",
                    "--nologo",
                    "--norestore",
                    "-env:UserInstallation=" + userProfileDir.toUri(),
                    "--convert-to", "pdf",
                    "--outdir", tempOutputFile.getParent().toString(),
                    tempInputFile.toString()
            ));
            // 输出写入临时日志文件：转换失败时把 LibreOffice 的真实报错带出来，避免只看到一句「转换失败」
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(tempLogFile.toFile());

            Process process = processBuilder.start();
            long timeoutMs = config.getTaskExecutionTimeout() != null
                    ? config.getTaskExecutionTimeout()
                    : 300_000L;
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new RuntimeException("LibreOffice 命令行转换超时（" + timeoutMs + " ms）" + readLogHint(tempLogFile));
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(tempOutputFile)) {
                throw new RuntimeException("LibreOffice 命令行转换失败，退出码: "
                        + process.exitValue() + readLogHint(tempLogFile));
            }

            // 读取结果
            byte[] pdfData = Files.readAllBytes(tempOutputFile);
            log.info("Office文件转换成功: {} -> PDF, size={}KB",
                    sourceExtension, pdfData.length / 1024);

            return new ByteArrayInputStream(pdfData);

        } catch (IOException e) {
            log.error("文件IO错误", e);
            throw new RuntimeException("文件读写错误", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("文件转换被中断", e);
        } finally {
            cleanupTempFiles(tempInputFile, tempOutputFile, tempLogFile);
            cleanupDirectory(userProfileDir);
        }
    }

    /**
     * 读取 LibreOffice 命令输出，作为异常信息的补充说明。
     */
    private String readLogHint(Path logFile) {
        if (logFile == null) {
            return "";
        }
        try {
            if (!Files.isRegularFile(logFile)) {
                return "";
            }
            String output = Files.readString(logFile).trim();
            if (output.isEmpty()) {
                return "";
            }
            return "；LibreOffice 输出: " + output.substring(0, Math.min(output.length(), 500));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 解析 soffice 可执行文件。
     *
     * <p>必须与启动阶段 {@link LibreOfficePathResolver} 使用同一套解析规则：
     * {@code fs.preview.office.office-home} 未配置时 {@code config.getOfficeHome()} 为 {@code null}，
     * 直接 {@code Path.of(null)} 会抛 NullPointerException，导致所有 Word/Excel/PPT 转换失败
     * （启动日志却显示 LibreOffice 启动成功，问题因此被掩盖）。</p>
     */
    private String resolveSofficeExecutable() {
        String cached = sofficeExecutable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (sofficeExecutable == null) {
                sofficeExecutable = doResolveSofficeExecutable();
            }
            return sofficeExecutable;
        }
    }

    private String doResolveSofficeExecutable() {
        String officeHome = LibreOfficePathResolver.resolveOfficeHome(config.getOfficeHome());
        if (!LibreOfficePathResolver.validateOfficeHome(officeHome)) {
            throw new IllegalStateException("LibreOffice 路径无效，无法转换 Office 文档: " + officeHome
                    + "，请安装 LibreOffice 或配置 fs.preview.office.office-home");
        }
        Path home = Path.of(officeHome);
        List<Path> candidates = List.of(
                home.resolve("program/soffice.exe"),
                home.resolve("program/soffice"),
                home.resolve("MacOS/soffice")
        );
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .map(Path::toString)
                .orElseThrow(() -> new IllegalStateException(
                        "未找到 LibreOffice 可执行文件: " + officeHome));
    }

    private Path createTempFile(String baseName, String extension) throws IOException {
        Path cacheDir = Path.of(config.getCachePath());
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir);
        }
        return cacheDir.resolve(baseName + "." + extension);
    }

    private void cleanupTempFiles(Path... files) {
        for (Path file : files) {
            if (file != null && Files.exists(file)) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    log.warn("临时文件删除失败: {}", file, e);
                }
            }
        }
    }

    private void cleanupDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("临时目录清理失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("临时目录遍历失败: {}", directory, e);
        }
    }
}
