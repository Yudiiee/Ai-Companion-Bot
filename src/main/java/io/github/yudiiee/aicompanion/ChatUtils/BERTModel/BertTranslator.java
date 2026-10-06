package io.github.yudiiee.aicompanion.ChatUtils.BERTModel;

import ai.djl.Model;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.Classifications;
import ai.djl.ndarray.*;
import ai.djl.translate.*;

import java.util.*;

public class BertTranslator implements Translator<String, Classifications> {

    private List<String> classes;
    private HuggingFaceTokenizer tokenizer;

    public BertTranslator(List<String> labels) {
        this.classes = labels;
    }


    public void prepare(NDManager manager, Model model) throws Exception {
        tokenizer = HuggingFaceTokenizer.newInstance("distilbert-base-uncased");
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) throws Exception {
        NDManager manager = ctx.getNDManager();

        // Use Encoding instead of SingleToken
        Encoding encoding = tokenizer.encode(input);

        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        // Create NDArrays with proper data type (long)
        NDArray inputIdArray = manager.create(inputIds).expandDims(0);
        NDArray attentionMaskArray = manager.create(attentionMask).expandDims(0);

        return new NDList(inputIdArray, attentionMaskArray);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.get(0);
        return new Classifications(classes, logits);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;  // Handling single input at a time
    }
}