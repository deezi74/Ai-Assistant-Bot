// JNI bridge between dev.pocketagent.core.llm.LlamaBridge and llama.cpp.
//
// One "session" owns a llama_context, a sampler chain and a mirror of the tokens
// currently held in the KV cache. Each completion re-tokenizes the whole chat
// prompt and only evaluates the suffix that differs from the cached tokens, so
// multi-step agent loops reuse the prefix instead of re-reading it every turn.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "PocketAgentNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, "[I] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#define LOGW(...) do { std::fprintf(stderr, "[W] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[E] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#endif

namespace {

constexpr int ERR_TOKENIZE = -1;
constexpr int ERR_CONTEXT_FULL = -2;
constexpr int ERR_DECODE = -3;
constexpr int ERR_ABORTED = -4;
constexpr int ERR_BAD_SESSION = -5;

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::vector<llama_token> cached;
    std::string pendingUtf8;
    int nCtx = 0;
    int nBatch = 0;
    bool finished = true;
    std::atomic<bool> abort{false};
};

bool g_backendReady = false;

void logCallback(ggml_log_level level, const char *text, void * /*user*/) {
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
        case GGML_LOG_LEVEL_WARN: LOGW("%s", text); break;
        case GGML_LOG_LEVEL_INFO: LOGI("%s", text); break;
        default: break;
    }
}

bool abortCallback(void *data) {
    auto *session = static_cast<Session *>(data);
    return session != nullptr && session->abort.load();
}

// Java's NewStringUTF expects modified UTF-8, which breaks on 4-byte sequences
// such as emoji. Build strings from raw bytes through java.lang.String instead.
jstring toJavaString(JNIEnv *env, const std::string &value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                            reinterpret_cast<const jbyte *>(value.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    return result;
}

// Strings handed to Kotlin are read with GetStringUTFChars (modified UTF-8). Convert
// via String.getBytes("UTF-8") so supplementary characters survive.
std::string toUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, charset));
    const jsize len = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return out;
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 code point.
size_t completeUtf8Prefix(const std::string &s) {
    const size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        --i;
        ++back;
        const auto c = static_cast<unsigned char>(s[i]);
        if ((c & 0xC0) != 0x80) {
            size_t need = 1;
            if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            return (n - i >= need) ? n : i;
        }
    }
    return n;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool addSpecial) {
    int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, addSpecial, true);
    if (n <= 0) {
        n = static_cast<int>(text.size()) + 8;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    int got = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                             static_cast<int32_t>(tokens.size()), addSpecial, true);
    if (got < 0) {
        tokens.resize(static_cast<size_t>(-got));
        got = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                             static_cast<int32_t>(tokens.size()), addSpecial, true);
    }
    if (got < 0) return {};
    tokens.resize(static_cast<size_t>(got));
    return tokens;
}

std::string tokenToPiece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::string big(static_cast<size_t>(-n), '\0');
    n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, true);
    if (n < 0) return {};
    big.resize(static_cast<size_t>(n));
    return big;
}

