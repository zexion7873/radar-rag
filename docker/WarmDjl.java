import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Image build step: one embed makes DJL unpack libtorch and the tokenizer natives into DJL_CACHE_DIR,
 * so the image starts with them on disk. Unpacked at runtime instead, they would land in Cloud Run's
 * in-memory filesystem and count against the instance's memory.
 */
public class WarmDjl {

    public static void main(String[] args) throws Exception {
        try (var model = new TransformersEmbeddingModel()) {
            model.setTokenizerResource("file:models/paraphrase/tokenizer.json");
            model.setModelResource("file:models/paraphrase/model.onnx");
            model.setDisableCaching(true);
            model.afterPropertiesSet();
            System.out.println("warm embed: " + model.embed("warm").length + " dims");
        }
    }
}
