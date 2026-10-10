package com.radar.intel.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationDocumentation;
import org.springframework.ai.observation.conventions.AiProvider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Sentence embeddings from an ONNX transformer: tokenize, run the model, mean-pool the last hidden
 * state over the attention mask. Computes what Spring AI 2.0.1's TransformersEmbeddingModel computes,
 * without its PyTorch dependency, and loads the model by path so the JVM heap never holds it.
 */
public class OnnxEmbeddingModel extends AbstractEmbeddingModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingModel.class);

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static final String OUTPUT = "last_hidden_state";

    private static final DefaultEmbeddingModelObservationConvention CONVENTION =
            new DefaultEmbeddingModelObservationConvention();

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();

    private final HuggingFaceTokenizer tokenizer;

    private final OrtSession session;

    private final Set<String> inputNames;

    private final ObservationRegistry observationRegistry;

    public OnnxEmbeddingModel(Path model, Path tokenizerJson, Map<String, String> tokenizerOptions,
            ObservationRegistry observationRegistry) throws IOException, OrtException {
        // Independent, and each ~2 s of a Cloud Run cold start, so the session builds while the tokenizer loads.
        FutureTask<OrtSession> pending = new FutureTask<>(() -> {
            long[] start = clock();
            try (var options = new OrtSession.SessionOptions()) {
                OrtSession created = environment.createSession(model.toString(), options);
                log.info("ONNX session created: {}", since(start));
                return created;
            }
        });
        Thread.ofPlatform().name("onnx-session").start(pending);
        long[] start = clock();
        try (InputStream in = Files.newInputStream(tokenizerJson)) {
            this.tokenizer = HuggingFaceTokenizer.newInstance(in, tokenizerOptions);
        }
        log.info("Tokenizer loaded: {}", since(start));
        this.session = await(pending);
        this.inputNames = session.getInputNames();
        if (!session.getOutputNames().contains(OUTPUT)) {
            throw new IllegalStateException(model + " has no " + OUTPUT + " output: " + session.getOutputNames());
        }
        this.observationRegistry = observationRegistry;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var context = EmbeddingModelObservationContext.builder()
                .embeddingRequest(request)
                .provider(AiProvider.ONNX.value())
                .build();
        return EmbeddingModelObservationDocumentation.EMBEDDING_MODEL_OPERATION
                .observation(null, CONVENTION, () -> context, observationRegistry)
                .observe(() -> {
                    EmbeddingResponse response = new EmbeddingResponse(embedAll(request.getInstructions()));
                    context.setResponse(response);
                    return response;
                });
    }

    private List<Embedding> embedAll(List<String> texts) {
        Encoding[] encodings = tokenizer.batchEncode(texts);
        long[][] ids = new long[encodings.length][];
        long[][] mask = new long[encodings.length][];
        long[][] types = new long[encodings.length][];
        for (int i = 0; i < encodings.length; i++) {
            ids[i] = encodings[i].getIds();
            mask[i] = encodings[i].getAttentionMask();
            types[i] = encodings[i].getTypeIds();
        }
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, ids);
                OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
                OnnxTensor typesTensor = OnnxTensor.createTensor(environment, types)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idsTensor);
            inputs.put("attention_mask", maskTensor);
            inputs.put("token_type_ids", typesTensor);
            inputs.keySet().retainAll(inputNames);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(OUTPUT).orElseThrow().getValue();
                List<Embedding> embeddings = new ArrayList<>(hidden.length);
                for (int i = 0; i < hidden.length; i++) {
                    embeddings.add(new Embedding(meanPool(hidden[i], mask[i]), i));
                }
                return embeddings;
            }
        }
        catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    /**
     * Masked mean over tokens in float32, summed in the order PyTorch's CPU reduction sums them
     * (multi_row_sum's cascade), so the vectors stay bit-identical to the committed retrieval
     * baseline; a plain running sum drifts by an ulp or two.
     */
    static float[] meanPool(float[][] tokens, long[] mask) {
        int dims = tokens[0].length;
        float[][] weighted = new float[tokens.length][dims];
        float[][] maskRows = new float[tokens.length][1];
        for (int t = 0; t < tokens.length; t++) {
            float m = mask[t];
            maskRows[t][0] = m;
            for (int d = 0; d < dims; d++) {
                weighted[t][d] = tokens[t][d] * m;
            }
        }
        float[] sum = cascadeSum(weighted);
        float divisor = Math.max(cascadeSum(maskRows)[0], 1e-9f);
        for (int d = 0; d < dims; d++) {
            sum[d] /= divisor;
        }
        return sum;
    }

    /** Column sums of rows, as ATen's multi_row_sum (aten/src/ATen/native/cpu/SumKernel.cpp) orders them. */
    static float[] cascadeSum(float[][] rows) {
        int levels = 4;
        int size = rows.length;
        int ceilLog2 = size <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(size - 1);
        int levelPower = Math.max(4, ceilLog2 / levels);
        int levelStep = 1 << levelPower;
        int levelMask = levelStep - 1;
        int cols = rows[0].length;
        float[][] acc = new float[levels][cols];
        int i = 0;
        while (i + levelStep <= size) {
            for (int j = 0; j < levelStep; j++, i++) {
                for (int c = 0; c < cols; c++) {
                    acc[0][c] += rows[i][c];
                }
            }
            for (int j = 1; j < levels; j++) {
                for (int c = 0; c < cols; c++) {
                    acc[j][c] += acc[j - 1][c];
                    acc[j - 1][c] = 0f;
                }
                if ((i & (levelMask << (j * levelPower))) != 0) {
                    break;
                }
            }
        }
        for (; i < size; i++) {
            for (int c = 0; c < cols; c++) {
                acc[0][c] += rows[i][c];
            }
        }
        for (int j = 1; j < levels; j++) {
            for (int c = 0; c < cols; c++) {
                acc[0][c] += acc[j][c];
            }
        }
        return acc[0];
    }

    /** Passages embed as Spring AI's transformers module embedded them: metadata-free formatted content. */
    @Override
    public String getEmbeddingContent(Document document) {
        return document.getFormattedContent(MetadataMode.NONE);
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public void close() throws OrtException {
        try {
            tokenizer.close();
        }
        finally {
            session.close();
        }
    }

    private static OrtSession await(FutureTask<OrtSession> pending) throws OrtException {
        try {
            return pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while creating the ONNX session", e);
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case OrtException ort -> throw ort;
                case RuntimeException runtime -> throw runtime;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
    }

    private static long[] clock() {
        return new long[] {System.nanoTime(), THREADS.getCurrentThreadCpuTime()};
    }

    /** Wall time well above this thread's CPU time is time spent waiting, on I/O or on other threads. */
    private static String since(long[] start) {
        return "wall %d ms, cpu %d ms".formatted((System.nanoTime() - start[0]) / 1_000_000,
                (THREADS.getCurrentThreadCpuTime() - start[1]) / 1_000_000);
    }
}