llama_sampler *buildSampler(const llama_vocab *vocab, float temperature, float topP, int topK,
                            float minP, float repeatPenalty, int repeatLastN, uint32_t seed) {
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(params);
    if (repeatPenalty > 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), repeatLastN, repeatPenalty, 0.0f, 0.0f));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }
    if (topK > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    if (minP > 0.0f) llama_sampler_chain_add(chain, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

// Evaluate tokens[start..end) in batches of nBatch; logits only for the final token.
int evaluate(Session *s, const std::vector<llama_token> &tokens, size_t start) {
    llama_batch batch = llama_batch_init(s->nBatch, 0, 1);
    int status = 0;
    for (size_t i = start; i < tokens.size(); i += static_cast<size_t>(s->nBatch)) {
        if (s->abort.load()) { status = ERR_ABORTED; break; }
        const size_t chunkEnd = std::min(tokens.size(), i + static_cast<size_t>(s->nBatch));
        batch.n_tokens = 0;
        for (size_t j = i; j < chunkEnd; ++j) {
            const int k = batch.n_tokens;
            batch.token[k] = tokens[j];
            batch.pos[k] = static_cast<llama_pos>(j);
            batch.n_seq_id[k] = 1;
            batch.seq_id[k][0] = 0;
            batch.logits[k] = (j == tokens.size() - 1) ? 1 : 0;
            batch.n_tokens++;
        }
        const int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            status = (rc == 2) ? ERR_ABORTED : ERR_DECODE;
            LOGE("llama_decode failed rc=%d at token %zu", rc, i);
            break;
        }
        s->cached.insert(s->cached.end(), tokens.begin() + static_cast<long>(i),
                         tokens.begin() + static_cast<long>(chunkEnd));
    }
    llama_batch_free(batch);
    return status;
}

Session *asSession(jlong handle) { return reinterpret_cast<Session *>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeInit(JNIEnv *env, jclass) {
    if (!g_backendReady) {
        llama_log_set(logCallback, nullptr);
        llama_backend_init();
        g_backendReady = true;
    }
    return toJavaString(env, llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeLoadModel(JNIEnv *env, jclass, jstring jpath,
                                                         jint nGpuLayers, jboolean useMmap,
                                                         jboolean useMlock) {
    const std::string path = toUtf8(env, jpath);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = nGpuLayers;
    const bool mmap = useMmap == JNI_TRUE;
    const bool mlock = useMlock == JNI_TRUE;
    params.load_mode = mmap ? (mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP)
                            : (mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE);
    llama_model *model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        LOGE("failed to load model from %s", path.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeFreeModel(JNIEnv *, jclass, jlong handle) {
    auto *model = reinterpret_cast<llama_model *>(handle);
    if (model != nullptr) llama_model_free(model);
}

JNIEXPORT jstring JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeModelInfo(JNIEnv *env, jclass, jlong handle) {
    auto *model = reinterpret_cast<llama_model *>(handle);
    if (model == nullptr) return toJavaString(env, "{}");
    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));
    std::string safeDesc;
    for (const char *p = desc; *p != '\0'; ++p) {
        if (*p == '"' || *p == '\\') safeDesc.push_back('\\');
        safeDesc.push_back(*p);
    }
    char json[512];
    std::snprintf(json, sizeof(json),
                  "{\"description\":\"%s\",\"parameters\":%llu,\"sizeBytes\":%llu,"
                  "\"layers\":%d,\"contextTrain\":%d,\"vocab\":%d,\"hasChatTemplate\":%s}",
                  safeDesc.c_str(),
                  static_cast<unsigned long long>(llama_model_n_params(model)),
                  static_cast<unsigned long long>(llama_model_size(model)),
                  llama_model_n_layer(model),
                  llama_model_n_ctx_train(model),
                  llama_vocab_n_tokens(llama_model_get_vocab(model)),
                  llama_model_chat_template(model, nullptr) != nullptr ? "true" : "false");
    return toJavaString(env, json);
}

JNIEXPORT jlong JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeNewSession(JNIEnv *, jclass, jlong modelHandle,
                                                          jint nCtx, jint nBatch, jint nThreads) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (model == nullptr) return 0;
    auto *s = new Session();
    s->model = model;
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(nCtx);
    params.n_batch = static_cast<uint32_t>(nBatch);
    params.n_ubatch = static_cast<uint32_t>(nBatch);
    params.n_threads = nThreads;
    params.n_threads_batch = nThreads;
    params.no_perf = true;
    params.abort_callback = abortCallback;
    params.abort_callback_data = s;
    s->ctx = llama_init_from_model(model, params);
    if (s->ctx == nullptr) {
        LOGE("llama_init_from_model failed (n_ctx=%d)", nCtx);
        delete s;
        return 0;
    }
    s->nCtx = static_cast<int>(llama_n_ctx(s->ctx));
    s->nBatch = static_cast<int>(llama_n_batch(s->ctx));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeFreeSession(JNIEnv *, jclass, jlong handle) {
    Session *s = asSession(handle);
    if (s == nullptr) return;
    if (s->sampler != nullptr) llama_sampler_free(s->sampler);
    if (s->ctx != nullptr) llama_free(s->ctx);
    delete s;
}

JNIEXPORT jint JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    Session *s = asSession(handle);
    return s == nullptr ? 0 : s->nCtx;
}

JNIEXPORT jstring JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeApplyChatTemplate(JNIEnv *env, jclass, jlong modelHandle,
                                                                 jobjectArray roles, jobjectArray contents,
                                                                 jboolean addAssistant) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (model == nullptr) return nullptr;
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> roleStore(static_cast<size_t>(n));
    std::vector<std::string> contentStore(static_cast<size_t>(n));
    std::vector<llama_chat_message> messages(static_cast<size_t>(n));
    size_t totalChars = 0;
    for (jsize i = 0; i < n; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        roleStore[static_cast<size_t>(i)] = toUtf8(env, role);
        contentStore[static_cast<size_t>(i)] = toUtf8(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        totalChars += contentStore[static_cast<size_t>(i)].size() + 64;
    }
    for (jsize i = 0; i < n; ++i) {
        messages[static_cast<size_t>(i)].role = roleStore[static_cast<size_t>(i)].c_str();
        messages[static_cast<size_t>(i)].content = contentStore[static_cast<size_t>(i)].c_str();
    }
    const char *tmpl = llama_model_chat_template(model, nullptr);
    std::vector<char> buf(totalChars * 2 + 1024);
    int len = -1;
    if (tmpl != nullptr) {
        len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), addAssistant == JNI_TRUE,
                                        buf.data(), static_cast<int32_t>(buf.size()));
        if (len > static_cast<int>(buf.size())) {
            buf.resize(static_cast<size_t>(len) + 1);
            len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), addAssistant == JNI_TRUE,
                                            buf.data(), static_cast<int32_t>(buf.size()));
        }
    }
    if (len < 0) {
        LOGW("model chat template unsupported, falling back to chatml");
        len = llama_chat_apply_template("chatml", messages.data(), messages.size(), addAssistant == JNI_TRUE,
                                        buf.data(), static_cast<int32_t>(buf.size()));
        if (len > static_cast<int>(buf.size())) {
            buf.resize(static_cast<size_t>(len) + 1);
            len = llama_chat_apply_template("chatml", messages.data(), messages.size(), addAssistant == JNI_TRUE,
                                            buf.data(), static_cast<int32_t>(buf.size()));
        }
    }
    if (len < 0) return nullptr;
    return toJavaString(env, std::string(buf.data(), static_cast<size_t>(len)));
}

