package com.travelmind.aiagent.knowledge.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TravelSemanticChunker {
    private final int maxChars;
    private final int minChars;

    public TravelSemanticChunker(@Value("${travel.knowledge.chunk.max-chars:1200}") int maxChars,
                                 @Value("${travel.knowledge.chunk.min-chars:80}") int minChars) {
        this.maxChars = Math.max(200, maxChars);
        this.minChars = Math.max(20, minChars);
    }

    public List<String> split(String content) {
        if (content == null || content.isBlank()) return List.of();
        String cleaned = content.replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        String[] semanticBlocks = cleaned.split("(?m)(?=^#{1,4}\\s|^第[一二三四五六七八九十0-9]+天|^Day\\s*\\d+)|\\n\\s*\\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : semanticBlocks) {
            String block = raw.trim();
            if (block.isEmpty()) continue;
            if (current.length() > 0 && current.length() + block.length() + 2 > maxChars) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (block.length() > maxChars) {
                flush(chunks, current);
                for (int start = 0; start < block.length(); start += maxChars) {
                    chunks.add(block.substring(start, Math.min(block.length(), start + maxChars)));
                }
            } else {
                if (current.length() > 0) current.append("\n\n");
                current.append(block);
            }
        }
        flush(chunks, current);
        if (chunks.size() > 1 && chunks.get(chunks.size() - 1).length() < minChars) {
            String tail = chunks.remove(chunks.size() - 1);
            int last = chunks.size() - 1;
            chunks.set(last, chunks.get(last) + "\n\n" + tail);
        }
        return chunks;
    }

    private void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }
}
