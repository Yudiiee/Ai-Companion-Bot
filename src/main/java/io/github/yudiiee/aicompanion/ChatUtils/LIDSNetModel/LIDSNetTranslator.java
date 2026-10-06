package io.github.yudiiee.aicompanion.ChatUtils.LIDSNetModel;

import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.modality.Classifications;
import java.util.List;

public class LIDSNetTranslator implements Translator<float[], Classifications> {

    private final List<String> classNames;

    public LIDSNetTranslator(List<String> classNames) {
        this.classNames = classNames;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, float[] input) {
        // Shape: [1, input_dim]
        NDArray array = ctx.getNDManager().create(input).reshape(1, input.length);
        return new NDList(array);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) throws Exception {
        NDArray output = list.singletonOrThrow();   // Shape: [1, num_classes]
        NDArray prob = output.softmax(1);           // Shape preserved
        return new Classifications(classNames, prob);
    }

    @Override
    public Batchifier getBatchifier() {
        return null; // Set to Batchifier.STACK for batch inference
    }
}
