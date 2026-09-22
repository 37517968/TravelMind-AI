package com.travelmind.aiagent.controller;

import cn.hutool.core.io.FileUtil;
import com.travelmind.aiagent.constant.FileConstant;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件下载控制器
 * 提供生成的PDF、图片等文件的下载功能
 */
@RestController
@RequestMapping("/file")
public class FileController {

    /**
     * 下载文件
     *
     * @param path     文件路径
     * @param response HTTP响应
     */
    @GetMapping("/download")
    public void downloadFile(@RequestParam String path, HttpServletResponse response) {
        try {
            // URL解码
            String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
            
            // 安全检查：确保文件在允许的目录下
            File file = new File(decodedPath);
            String allowedDir = FileConstant.FILE_SAVE_DIR;
            
            if (!file.getCanonicalPath().startsWith(new File(allowedDir).getCanonicalPath())) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("Access denied: File is outside allowed directory");
                return;
            }
            
            if (!file.exists()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("File not found: " + decodedPath);
                return;
            }
            
            // 设置响应头
            String fileName = file.getName();
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");
            
            response.setContentType(getContentType(fileName));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);
            response.setContentLengthLong(file.length());
            
            // 写入文件内容
            try (OutputStream out = response.getOutputStream()) {
                Files.copy(file.toPath(), out);
                out.flush();
            }
        } catch (IOException e) {
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("Error downloading file: " + e.getMessage());
            } catch (IOException ex) {
                // ignore
            }
        }
    }

    /**
     * 列出可下载的文件
     *
     * @param type 文件类型：pdf/image/all
     * @return 文件列表
     */
    @GetMapping("/list")
    public Map<String, Object> listFiles(@RequestParam(defaultValue = "all") String type) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            File baseDir = new File(FileConstant.FILE_SAVE_DIR);
            if (!baseDir.exists()) {
                result.put("success", true);
                result.put("files", new String[0]);
                return result;
            }
            
            java.util.List<Map<String, Object>> fileList = new java.util.ArrayList<>();
            
            // 遍历子目录
            File[] subDirs = baseDir.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File subDir : subDirs) {
                    if ("all".equals(type) || matchesType(subDir.getName(), type)) {
                        File[] files = subDir.listFiles(File::isFile);
                        if (files != null) {
                            for (File file : files) {
                                Map<String, Object> fileInfo = new HashMap<>();
                                fileInfo.put("name", file.getName());
                                fileInfo.put("path", file.getAbsolutePath());
                                fileInfo.put("size", file.length());
                                fileInfo.put("lastModified", file.lastModified());
                                fileInfo.put("type", subDir.getName());
                                fileInfo.put("downloadUrl", "/api/file/download?path=" + 
                                        URLEncoder.encode(file.getAbsolutePath(), StandardCharsets.UTF_8));
                                fileList.add(fileInfo);
                            }
                        }
                    }
                }
            }
            
            // 按修改时间倒序排序
            fileList.sort((a, b) -> Long.compare(
                    (Long) b.get("lastModified"), 
                    (Long) a.get("lastModified")));
            
            result.put("success", true);
            result.put("files", fileList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    /**
     * 删除文件
     *
     * @param path 文件路径
     * @return 删除结果
     */
    @DeleteMapping("/delete")
    public Map<String, Object> deleteFile(@RequestParam String path) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
            File file = new File(decodedPath);
            String allowedDir = FileConstant.FILE_SAVE_DIR;
            
            // 安全检查
            if (!file.getCanonicalPath().startsWith(new File(allowedDir).getCanonicalPath())) {
                result.put("success", false);
                result.put("error", "Access denied");
                return result;
            }
            
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File not found");
                return result;
            }
            
            boolean deleted = FileUtil.del(file);
            result.put("success", deleted);
            if (!deleted) {
                result.put("error", "Failed to delete file");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    private boolean matchesType(String dirName, String type) {
        return switch (type.toLowerCase()) {
            case "pdf" -> "pdf".equals(dirName) || "travel-plans".equals(dirName);
            case "image" -> "image".equals(dirName) || "download".equals(dirName);
            default -> true;
        };
    }

    private String getContentType(String fileName) {
        String extension = FileUtil.extName(fileName).toLowerCase();
        return switch (extension) {
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            case "txt" -> MediaType.TEXT_PLAIN_VALUE;
            case "json" -> MediaType.APPLICATION_JSON_VALUE;
            case "html" -> MediaType.TEXT_HTML_VALUE;
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}

