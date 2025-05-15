package com.sanss.prc.file_upload_backend.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*")
public class FileUploadController {

    private final String TEMP_FOLDER = "D:\\studyProject\\file_upload_project\\file_upload_backend\\file_upload_backend\\upload_temp";
    private final String UPLOAD_FOLDER = "D:\\studyProject\\file_upload_project\\file_upload_backend\\file_upload_backend\\uploads";

    // 检查分块上传状态
    @GetMapping("/check")
    public ResponseEntity<?> checkChunk(
            @RequestParam String fileHash) {
        log.info("Checking chunk upload status for fileHash: {}", fileHash);
        Path tempDir = Paths.get(TEMP_FOLDER, fileHash);
        if (!Files.exists(tempDir)) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        try {
            List<Integer> uploadedChunks = Files.list(tempDir)
                                                .map(p -> Integer.parseInt(p.getFileName().toString()))
                                                .collect(Collectors.toList());

            return ResponseEntity.ok(uploadedChunks);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    // 上传分块
    @PostMapping
    public ResponseEntity<?> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestParam int chunkIndex,
            @RequestParam String fileHash) {
        log.info("Uploading chunk {} for fileHash: {}", chunkIndex, fileHash);
        try {
            Path tempDir = Paths.get(TEMP_FOLDER, fileHash);
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }

            Path chunkPath = tempDir.resolve(String.valueOf(chunkIndex));
            file.transferTo(chunkPath);

            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    // 合并分块
    @PostMapping("/merge")
    public ResponseEntity<?> mergeChunks(@Validated @RequestBody MergeRequest request) {
        log.info("Merging chunks for fileHash: {}", request.getFileHash());

        try {
            // 安全检查：清理文件名
            String safeFileName = sanitizeFileName(request.getFileName());
            if (safeFileName == null) {
                return ResponseEntity.badRequest().body("Invalid file name");
            }

            Path tempDir = Paths.get(TEMP_FOLDER, request.getFileHash());
            if (!Files.exists(tempDir)) {
                log.warn("Temporary directory not found for hash: {}", request.getFileHash());
                return ResponseEntity.status(400).body("Missing chunks");
            }

            Path uploadsDir = Paths.get(UPLOAD_FOLDER);
            if (!Files.exists(uploadsDir)) {
                Files.createDirectories(uploadsDir);
                log.info("Created upload directory: {}", uploadsDir.toAbsolutePath());
            }

            Path outputPath = uploadsDir.resolve(safeFileName);

            // 合并分块
            try (OutputStream os = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                int totalChunks = (int) Math.ceil((double) request.getFileSize() / request.getChunkSize());
                log.info("Total chunks to merge: {}", totalChunks);

                for (int i = 0; i < totalChunks; i++) {
                    Path chunkPath = tempDir.resolve(String.valueOf(i));
                    if (!Files.exists(chunkPath)) {
                        log.warn("Missing chunk file: {}", chunkPath);
                        return ResponseEntity.status(400).body("Missing chunk: " + i);
                    }

                    long size = Files.size(chunkPath);
                    if (size == 0) {
                        log.warn("Chunk {} is empty", i);
                        return ResponseEntity.status(400).body("Empty chunk: " + i);
                    }

                    log.debug("Copying chunk {} (size: {} bytes)", i, size);
                    Files.copy(chunkPath, os);
                    os.flush(); // 确保每次写入都刷新缓冲区
                }
            }

            // 验证合并后文件大小
            long mergedFileSize = Files.size(outputPath);
            log.info("Merge completed. Final file size: {} bytes", mergedFileSize);
            if (mergedFileSize == 0) {
                log.error("Merged file is empty: {}", outputPath);
                return ResponseEntity.status(500).body("Merged file is empty");
            }

            // 清理临时文件
            FileUtils.deleteDirectory(tempDir.toFile());

            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("Error merging chunks", e);
            return ResponseEntity.status(500).body("Merge failed: " + e.getMessage());
        }
    }


    // 简单文件名校验函数
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\")) {
            return null;
        }
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // 请求参数类
    @Data
    public static class MergeRequest {
        @NotBlank(message = "fileName cannot be blank")
        private String fileName;
        @NotBlank(message = "fileHash cannot be blank")
        private String fileHash;
        @Min(1)
        private long fileSize;
        @Min(1)
        private int chunkSize;
    }
}
