package io.github.yudiiee.aicompanion.ChatUtils.PreProcessing;

import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class OpenNLPProcessor {

    private final SentenceDetectorME sentenceDetector;
    private final TokenizerME tokenizer;
    private final POSTaggerME posTagger;
    private final LemmatizerME lemmatizer;

    public OpenNLPProcessor(String modelPath) throws IOException {
        try (
                FileInputStream sentenceModelIn = new FileInputStream(modelPath + "/opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin");
                FileInputStream tokenModelIn = new FileInputStream(modelPath + "/opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin");
                FileInputStream posModelIn = new FileInputStream(modelPath + "/opennlp-en-ud-ewt-pos-1.3-2.5.4.bin");
                FileInputStream lemmatizerModelIn = new FileInputStream(modelPath + "/opennlp-en-ud-ewt-lemmas-1.3-2.5.4.bin")
        ) {
            SentenceModel sentenceModel = new SentenceModel(sentenceModelIn);
            TokenizerModel tokenizerModel = new TokenizerModel(tokenModelIn);
            POSModel posModel = new POSModel(posModelIn);
            LemmatizerModel lemmatizerModel = new LemmatizerModel(lemmatizerModelIn);

            sentenceDetector = new SentenceDetectorME(sentenceModel);
            tokenizer = new TokenizerME(tokenizerModel);
            posTagger = new POSTaggerME(posModel);
            lemmatizer = new LemmatizerME(lemmatizerModel);
        }
    }

    public String[] detectSentences(String text) {
        return sentenceDetector.sentDetect(text);
    }

    public String[] tokenize(String sentence) {
        return tokenizer.tokenize(sentence);
    }

    public String[] posTag(String[] tokens) {
        return posTagger.tag(tokens);
    }

    public String[] lemmatize(String[] tokens, String[] posTags) {
        return lemmatizer.lemmatize(tokens, posTags);
    }

    // Optional: Combined utility for token + POS + lemma in one call
    public List<TokenInfo> analyze(String sentence) {
        String[] tokens = tokenize(sentence);
        String[] posTags = posTag(tokens);
        String[] lemmas = lemmatize(tokens, posTags);

        List<TokenInfo> results = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            results.add(new TokenInfo(tokens[i], posTags[i], lemmas[i]));
        }
        return results;
    }

    // Helper class to encapsulate a token's metadata
    public static class TokenInfo {
        public final String token;
        public final String posTag;
        public final String lemma;

        public TokenInfo(String token, String posTag, String lemma) {
            this.token = token;
            this.posTag = posTag;
            this.lemma = lemma;
        }

        @Override
        public String toString() {
            return token + " (" + posTag + ") → " + lemma;
        }
    }
}
