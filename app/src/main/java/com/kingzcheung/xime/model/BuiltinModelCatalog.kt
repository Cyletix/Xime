package com.kingzcheung.xime.model

/** Versioned local catalog: downloads go directly to model publishers, without a market request. */
object BuiltinModelCatalog {
    val models = com.kingzcheung.xime.speech.SpeechModelCatalog.models + listOf(
        ModelInfo("ochwpro", "手写模型", "本地手写识别模型。", ModelCategory.HANDWRITING,
            versions = listOf(ModelVersion(version = "v1.0", size = "6.7 MB",
                files = listOf(
                    ModelFile("ochwpro.onnx", "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/ochwpro.onnx", "eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13", 7001270L),
                    ModelFile("char_index.json", "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json", "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176", 51317L),
                )))),
        ModelInfo("predictive-text-base", "智能联想模型 base 版本", "较大的本地联想模型。", ModelCategory.PREDICTION,
            versions = listOf(ModelVersion(version = "v1.0", size = "34.8 MB",
                files = listOf(
                    ModelFile("vocab.json", "https://www.modelscope.cn/models/bikeand/predictive-text-base/resolve/master/onnx/vocab.json", "3f7a6aa773afe6dacf75701f7861257d36a46a26ac70c0f8ee6dd4032cc3b9c2", 141139L),
                    ModelFile("model_int8_dynamic.onnx", "https://www.modelscope.cn/models/bikeand/predictive-text-base/resolve/master/onnx/model_int8_dynamic.onnx", "e15009c84d9702056ba8b5f6c04b27ae7d0400167647a5e94cb699f24f885a9d", 36353598L),
                )))),
        ModelInfo("predictive-text-small", "智能联想模型 small 版本", "轻量本地联想模型。", ModelCategory.PREDICTION,
            versions = listOf(ModelVersion(version = "v1.0", size = "18.9 MB",
                files = listOf(
                    ModelFile("vocab.json", "https://www.modelscope.cn/models/bikeand/predictive-text-small/resolve/master/vocab.json", "3f7a6aa773afe6dacf75701f7861257d36a46a26ac70c0f8ee6dd4032cc3b9c2", 141139L),
                    ModelFile("model_int8_dynamic.onnx", "https://www.modelscope.cn/models/bikeand/predictive-text-small/resolve/master/model_int8_dynamic.onnx", "85b9e8915bd22a5ac1312146df37d77ee3453b9c836c89e666977e7c118a4225", 19720256L),
                )))),
        ModelInfo("zipformer-zh-int8", "中文 Zipformer int8", "中文流式语音识别，可使用 SenseVoice 二次校正。", ModelCategory.ASR,
            versions = listOf(ModelVersion(version = "v1.0", size = "132.63MB",
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2", sha256 = "5a2832047ea1f97dd0dc595b816c230c4bafad65cfc0341fa57517cadc50afd0",
                files = listOf(
                    ModelFile("encoder.int8.onnx", "", "", 0L),
                    ModelFile("decoder.onnx", "", "", 0L),
                    ModelFile("joiner.int8.onnx", "", "", 0L),
                    ModelFile("tokens.txt", "", "", 0L),
                )))),
    )
}
