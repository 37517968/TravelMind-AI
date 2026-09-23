package com.travelmind.aiagent.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地兜底 embedding 模型。
 *
 * 不依赖外部 API，使用稳定的 hash 向量，保证知识库初始化和检索可跑通。
 * spring-ai-alibaba 的 dashscopeEmbeddingModel 自带 @Primary，这里不能再标，
 * 否则按 EmbeddingModel 注入会因存在多个 @Primary 直接启动失败；需要本模型的注入点用
 * {@code @Qualifier("localTravelEmbeddingModel")} 显式指定。
 */
@Component
public class LocalTravelEmbeddingModel extends AbstractEmbeddingModel {

    private static final int DIMENSIONS = 128;

    public LocalTravelEmbeddingModel() {
        this.embeddingDimensions.set(DIMENSIONS);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> inputs = request == null ? List.of() : request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(embedText(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document == null ? null : document.getText());
    }

    private float[] embedText(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }

        StringBuilder token = new StringBuilder();
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(ch) || isCjk(ch)) {
                token.append(ch);
            } else {
                addToken(vector, token);
            }
        }
        addToken(vector, token);
        normalize(vector);
        return vector;
    }

    private void addToken(float[] vector, StringBuilder token) {
        if (token.length() == 0) {
            return;
        }

        String value = token.toString();
        token.setLength(0);
        int hash = value.hashCode();
        int index = Math.floorMod(hash, DIMENSIONS);
        vector[index] += 1.0f;
        vector[Math.floorMod(index * 31 + 7, DIMENSIONS)] += 0.5f;
        vector[Math.floorMod(index * 17 + 11, DIMENSIONS)] += 0.25f;
    }

    private void normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum <= 0.0) {
            return;
        }

        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    private boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
