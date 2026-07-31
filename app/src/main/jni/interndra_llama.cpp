#include <jni.h>
#include <android/log.h>

#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#define LOG_TAG "InterndraLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaState {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    std::atomic_bool cancelRequested{false};
    // Serializes inference and teardown. nativeCancelImpl intentionally does
    // not wait for this lock, so it can interrupt an active generation.
    std::mutex operationMutex;
};

struct SamplerGuard {
    llama_sampler ** slot;
    explicit SamplerGuard(llama_sampler ** samplerSlot) : slot(samplerSlot) {}
    ~SamplerGuard() {
        if (slot != nullptr && *slot != nullptr) {
            llama_sampler_free(*slot);
            *slot = nullptr;
        }
    }
    SamplerGuard(const SamplerGuard &) = delete;
    SamplerGuard & operator=(const SamplerGuard &) = delete;
};

std::once_flag backendInitOnce;

void ensureBackend() {
    std::call_once(backendInitOnce, [] {
        llama_backend_init();
        LOGI("llama backend initialized");
    });
}

void throwJava(JNIEnv * env, const char * message) {
    jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

std::string fromJava(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string tokenPiece(
    const llama_vocab * vocab,
    llama_token token,
    std::vector<char> & buffer) {
    // Reuse the scratch buffer for every token. Allocating a new vector in the
    // hot generation loop creates avoidable native-heap churn and increases
    // allocator/GC pressure on the Kotlin side when responses are long.
    if (buffer.empty()) buffer.resize(128);
    int32_t length = llama_token_to_piece(
        vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    if (length < 0) {
        // llama.cpp returns the required buffer size as a negative value when
        // the supplied buffer is too small. Guard the conversion against an
        // impossible INT32_MIN result before converting to size_t.
        if (length == INT32_MIN) return {};
        const size_t required = static_cast<size_t>(-length) + 1U;
        if (required > static_cast<size_t>(INT32_MAX)) return {};
        buffer.resize(required);
        length = llama_token_to_piece(
            vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    if (length <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(length));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    if (text.size() > static_cast<size_t>(INT32_MAX)) return {};
    const int32_t textLength = static_cast<int32_t>(text.size());
    int32_t tokenCount = llama_tokenize(
        vocab, text.data(), textLength, nullptr, 0, true, true);
    if (tokenCount >= 0) {
        // Current llama.cpp returns the negative required size when the output
        // buffer is too small. Be defensive for implementations that return 0.
        tokenCount = -tokenCount;
    }
    if (tokenCount <= 0) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(tokenCount));
    const int32_t written = llama_tokenize(
        vocab, text.data(), textLength, tokens.data(), tokenCount, true, true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

// Preserve the system prefix and the user's latest request when an oversized
// prompt exceeds the configured context. This is safer than silently dropping
// either the system policy or the user message entirely.
void fitPromptToContext(std::vector<llama_token> & tokens, size_t maxTokens) {
    if (tokens.size() <= maxTokens) return;
    if (maxTokens < 2) {
        tokens.resize(maxTokens);
        return;
    }
    const size_t prefix = maxTokens / 2;
    const size_t suffix = maxTokens - prefix;
    std::vector<llama_token> fitted;
    fitted.reserve(maxTokens);
    fitted.insert(fitted.end(), tokens.begin(), tokens.begin() + static_cast<ptrdiff_t>(prefix));
    fitted.insert(fitted.end(), tokens.end() - static_cast<ptrdiff_t>(suffix), tokens.end());
    tokens.swap(fitted);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_interndra_ai_LocalAiEngine_nativeInitImpl(
    JNIEnv * env, jclass, jstring modelPath, jint nThreads, jint nCtx) {
    LlamaState * state = nullptr;
    try {
        const std::string path = fromJava(env, modelPath);
        if (path.empty()) {
            throwJava(env, "Model path is empty");
            return 0;
        }

        ensureBackend();
        state = new LlamaState();
        llama_model_params modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        state->model = llama_model_load_from_file(path.c_str(), modelParams);
        if (state->model == nullptr) {
            LOGE("Could not load GGUF model: %s", path.c_str());
            delete state;
            return 0;
        }

        llama_context_params contextParams = llama_context_default_params();
        // Keep malformed JNI inputs from requesting an unbounded context and
        // allocating more native memory than the device can reasonably hold.
        const int boundedContext = std::clamp(static_cast<int>(nCtx), 512, 131072);
        contextParams.n_ctx = static_cast<uint32_t>(boundedContext);
        contextParams.n_batch = std::min<uint32_t>(contextParams.n_ctx, 2048U);
        contextParams.n_threads = std::clamp(static_cast<int>(nThreads), 1, 16);
        contextParams.n_threads_batch = contextParams.n_threads;
        contextParams.no_perf = true;
        state->context = llama_init_from_model(state->model, contextParams);
        if (state->context == nullptr) {
            LOGE("Could not create llama context for: %s", path.c_str());
            llama_model_free(state->model);
            delete state;
            return 0;
        }

        LOGI("Loaded GGUF model with %d threads and %u context tokens",
             contextParams.n_threads, contextParams.n_ctx);
        return reinterpret_cast<jlong>(state);
    } catch (const std::exception & error) {
        LOGE("Native model initialization exception: %s", error.what());
        if (state != nullptr) {
            if (state->context != nullptr) llama_free(state->context);
            if (state->model != nullptr) llama_model_free(state->model);
            delete state;
        }
        return 0;
    } catch (...) {
        LOGE("Native model initialization exception: unknown");
        if (state != nullptr) {
            if (state->context != nullptr) llama_free(state->context);
            if (state->model != nullptr) llama_model_free(state->model);
            delete state;
        }
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_interndra_ai_LocalAiEngine_nativeInferImpl(
    JNIEnv * env, jclass, jlong handle, jstring prompt, jint maxTokens, jfloat temperature) {
    auto * state = reinterpret_cast<LlamaState *>(handle);
    if (state == nullptr || state->model == nullptr || state->context == nullptr) {
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> operation(state->operationMutex);

    try {
    const std::string promptText = fromJava(env, prompt);
    const llama_vocab * vocab = llama_model_get_vocab(state->model);
    std::vector<llama_token> promptTokens = tokenize(vocab, promptText);
    if (promptTokens.empty()) {
        LOGE("Prompt tokenization failed");
        return env->NewStringUTF("");
    }

    llama_memory_clear(llama_get_memory(state->context), true);
    const uint32_t contextSize = llama_n_ctx(state->context);
    // Output cannot exceed the context window. Clamping here also prevents a
    // hostile or malformed JNI caller from forcing an enormous reserve().
    const uint32_t contextOutputLimit = contextSize > 1U ? contextSize - 1U : 1U;
    const int32_t requestedOutput = static_cast<int32_t>(std::min<uint64_t>(
        static_cast<uint64_t>(std::max(1, static_cast<int>(maxTokens))),
        static_cast<uint64_t>(contextOutputLimit)));
    const size_t promptLimit = contextSize > static_cast<uint32_t>(requestedOutput + 1)
        ? static_cast<size_t>(contextSize - static_cast<uint32_t>(requestedOutput + 1))
        : 1U;
    fitPromptToContext(promptTokens, promptLimit);

    llama_batch batch = llama_batch_get_one(
        promptTokens.data(), static_cast<int32_t>(promptTokens.size()));
    if (llama_decode(state->context, batch) != 0) {
        LOGE("Prompt decode failed");
        return env->NewStringUTF("");
    }

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    state->sampler = llama_sampler_chain_init(samplerParams);
    if (state->sampler == nullptr) {
        LOGE("Sampler initialization failed");
        return env->NewStringUTF("");
    }
    // The guard releases the sampler on every return and on C++ exceptions.
    // Keeping the pointer in state also lets nativeFreeImpl clean it during
    // cancellation while operationMutex is held.
    SamplerGuard samplerGuard(&state->sampler);
    if (temperature > 0.001f) {
        llama_sampler_chain_add(state->sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(state->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(state->sampler, llama_sampler_init_greedy());
    }

    std::string generated;
    generated.reserve(static_cast<size_t>(requestedOutput) * 4U);
    std::vector<char> pieceBuffer;
    for (int32_t index = 0; index < requestedOutput; ++index) {
        if (state->cancelRequested.load(std::memory_order_acquire)) break;
        const llama_token token = llama_sampler_sample(state->sampler, state->context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        generated += tokenPiece(vocab, token, pieceBuffer);
        llama_token nextToken = token;
        batch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(state->context, batch) != 0) {
            LOGE("Token decode failed at token %d", index);
            break;
        }
    }

    return env->NewStringUTF(generated.c_str());
    } catch (const std::exception & error) {
        LOGE("Native inference exception: %s", error.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("Native inference exception: unknown");
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_interndra_ai_LocalAiEngine_nativeBeginInferenceImpl(JNIEnv *, jclass, jlong handle) {
    auto * state = reinterpret_cast<LlamaState *>(handle);
    if (state != nullptr) {
        state->cancelRequested.store(false, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL
Java_com_interndra_ai_LocalAiEngine_nativeCancelImpl(JNIEnv *, jclass, jlong handle) {
    auto * state = reinterpret_cast<LlamaState *>(handle);
    if (state != nullptr) {
        state->cancelRequested.store(true, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL
Java_com_interndra_ai_LocalAiEngine_nativeFreeImpl(JNIEnv *, jclass, jlong handle) {
    auto * state = reinterpret_cast<LlamaState *>(handle);
    if (state == nullptr) return;

    state->cancelRequested.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> operation(state->operationMutex);
    if (state->sampler != nullptr) {
        llama_sampler_free(state->sampler);
        state->sampler = nullptr;
    }
    if (state->context != nullptr) {
        llama_free(state->context);
        state->context = nullptr;
    }
    if (state->model != nullptr) {
        llama_model_free(state->model);
        state->model = nullptr;
    }
    delete state;
}

JNIEXPORT jboolean JNICALL
Java_com_interndra_ai_LocalAiEngine_nativeIsLoadedImpl(JNIEnv *, jclass, jlong handle) {
    const auto * state = reinterpret_cast<const LlamaState *>(handle);
    return state != nullptr && state->model != nullptr && state->context != nullptr;
}

} // extern "C"