JNIEXPORT jint JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeCountTokens(JNIEnv *env, jclass, jlong modelHandle, jstring text) {
    auto *model = reinterpret_cast<llama_model *>(modelHandle);
    if (model == nullptr) return ERR_BAD_SESSION;
    return static_cast<jint>(tokenize(llama_model_get_vocab(model), toUtf8(env, text), true).size());
}

JNIEXPORT jint JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeStartCompletion(JNIEnv *env, jclass, jlong handle, jstring jprompt,
                                                               jfloat temperature, jfloat topP, jint topK,
                                                               jfloat minP, jfloat repeatPenalty,
                                                               jint repeatLastN, jint seed, jint maxNewTokens) {
    Session *s = asSession(handle);
    if (s == nullptr || s->ctx == nullptr) return ERR_BAD_SESSION;
    s->abort.store(false);
    s->pendingUtf8.clear();
    s->finished = false;

    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    const std::vector<llama_token> tokens = tokenize(vocab, toUtf8(env, jprompt), true);
    if (tokens.empty()) { s->finished = true; return ERR_TOKENIZE; }
    if (static_cast<int>(tokens.size()) + std::max(1, static_cast<int>(maxNewTokens)) > s->nCtx) {
        s->finished = true;
        return ERR_CONTEXT_FULL;
    }

    size_t common = 0;
    while (common < s->cached.size() && common < tokens.size() && s->cached[common] == tokens[common]) {
        ++common;
    }
    // Always re-evaluate at least the last prompt token so fresh logits exist.
    if (common == tokens.size()) common = tokens.size() - 1;

    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    s->cached.resize(common);

    const int rc = evaluate(s, tokens, common);
    if (rc != 0) {
        // Leave the cache consistent with what was actually decoded.
        llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(s->cached.size()), -1);
        s->finished = true;
        return rc;
    }

    if (s->sampler != nullptr) llama_sampler_free(s->sampler);
    s->sampler = buildSampler(vocab, temperature, topP, topK, minP, repeatPenalty, repeatLastN,
                              seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed));
    return static_cast<jint>(tokens.size());
}

// Returns the next chunk of UTF-8 bytes (possibly empty when a code point is split
// across tokens), or null once the model emits end-of-generation or the context fills.
JNIEXPORT jbyteArray JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeNextToken(JNIEnv *env, jclass, jlong handle) {
    Session *s = asSession(handle);
    if (s == nullptr || s->ctx == nullptr || s->sampler == nullptr || s->finished) return nullptr;
    if (s->abort.load()) { s->finished = true; return nullptr; }

    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    const llama_token token = llama_sampler_sample(s->sampler, s->ctx, -1);
    if (llama_vocab_is_eog(vocab, token) || static_cast<int>(s->cached.size()) >= s->nCtx - 1) {
        s->finished = true;
        if (s->pendingUtf8.empty()) return nullptr;
        // Flush whatever is left; invalid trailing bytes are dropped by the JVM decoder.
        std::string rest;
        rest.swap(s->pendingUtf8);
        jbyteArray out = env->NewByteArray(static_cast<jsize>(rest.size()));
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(rest.size()), reinterpret_cast<const jbyte *>(rest.data()));
        return out;
    }

    s->pendingUtf8 += tokenToPiece(vocab, token);

    llama_token single = token;
    llama_batch batch = llama_batch_get_one(&single, 1);
    if (llama_decode(s->ctx, batch) != 0) {
        LOGE("llama_decode failed during generation");
        s->finished = true;
    } else {
        s->cached.push_back(token);
    }

    const size_t ready = completeUtf8Prefix(s->pendingUtf8);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(ready));
    if (ready > 0) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(ready), reinterpret_cast<const jbyte *>(s->pendingUtf8.data()));
        s->pendingUtf8.erase(0, ready);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeAbort(JNIEnv *, jclass, jlong handle) {
    Session *s = asSession(handle);
    if (s != nullptr) s->abort.store(true);
}

JNIEXPORT void JNICALL
Java_dev_pocketagent_core_llm_LlamaBridge_nativeResetSession(JNIEnv *, jclass, jlong handle) {
    Session *s = asSession(handle);
    if (s == nullptr || s->ctx == nullptr) return;
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
    s->pendingUtf8.clear();
    s->finished = true;
}

}  // extern "C"
